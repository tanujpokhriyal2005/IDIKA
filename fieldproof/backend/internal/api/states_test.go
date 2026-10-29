package api

import (
	"context"
	"testing"
)

// The cloud case: the surveyor was made from the old admin page (no state),
// villages were imported before states were kept, and the map data holds the
// state boundary. A state lead must see exactly the farmers and surveyors of
// their state, and the admin everyone.
func TestStateScope(t *testing.T) {
	base, db := dashServer(t)
	ctx := context.Background()
	mustExec := func(q string, args ...any) {
		t.Helper()
		if _, err := db.Exec(ctx, q, args...); err != nil {
			t.Fatalf("%s: %v", q, err)
		}
	}
	var tanuj string
	db.QueryRow(ctx, `SELECT id FROM surveyors WHERE phone='9876543210'`).Scan(&tanuj)
	mustExec(`UPDATE surveyors SET state=NULL WHERE id=$1`, tanuj)
	mustExec(`UPDATE villages SET state=NULL`)
	mustExec(`UPDATE villages SET lat=19.6, lng=77.2, source='osm' WHERE district='Hingoli'`)
	mustExec(`CREATE TABLE osm_admin (name text, admin_level text, geom geometry(Geometry, 4326))`)
	mustExec(`INSERT INTO osm_admin VALUES
		('Maharashtra', '4', ST_GeomFromText('POLYGON((72 15.5, 81 15.5, 81 22.2, 72 22.2, 72 15.5))', 4326)),
		('Karnataka - Maharashtra border', '4', ST_GeomFromText('LINESTRING(72 15.5, 81 15.5)', 4326))`)

	meera := dashLogin(t, base, "meera.mh", DemoPassword)
	arvind := dashLogin(t, base, "arvind.mp", DemoPassword)
	admin := dashLogin(t, base, "admin", testAdminPassword)
	var tanujFarmers int
	db.QueryRow(ctx, `SELECT count(*) FROM farmers WHERE surveyor_id=$1 AND status='submitted'`, tanuj).Scan(&tanujFarmers)
	if tanujFarmers == 0 {
		t.Fatal("no farmers for the surveyor")
	}
	// The bug: with no state anywhere, the Maharashtra lead sees none of them.
	if p := meera.farmers("surveyor=" + tanuj); p.Total != 0 {
		t.Fatalf("before filling: %d", p.Total)
	}

	if err := FillStates(ctx, db); err != nil {
		t.Fatal(err)
	}
	if p := meera.farmers("surveyor=" + tanuj); p.Total != tanujFarmers {
		t.Fatalf("Maharashtra lead sees %d of %d farmers", p.Total, tanujFarmers)
	}
	if p := arvind.farmers("surveyor=" + tanuj); p.Total != 0 {
		t.Fatalf("Madhya Pradesh lead sees %d Maharashtra farmers", p.Total)
	}
	all := admin.farmers("size=1").Total
	mh, mp := meera.farmers("size=1").Total, arvind.farmers("size=1").Total
	if mh == 0 || mp == 0 || mh+mp != all {
		t.Fatalf("admin %d, Maharashtra %d, Madhya Pradesh %d", all, mh, mp)
	}
	for _, it := range meera.farmers("size=100").Items {
		if it.State != "Maharashtra" {
			t.Fatalf("Maharashtra list has %s from %q", it.FullName, it.State)
		}
	}
	// The surveyor got the state of their farmers, so the lead sees them on Surveyors too.
	var svs struct{ Items []SurveyorRow }
	meera.do("GET", "/v1/dash/surveyors", nil, &svs)
	found := false
	for _, s := range svs.Items {
		found = found || s.ID == tanuj
	}
	if !found {
		t.Fatal("Maharashtra lead doesn't see the surveyor")
	}
	arvind.do("GET", "/v1/dash/surveyors", nil, &svs)
	for _, s := range svs.Items {
		if s.ID == tanuj {
			t.Fatal("Madhya Pradesh lead sees a Maharashtra surveyor")
		}
	}
	// Farmer pages follow the same rule.
	var one string
	db.QueryRow(ctx, `SELECT id FROM farmers WHERE surveyor_id=$1 AND status='submitted' LIMIT 1`, tanuj).Scan(&one)
	if s := meera.do("GET", "/v1/dash/farmers/"+one, nil, nil); s != 200 {
		t.Fatalf("Maharashtra lead opening the farmer: %d", s)
	}
	if s := arvind.do("GET", "/v1/dash/farmers/"+one, nil, nil); s != 404 {
		t.Fatalf("Madhya Pradesh lead opening a Maharashtra farmer: %d", s)
	}

	// A farmer of the same surveyor in another state belongs to that state's lead.
	var delhi int
	if err := db.QueryRow(ctx, `INSERT INTO villages(district, block, name, lat, lng, source, state) VALUES ('East Delhi','Shahdara','Shahdara',28.67,77.29,'gps','Delhi') RETURNING id`).Scan(&delhi); err != nil {
		t.Fatal(err)
	}
	mustExec(`UPDATE farmers SET village_id=$1 WHERE id=$2`, delhi, one)
	mustExec(`INSERT INTO users(username, password_hash, role, full_name, state, must_change_password) VALUES ('dl.lead',$1,'state_lead','Delhi lead','Delhi',false)`, HashPassword("delhi-test-pass"))
	dl := dashLogin(t, base, "dl.lead", "delhi-test-pass")
	if p := dl.farmers(""); p.Total != 1 || p.Items[0].ID != one || p.Items[0].State != "Delhi" {
		t.Fatalf("Delhi lead: %+v", p)
	}
	if p := meera.farmers("surveyor=" + tanuj); p.Total != tanujFarmers-1 {
		t.Fatalf("Maharashtra lead after the move: %d", p.Total)
	}
	if s := meera.do("GET", "/v1/dash/farmers/"+one, nil, nil); s != 404 {
		t.Fatalf("Maharashtra lead opening a Delhi farmer: %d", s)
	}
	// The admin's state filter uses the farmer's state too.
	if p := admin.farmers("state=Delhi"); p.Total != 1 {
		t.Fatalf("admin state=Delhi: %d", p.Total)
	}
	if p := admin.farmers("size=1"); p.Total != all {
		t.Fatalf("admin total changed: %d", p.Total)
	}
	if got := canonicalState("NCT of Delhi"); got != "Delhi" {
		t.Fatalf("canonical state %q", got)
	}
}
