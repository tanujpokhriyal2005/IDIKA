package api

import (
	"context"
	"encoding/json"
	"fmt"
	"strings"
	"testing"
)

func TestLandSwap(t *testing.T) {
	base, db := dashServer(t)
	ctx := context.Background()
	meera := dashLogin(t, base, "meera.mh", DemoPassword)
	phone := login(t, base)

	var fid, sv string
	if err := db.QueryRow(ctx, `SELECT ps.farmer_id, f.surveyor_id FROM farmer_plan_status ps JOIN farmers f ON f.id=ps.farmer_id JOIN surveyors s ON s.id=f.surveyor_id
		WHERE s.phone='9876543210' AND ps.onboarding_ok ORDER BY f.number LIMIT 1`).Scan(&fid, &sv); err != nil {
		t.Fatal(err)
	}
	// Payment approved straight in the database: this test is about the land.
	if _, err := db.Exec(ctx, `INSERT INTO payments(farmer_id, surveyor_id, timber_count, fruit_count, amount_due, amount_paid, method, reference, paid_on, status)
		VALUES ($1,$2,40,60,800,800,'upi','UPI12345',current_date,'approved')`, fid, sv); err != nil {
		t.Fatal(err)
	}
	var raw []byte
	db.QueryRow(ctx, `SELECT points FROM boundaries WHERE farmer_id=$1 AND purpose='onboarding' AND status='active' LIMIT 1`, fid).Scan(&raw)
	var onb []PointIn
	json.Unmarshal(raw, &onb)
	var clat, span float64
	for _, p := range onb {
		clat += p.Lat / float64(len(onb))
	}
	for _, p := range onb {
		span = max(span, p.Lat-clat)
	}
	type walkOut struct {
		Error     string
		Overlap   float64
		DistanceM float64
	}
	// walk: the onboarding shape shifted north by a share of its own height.
	walk := func(shift float64, body map[string]any) (int, walkOut) {
		t.Helper()
		var dense []PointIn
		for i, a := range onb {
			b := onb[(i+1)%len(onb)]
			for k := 0; k < 4; k++ {
				f := float64(k) / 4
				dense = append(dense, PointIn{Lat: a.Lat + (b.Lat-a.Lat)*f + shift*2*span, Lng: a.Lng + (b.Lng-a.Lng)*f, Acc: 4, T: int64(len(dense)) * 5000})
			}
		}
		if body == nil {
			body = map[string]any{}
		}
		body["points"], body["purpose"] = dense, "pre_plant"
		uploadSeq++
		var out walkOut
		s := phone.do("PUT", "/v1/farmers/"+fid+"/boundaries/"+fmt.Sprintf("%08x-2222-4000-8000-%012x", uploadSeq, uploadSeq), body, &out)
		return s, out
	}
	stage := func() string {
		var st PlanState
		phone.do("GET", "/v1/farmers/"+fid+"/plantation", nil, &st)
		return st.Plantation
	}
	task := func() ActionItem {
		var items []ActionItem
		phone.do("GET", "/v1/action-items", nil, &items)
		for _, it := range items {
			if it.FarmerID == fid && strings.HasPrefix(it.ID, "plan-") {
				return it
			}
		}
		return ActionItem{}
	}

	// Far away: refused, nothing saved.
	s, out := walk(8, nil)
	if s != 409 || out.Error != "different_land" || out.Overlap > 0.01 || out.DistanceM < 100 {
		t.Fatalf("far walk: %d %+v", s, out)
	}
	if st := stage(); st != "pre_walk" {
		t.Fatalf("stage after a refused walk: %s", st)
	}
	// About half on the onboarding land: saved with the surveyor's note, flagged in the checks.
	if s, out := walk(0.65, map[string]any{"landNote": "North part is a new bund"}); s != 200 {
		t.Fatalf("half walk: %d %+v", s, out)
	}
	if st := stage(); st != "delivery" {
		t.Fatalf("stage after half walk: %s", st)
	}
	var d DashFarmerDetail
	meera.do("GET", "/v1/dash/farmers/"+fid, nil, &d)
	pl := d.Plan.Plant
	if pl.PreOverlap == nil || *pl.PreOverlap < 0.3 || *pl.PreOverlap > 0.8 || pl.LandNote != "North part is a new bund" || pl.Checks[0].OK || !strings.Contains(pl.Checks[0].Text, "Surveyor's note") {
		t.Fatalf("half walk detail %v %+v", pl.PreOverlap, pl.Checks)
	}
	// Reporting a land change needs the reason, Gat, area and a 7/12.
	change := map[string]any{"reason": "changed_mind", "surveyNo": "52/1", "areaHa": 0.7, "ownerType": "farmer"}
	for name, bad := range map[string]map[string]any{
		"reason": {"reason": "bored", "surveyNo": "52/1", "areaHa": 0.7},
		"gat":    {"reason": "changed_mind", "surveyNo": " ", "areaHa": 0.7},
		"area":   {"reason": "changed_mind", "surveyNo": "52/1", "areaHa": 0},
	} {
		if s, _ := walk(8, map[string]any{"landChange": bad}); s != 400 {
			t.Fatalf("land change without %s: %d", name, s)
		}
	}
	if s, out := walk(8, map[string]any{"landChange": change}); s != 400 || out.Error != "photo" {
		t.Fatalf("land change without a 7/12: %d %+v", s, out)
	}
	phoneUpload(t, phone, fid, "land_change_record", 0, 0)
	if s, out := walk(8, map[string]any{"landChange": change}); s != 200 {
		t.Fatalf("land change: %d %+v", s, out)
	}
	if st := stage(); st != "land_review" {
		t.Fatalf("stage after land change: %s", st)
	}
	if it := task(); it.Kind != "land_change_review" || it.Note != "Gat 52/1" {
		t.Fatalf("task %+v", it)
	}
	// Delivery waits for the decision.
	if s := phone.do("POST", "/v1/farmers/"+fid+"/delivery", map[string]any{"timberCounted": 40, "fruitCounted": 60, "healthy": true, "rightSpecies": true, "satisfied": true}, nil); s != 409 {
		t.Fatalf("delivery during land review: %d", s)
	}
	review := func(dec, msg string) int {
		return meera.do("POST", "/v1/dash/farmers/"+fid+"/land-change-review", map[string]string{"decision": dec, "message": msg}, nil)
	}
	if s := review("rejected", ""); s != 400 {
		t.Fatalf("reject without message: %d", s)
	}
	if s := review("pending", "The 7/12 photo is blurry."); s != 200 {
		t.Fatalf("pending: %d", s)
	}
	if it := task(); it.Kind != "fix_land_change" || !it.Attention || it.Note != "The 7/12 photo is blurry." || it.ReviewerName != "Meera Kulkarni, State lead" {
		t.Fatalf("fix task %+v", it)
	}
	phoneUpload(t, phone, fid, "land_change_record", 0, 0)
	if st := stage(); st != "land_review" {
		t.Fatalf("stage after a new 7/12: %s", st)
	}
	msg := "Plant on Gat 45/2 as agreed at onboarding."
	if s := review("rejected", msg); s != 200 {
		t.Fatalf("reject: %d", s)
	}
	if st := stage(); st != "pre_walk" {
		t.Fatalf("stage after rejection: %s", st)
	}
	if it := task(); it.Kind != "walk_pre_plant" || !it.Attention || it.Note != msg || it.Decision != "rejected" {
		t.Fatalf("walk again task %+v", it)
	}
	if s := review("approved", ""); s != 409 {
		t.Fatalf("decision with nothing waiting: %d", s)
	}
	// Reporting again needs a new 7/12 after the rejection.
	if s, out := walk(8, map[string]any{"landChange": change}); s != 400 || out.Error != "photo" {
		t.Fatalf("second report without a new 7/12: %d %+v", s, out)
	}
	phoneUpload(t, phone, fid, "land_change_record", 0, 0)
	if s, _ := walk(8, map[string]any{"landChange": change}); s != 200 {
		t.Fatalf("second report: %d", s)
	}
	if s := review("approved", "ignored"); s != 200 {
		t.Fatalf("approve: %d", s)
	}
	if st := stage(); st != "delivery" {
		t.Fatalf("stage after approval: %s", st)
	}
	meera.do("GET", "/v1/dash/farmers/"+fid, nil, &d)
	lc := d.Plan.Plant.LandChange
	if lc == nil || lc.Status != "approved" || lc.SurveyNo != "52/1" || lc.ReasonText != "Farmer changed his mind" || len(lc.Photos) != 3 || !d.Plan.Plant.Checks[0].OK {
		t.Fatalf("approved land change %+v %+v", lc, d.Plan.Plant.Checks)
	}
	// Walking the registered land clears the land change.
	if s, _ := walk(0, nil); s != 200 {
		t.Fatalf("registered walk: %d", s)
	}
	meera.do("GET", "/v1/dash/farmers/"+fid, nil, &d)
	if d.Plan.Plant.LandChange != nil || *d.Plan.Plant.PreOverlap < 0.99 {
		t.Fatalf("after walking the registered land %+v", d.Plan.Plant.LandChange)
	}
	if p := meera.farmers("plantation=land_change"); p.Total != 0 {
		t.Fatalf("land change filter %d", p.Total)
	}
}
