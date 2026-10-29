package api

// Dashboard integration tests: sign-in, who sees what, every filter and its
// edge cases, review decisions, the task loop to the surveyor's phone, and
// issuing logins. Same TEST_DATABASE_URL setup as api_test.go.

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"mime/multipart"
	"net/http"
	"net/http/cookiejar"
	"net/url"
	"strings"
	"testing"

	"github.com/jackc/pgx/v5/pgxpool"
)

const testAdminPassword = "admin-test-pass"

func dashServer(t *testing.T) (string, *pgxpool.Pool) {
	srv, db := testServer(t)
	ctx := context.Background()
	if err := EnsureAdmin(ctx, db, "admin", testAdminPassword); err != nil {
		t.Fatal(err)
	}
	if err := SeedDashboard(ctx, db, t.TempDir()); err != nil {
		t.Fatal(err)
	}
	return srv.URL, db
}

type dashClient struct {
	t    *testing.T
	base string
	http *http.Client
}

func newDash(t *testing.T, base string) *dashClient {
	jar, _ := cookiejar.New(nil)
	return &dashClient{t: t, base: base, http: &http.Client{Jar: jar}}
}

func (c *dashClient) req(method, path string, body any, out any, csrf bool) int {
	c.t.Helper()
	var rd io.Reader
	if body != nil {
		b, _ := json.Marshal(body)
		rd = bytes.NewReader(b)
	}
	req, _ := http.NewRequest(method, c.base+path, rd)
	req.Header.Set("Content-Type", "application/json")
	if csrf {
		req.Header.Set("X-Fieldproof", "1")
	}
	resp, err := c.http.Do(req)
	if err != nil {
		c.t.Fatal(err)
	}
	defer resp.Body.Close()
	data, _ := io.ReadAll(resp.Body)
	if out != nil && len(data) > 0 {
		if err := json.Unmarshal(data, out); err != nil {
			c.t.Fatalf("%s %s: %d %s", method, path, resp.StatusCode, data)
		}
	}
	return resp.StatusCode
}

func (c *dashClient) do(method, path string, body, out any) int { return c.req(method, path, body, out, true) }

func dashLogin(t *testing.T, base, user, pass string) *dashClient {
	t.Helper()
	c := newDash(t, base)
	var u DashUser
	if s := c.do("POST", "/v1/dash/login", map[string]any{"username": user, "password": pass}, &u); s != 200 {
		t.Fatalf("login %s: %d", user, s)
	}
	return c
}

type farmerPage struct {
	Total int             `json:"total"`
	Items []DashFarmerRow `json:"items"`
}

func (c *dashClient) farmers(q string) farmerPage {
	c.t.Helper()
	var p farmerPage
	if s := c.do("GET", "/v1/dash/farmers?"+q, nil, &p); s != 200 {
		c.t.Fatalf("farmers?%s: %d", q, s)
	}
	return p
}

func TestPasswordsAndUsernames(t *testing.T) {
	h := HashPassword("correct horse")
	if !CheckPassword(h, "correct horse") || CheckPassword(h, "Correct horse") || CheckPassword(h, "") {
		t.Fatal("password check")
	}
	if HashPassword("x") == HashPassword("x") {
		t.Fatal("hashes must be salted")
	}
	for _, bad := range []string{"", "pbkdf2-sha256$x$y$z", "md5$1$aa$bb", "pbkdf2-sha256$0$AA$AA"} {
		if CheckPassword(bad, "x") {
			t.Fatalf("malformed hash accepted: %q", bad)
		}
	}
	for u, ok := range map[string]bool{
		"meera.mh": true, "abc": true, "a1.b2.c3": true, "ab": false, ".abc": false, "abc.": false, "a..b": false,
		"Meera": false, "meera_mh": false, "meera mh": false, strings.Repeat("a", 32): true, strings.Repeat("a", 33): false,
	} {
		if ValidUsername(u) != ok {
			t.Fatalf("ValidUsername(%q) = %v", u, !ok)
		}
	}
	if p := TempPassword(); len(p) != 11 || p[5] != '-' || strings.ContainsAny(p, "0O1lI") {
		t.Fatalf("temp password %q", p)
	}
}

func TestDashSignIn(t *testing.T) {
	base, _ := dashServer(t)
	c := newDash(t, base)
	if s := c.req("POST", "/v1/dash/login", map[string]any{"username": "admin", "password": testAdminPassword}, nil, false); s != 403 {
		t.Fatalf("login without CSRF header: %d", s)
	}
	if s := c.do("GET", "/v1/dash/me", nil, nil); s != 401 {
		t.Fatalf("me before login: %d", s)
	}
	if s := c.do("POST", "/v1/dash/login", map[string]any{"username": "nobody", "password": "whatever1"}, nil); s != 401 {
		t.Fatalf("unknown user: %d", s)
	}
	// Username is case-insensitive.
	if s := c.do("POST", "/v1/dash/login", map[string]any{"username": " ADMIN ", "password": testAdminPassword}, nil); s != 200 {
		t.Fatalf("admin login: %d", s)
	}
	if s := c.req("POST", "/v1/dash/users/x/active", map[string]any{"active": false}, nil, false); s != 403 {
		t.Fatalf("POST without CSRF header: %d", s)
	}
	// Surveyors use the phone app.
	if s := newDash(t, base).do("POST", "/v1/dash/login", map[string]any{"username": "priya.shinde", "password": DemoPassword}, nil); s != 403 {
		t.Fatalf("surveyor dashboard login: %d", s)
	}
	// Lockout after repeated wrong passwords, even with the right one afterwards.
	lc := newDash(t, base)
	for i := 0; i < maxFailedLogins; i++ {
		if s := lc.do("POST", "/v1/dash/login", map[string]any{"username": "arvind.mp", "password": "wrong-pass"}, nil); s != 401 {
			t.Fatalf("wrong password %d: %d", i, s)
		}
	}
	if s := lc.do("POST", "/v1/dash/login", map[string]any{"username": "arvind.mp", "password": DemoPassword}, nil); s != 429 {
		t.Fatalf("locked account signed in: %d", s)
	}
	// Sign out ends the session.
	c.do("POST", "/v1/dash/logout", nil, nil)
	if s := c.do("GET", "/v1/dash/me", nil, nil); s != 401 {
		t.Fatalf("me after logout: %d", s)
	}
}

func TestDashScope(t *testing.T) {
	base, db := dashServer(t)
	ctx := context.Background()
	admin := dashLogin(t, base, "admin", testAdminPassword)
	meera := dashLogin(t, base, "meera.mh", DemoPassword)
	green := dashLogin(t, base, "greenearth.hingoli", DemoPassword)

	all := admin.farmers("size=100")
	var mh, mp int
	for _, st := range []string{"Maharashtra", "Madhya Pradesh"} {
		n := admin.farmers("state=" + url.QueryEscape(st)).Total
		if st == "Maharashtra" {
			mh = n
		} else {
			mp = n
		}
	}
	if mh == 0 || mp == 0 || mh+mp != all.Total {
		t.Fatalf("admin totals: all %d, MH %d, MP %d", all.Total, mh, mp)
	}
	if got := meera.farmers("").Total; got != mh {
		t.Fatalf("state lead sees %d farmers, want %d", got, mh)
	}
	for _, f := range meera.farmers("size=100").Items {
		if f.State != "Maharashtra" {
			t.Fatalf("state lead sees a farmer in %s", f.State)
		}
	}
	// Asking for another state narrows to nothing; it never widens.
	if n := meera.farmers("state=" + url.QueryEscape("Madhya Pradesh")).Total; n != 0 {
		t.Fatalf("state lead saw %d farmers of another state", n)
	}
	var mpFarmer, vasuFarmer, mpFile string
	db.QueryRow(ctx, `SELECT f.id FROM farmers f JOIN surveyors s ON s.id=f.surveyor_id WHERE s.state='Madhya Pradesh' AND f.status='submitted' LIMIT 1`).Scan(&mpFarmer)
	db.QueryRow(ctx, `SELECT x.id FROM files x WHERE x.farmer_id=$1 LIMIT 1`, mpFarmer).Scan(&mpFile)
	db.QueryRow(ctx, `SELECT f.id FROM farmers f JOIN surveyors s ON s.id=f.surveyor_id JOIN users n ON n.id=s.ngo_user_id WHERE n.username='vasundhara.trust' LIMIT 1`).Scan(&vasuFarmer)
	for _, p := range []string{"/v1/dash/farmers/" + mpFarmer, "/v1/dash/files/" + mpFile, "/v1/dash/farmers/" + mpFarmer + "/kml"} {
		if s := meera.do("GET", p, nil, nil); s != 404 {
			t.Fatalf("state lead GET %s: %d", p, s)
		}
		if s := admin.do("GET", p, nil, nil); s != 200 {
			t.Fatalf("admin GET %s: %d", p, s)
		}
	}
	if s := meera.do("POST", "/v1/dash/farmers/"+mpFarmer+"/review", map[string]any{"decisions": map[string]any{"aadhaar": map[string]string{"decision": "approved"}}}, nil); s != 404 {
		t.Fatalf("state lead reviewed another state's farmer: %d", s)
	}
	// An NGO sees only its own surveyors' farmers.
	if s := green.do("GET", "/v1/dash/farmers/"+vasuFarmer, nil, nil); s != 404 {
		t.Fatalf("NGO opened another NGO's farmer: %d", s)
	}
	for _, f := range green.farmers("size=100").Items {
		if f.NGO != "Green Earth Hingoli" {
			t.Fatalf("NGO sees farmer of %q", f.NGO)
		}
	}
	var ngos struct{ Items []NGORow }
	meera.do("GET", "/v1/dash/ngos", nil, &ngos)
	for _, n := range ngos.Items {
		if n.State != "Maharashtra" {
			t.Fatalf("state lead sees NGO in %s", n.State)
		}
	}
	var svs struct{ Items []SurveyorRow }
	green.do("GET", "/v1/dash/surveyors", nil, &svs)
	for _, s := range svs.Items {
		if s.NGO != "Green Earth Hingoli" {
			t.Fatalf("NGO sees surveyor of %q", s.NGO)
		}
	}
	if s := meera.do("GET", "/v1/dash/state-leads", nil, nil); s != 403 {
		t.Fatalf("state lead listed state leads: %d", s)
	}
	// Farmers that haven't finished onboarding are not on the dashboard.
	var registered string
	db.QueryRow(ctx, `SELECT id FROM farmers WHERE status <> 'submitted' LIMIT 1`).Scan(&registered)
	if s := admin.do("GET", "/v1/dash/farmers/"+registered, nil, nil); s != 404 {
		t.Fatalf("unfinished farmer visible: %d", s)
	}
}

func TestDashFilters(t *testing.T) {
	base, db := dashServer(t)
	ctx := context.Background()
	a := dashLogin(t, base, "admin", testAdminPassword)
	total := a.farmers("").Total

	// Each document filter returns only farmers with that status.
	for p, key := range docParam {
		sum := 0
		for st := range docStatuses {
			pg := a.farmers(fmt.Sprintf("%s=%s&size=100", p, st))
			sum += pg.Total
			for _, f := range pg.Items {
				if f.Docs[key] != st {
					t.Fatalf("%s=%s returned %s", p, st, f.Docs[key])
				}
			}
		}
		if sum != total {
			t.Fatalf("%s statuses add up to %d, want %d", p, sum, total)
		}
		if a.farmers(p+"=any").Total != total {
			t.Fatalf("%s=any should not filter", p)
		}
	}
	// Combining filters is AND.
	both := a.farmers("aadhaar=approved&land=pending&size=100")
	for _, f := range both.Items {
		if f.Docs["aadhaar"] != "approved" || f.Docs["land_record"] != "pending" {
			t.Fatalf("combined filter returned %+v", f.Docs)
		}
	}
	if both.Total > a.farmers("land=pending").Total {
		t.Fatal("AND made the list bigger")
	}
	// Quick review filters.
	for _, f := range a.farmers("review=waiting&size=100").Items {
		if !strings.Contains(fmt.Sprint(f.Docs), "new") {
			t.Fatalf("waiting filter returned %+v", f.Docs)
		}
	}
	for _, f := range a.farmers("review=approved&size=100").Items {
		for _, st := range f.Docs {
			if st != "approved" {
				t.Fatalf("approved filter returned %+v", f.Docs)
			}
		}
	}
	// Place filters, including a village that isn't in the chosen block.
	// The busiest village of the seed, and a village in another state.
	var vid, otherVillage int
	var vDistrict, vBlock string
	db.QueryRow(ctx, `SELECT v.id, v.district, v.block FROM farmers f JOIN villages v ON v.id=f.village_id WHERE f.status='submitted'
		GROUP BY v.id ORDER BY count(*) DESC LIMIT 1`).Scan(&vid, &vDistrict, &vBlock)
	db.QueryRow(ctx, `SELECT id FROM villages WHERE district='Sehore' LIMIT 1`).Scan(&otherVillage)
	place := "district=" + url.QueryEscape(vDistrict) + "&block=" + url.QueryEscape(vBlock)
	inBlock := a.farmers(place + "&size=100")
	if inBlock.Total == 0 {
		t.Fatal("no farmers in the busiest block")
	}
	for _, f := range inBlock.Items {
		if f.District != vDistrict || f.Block != vBlock {
			t.Fatalf("place filter returned %s/%s", f.District, f.Block)
		}
	}
	if n := a.farmers(fmt.Sprintf("%s&village=%d", place, vid)).Total; n == 0 || n > inBlock.Total {
		t.Fatalf("village filter matched %d of %d", n, inBlock.Total)
	}
	if n := a.farmers(fmt.Sprintf("%s&village=%d", place, otherVillage)).Total; n != 0 {
		t.Fatalf("village outside the block matched %d", n)
	}
	if n := a.farmers("district=Nowhere").Total; n != 0 {
		t.Fatalf("unknown district matched %d", n)
	}
	// Paging: beyond the last page is empty but keeps the total; size is capped.
	pg := a.farmers("page=999")
	if len(pg.Items) != 0 || pg.Total != total {
		t.Fatalf("page 999: %d items, total %d", len(pg.Items), pg.Total)
	}
	var big struct{ Size int }
	a.do("GET", "/v1/dash/farmers?size=1000", nil, &big)
	if big.Size != 100 {
		t.Fatalf("size not capped: %d", big.Size)
	}
	// Pages don't overlap and cover everything.
	seen := map[string]bool{}
	for p := 1; p <= (total+24)/25; p++ {
		for _, f := range a.farmers(fmt.Sprintf("page=%d&sort=name", p)).Items {
			if seen[f.ID] {
				t.Fatalf("farmer %s on two pages", f.Code)
			}
			seen[f.ID] = true
		}
	}
	if len(seen) != total {
		t.Fatalf("pages covered %d of %d", len(seen), total)
	}
	// Search: code, name, mobile; LIKE wildcards are literal text.
	var code, name, mobile string
	db.QueryRow(ctx, `SELECT code, full_name, mobile FROM farmers WHERE status='submitted' ORDER BY number LIMIT 1`).Scan(&code, &name, &mobile)
	for _, q := range []string{code, strings.ToUpper(code), strings.Fields(name)[0], mobile, "+91 " + mobile} {
		if a.farmers("q="+url.QueryEscape(q)).Total == 0 {
			t.Fatalf("search %q found nothing", q)
		}
	}
	for _, q := range []string{"%", "_", `\`, "'; DROP TABLE farmers; --"} {
		if n := a.farmers("q=" + url.QueryEscape(q)).Total; n != 0 {
			t.Fatalf("search %q matched %d", q, n)
		}
	}
	// Bad values are refused with a message, never ignored silently.
	for _, q := range []string{"aadhaar=bogus", "land=APPROVED", "village=abc", "village=-3", "ngo=not-a-uuid", "surveyor=1",
		"page=0", "page=x", "size=0", "sort=random", "review=everything", "q=" + strings.Repeat("a", 101)} {
		var e apiError
		if s := a.do("GET", "/v1/dash/farmers?"+q, nil, &e); s != 400 || e.Message == "" {
			t.Fatalf("%s: %d %+v", q, s, e)
		}
	}
	// Dropdown options cascade and stay inside the person's area.
	var o struct {
		States, Districts, Blocks []string
		Villages                  []Option
	}
	meera := dashLogin(t, base, "meera.mh", DemoPassword)
	meera.do("GET", "/v1/dash/options", nil, &o)
	if len(o.States) != 1 || o.States[0] != "Maharashtra" || len(o.Blocks) != 0 || len(o.Villages) != 0 {
		t.Fatalf("state lead options %+v", o)
	}
	for _, d := range o.Districts {
		if d == "Sehore" {
			t.Fatal("state lead offered a district of another state")
		}
	}
	meera.do("GET", "/v1/dash/options?district=Hingoli", nil, &o)
	if len(o.Blocks) == 0 || len(o.Villages) != 0 {
		t.Fatalf("blocks %v villages %v", o.Blocks, o.Villages)
	}
	meera.do("GET", "/v1/dash/options?"+place, nil, &o)
	if len(o.Villages) == 0 {
		t.Fatal("no villages for Basmath")
	}
	// Overview, badge and list agree.
	var ov struct{ Tiles map[string]any }
	a.do("GET", "/v1/dash/overview", nil, &ov)
	var cnt struct{ Waiting int }
	a.do("GET", "/v1/dash/counts", nil, &cnt)
	if w := a.farmers("review=waiting").Total; int(ov.Tiles["waiting"].(float64)) != w || cnt.Waiting != w {
		t.Fatalf("waiting: overview %v, badge %d, list %d", ov.Tiles["waiting"], cnt.Waiting, w)
	}
	for _, r := range []string{"30", "90", "365", "all"} {
		if s := a.do("GET", "/v1/dash/insights?range="+r, nil, nil); s != 200 {
			t.Fatalf("insights %s: %d", r, s)
		}
	}
	if s := a.do("GET", "/v1/dash/insights?range=7", nil, nil); s != 400 {
		t.Fatalf("bad insights range: %d", s)
	}
}

func TestDashReviewToPhone(t *testing.T) {
	base, db := dashServer(t)
	ctx := context.Background()
	meera := dashLogin(t, base, "meera.mh", DemoPassword)
	phone := login(t, base) // Tanuj, the surveyor of the seeded Hingoli farmers

	// A Tanuj farmer with all documents and nothing reviewed yet.
	var fid string
	if err := db.QueryRow(ctx, `SELECT f.id FROM farmers f JOIN surveyors s ON s.id=f.surveyor_id JOIN farmer_doc_status ds ON ds.farmer_id=f.id
		WHERE s.phone='9876543210' AND f.status='submitted' AND ds.aadhaar='new' AND ds.water_source='new' AND ds.boundary='new' LIMIT 1`).Scan(&fid); err != nil {
		t.Fatal(err)
	}
	review := func(dec map[string]any) (int, map[string]any) {
		var out map[string]any
		s := meera.do("POST", "/v1/dash/farmers/"+fid+"/review", map[string]any{"decisions": dec}, &out)
		return s, out
	}
	for name, dec := range map[string]map[string]any{
		"empty":           {},
		"reject no msg":   {"water_source": map[string]string{"decision": "rejected", "message": "   "}},
		"pending no msg":  {"aadhaar": map[string]string{"decision": "pending"}},
		"bad decision":    {"aadhaar": map[string]string{"decision": "maybe"}},
		"unknown doc":     {"pan_card": map[string]string{"decision": "approved"}},
		"message too long": {"aadhaar": map[string]string{"decision": "pending", "message": strings.Repeat("x", 1001)}},
	} {
		if s, _ := review(dec); s != 400 {
			t.Fatalf("%s: %d", name, s)
		}
	}
	// Nothing was saved by the refused requests.
	var n int
	db.QueryRow(ctx, `SELECT count(*) FROM doc_events WHERE farmer_id=$1`, fid).Scan(&n)
	if n != 0 {
		t.Fatalf("refused review left %d events", n)
	}
	msg := "This is the neighbour's well. Photograph the farmer's own borewell."
	s, out := review(map[string]any{
		"water_source": map[string]string{"decision": "rejected", "message": msg},
		"aadhaar":      map[string]string{"decision": "approved", "message": "ignored for approvals"},
	})
	if s != 200 || fmt.Sprint(out["tasksSent"]) != "[water_source]" {
		t.Fatalf("review: %d %v", s, out)
	}
	var d DashFarmerDetail
	meera.do("GET", "/v1/dash/farmers/"+fid, nil, &d)
	if d.Docs["water_source"].Status != "rejected" || d.Docs["water_source"].Message != msg || d.Docs["aadhaar"].Status != "approved" || d.Docs["aadhaar"].Message != "" {
		t.Fatalf("docs after review %+v", d.Docs)
	}
	if len(d.History) != 2 || !strings.Contains(d.History[0].By, "Meera Kulkarni") {
		t.Fatalf("history %+v", d.History)
	}
	// The task is on Tanuj's phone, with a red dot, the message and the other documents' status.
	var items []ActionItem
	phone.do("GET", "/v1/action-items", nil, &items)
	var task *ActionItem
	for i := range items {
		if items[i].FarmerID == fid {
			task = &items[i]
		}
		// Review tasks come before everything else.
		if i > 0 && items[i].Attention && !items[i-1].Attention {
			t.Fatalf("review task below an ordinary one: %+v", items)
		}
	}
	if task == nil || task.Kind != "fix_water_source" || !task.Attention || task.Note != msg ||
		task.ReviewerName != "Meera Kulkarni, State lead" || task.Docs["aadhaar"] != "approved" || task.Docs["water_source"] != "rejected" {
		t.Fatalf("phone task %+v", task)
	}
	itemID := task.ID
	// ...and on nobody else's.
	var other int
	db.QueryRow(ctx, `SELECT count(*) FROM action_items a JOIN surveyors s ON s.id=a.surveyor_id WHERE a.farmer_id=$1 AND s.phone<>'9876543210'`, fid).Scan(&other)
	if other != 0 {
		t.Fatal("task went to another surveyor")
	}
	// Changing the message updates the same open task instead of adding another.
	review(map[string]any{"water_source": map[string]string{"decision": "pending", "message": "Also show the pump."}})
	db.QueryRow(ctx, `SELECT count(*) FROM action_items WHERE farmer_id=$1 AND kind='fix_water_source' AND status='open'`, fid).Scan(&n)
	if n != 1 {
		t.Fatalf("%d open water tasks", n)
	}
	// Marking it done without sending anything new is refused.
	if s := phone.do("POST", "/v1/action-items/"+itemID+"/complete", map[string]any{}, nil); s != 409 {
		t.Fatalf("complete without a new photo: %d", s)
	}
	// The surveyor sends a new photo: back to New, history notes it, task closes.
	var body bytes.Buffer
	mw := multipart.NewWriter(&body)
	mw.WriteField("id", "11111111-2222-4333-8444-555555555555")
	mw.WriteField("kind", "water_source")
	fw, _ := mw.CreateFormFile("file", "w.jpg")
	fw.Write(sampleImage("water_source"))
	mw.Close()
	req, _ := http.NewRequest("POST", base+"/v1/farmers/"+fid+"/files", &body)
	req.Header.Set("Content-Type", mw.FormDataContentType())
	req.Header.Set("Authorization", "Bearer "+phone.token)
	resp, err := http.DefaultClient.Do(req)
	if err != nil || resp.StatusCode != 201 {
		t.Fatalf("upload: %v %v", err, resp.Status)
	}
	resp.Body.Close()
	meera.do("GET", "/v1/dash/farmers/"+fid, nil, &d)
	if d.Docs["water_source"].Status != "new" || d.History[len(d.History)-1].Event != "reuploaded" {
		t.Fatalf("after re-upload %+v / %+v", d.Docs["water_source"], d.History[len(d.History)-1])
	}
	phone.do("GET", "/v1/action-items", nil, &items)
	for _, it := range items {
		if it.Kind == "fix_water_source" && it.FarmerID == fid {
			t.Fatal("task still open after the new photo")
		}
	}
	if s := phone.do("POST", "/v1/action-items/"+itemID+"/complete", map[string]any{}, nil); s != 200 {
		t.Fatalf("complete after the photo (already closed): %d", s)
	}
	// Approving closes a task too.
	review(map[string]any{"boundary": map[string]string{"decision": "pending", "message": "Walk the east side again."}})
	// Approving only sends no tasks: an empty list, never null (the page lists them).
	if s, out := review(map[string]any{"boundary": map[string]string{"decision": "approved"}}); s != 200 || fmt.Sprint(out["tasksSent"]) != "[]" {
		t.Fatalf("approve only: %d %#v", s, out["tasksSent"])
	}
	db.QueryRow(ctx, `SELECT count(*) FROM action_items WHERE farmer_id=$1 AND kind='fix_boundary' AND status='open'`, fid).Scan(&n)
	if n != 0 {
		t.Fatal("approval left the boundary task open")
	}
	// Approving a document that was never uploaded is refused; asking for it is fine.
	var bare string
	db.QueryRow(ctx, `SELECT f.id FROM farmers f JOIN farmer_doc_status ds ON ds.farmer_id=f.id JOIN surveyors s ON s.id=f.surveyor_id
		WHERE f.status='submitted' AND ds.aadhaar='missing' AND s.state='Maharashtra' LIMIT 1`).Scan(&bare)
	if s := meera.do("POST", "/v1/dash/farmers/"+bare+"/review", map[string]any{"decisions": map[string]any{"aadhaar": map[string]string{"decision": "approved"}}}, nil); s != 400 {
		t.Fatalf("approved a missing document: %d", s)
	}
	if s := meera.do("POST", "/v1/dash/farmers/"+bare+"/review", map[string]any{"decisions": map[string]any{"aadhaar": map[string]string{"decision": "pending", "message": "Please upload the Aadhaar photos."}}}, nil); s != 200 {
		t.Fatalf("asking for a missing document: %d", s)
	}
}

func TestDashIssueLogins(t *testing.T) {
	base, db := dashServer(t)
	ctx := context.Background()
	admin := dashLogin(t, base, "admin", testAdminPassword)
	meera := dashLogin(t, base, "meera.mh", DemoPassword)
	green := dashLogin(t, base, "greenearth.hingoli", DemoPassword)
	var narmada, greenID string
	db.QueryRow(ctx, `SELECT id FROM users WHERE username='narmada.seva'`).Scan(&narmada)
	db.QueryRow(ctx, `SELECT id FROM users WHERE username='greenearth.hingoli'`).Scan(&greenID)

	issue := func(c *dashClient, in map[string]any) (int, map[string]any) {
		var out map[string]any
		s := c.do("POST", "/v1/dash/users", in, &out)
		return s, out
	}
	// Only roles below yours.
	for _, x := range []struct {
		c    *dashClient
		role string
	}{{green, "ngo"}, {green, "state_lead"}, {meera, "state_lead"}, {meera, "admin"}, {admin, "admin"}} {
		if s, _ := issue(x.c, map[string]any{"role": x.role, "username": "some.one", "fullName": "Some One", "state": "Goa"}); s != 403 {
			t.Fatalf("issuing %s: %d", x.role, s)
		}
	}
	// Field checks.
	for name, in := range map[string]map[string]any{
		"bad username":     {"role": "ngo", "username": "Bad Name", "fullName": "X Trust", "state": "Goa"},
		"short name":       {"role": "ngo", "username": "x.trust", "fullName": "X", "state": "Goa"},
		"no state":         {"role": "state_lead", "username": "lead.x", "fullName": "Lead X"},
		"unknown state":    {"role": "state_lead", "username": "lead.x", "fullName": "Lead X", "state": "Atlantis"},
		"bad mobile":       {"role": "ngo", "username": "x.trust", "fullName": "X Trust", "state": "Goa", "mobile": "12345"},
		"surveyor no ngo":  {"role": "surveyor", "username": "sv.x", "fullName": "Sv X", "mobile": "9811111111", "district": "Hingoli", "blocks": []string{"Hingoli"}},
		"surveyor no mob":  {"role": "surveyor", "username": "sv.x", "fullName": "Sv X", "ngoId": greenID, "district": "Hingoli", "blocks": []string{"Hingoli"}},
		"surveyor no blks": {"role": "surveyor", "username": "sv.x", "fullName": "Sv X", "ngoId": greenID, "mobile": "9811111111", "district": "Hingoli", "blocks": []string{" ", ""}},
	} {
		if s, out := issue(admin, in); s != 400 || out["field"] == nil {
			t.Fatalf("%s: %d %v", name, s, out)
		}
	}
	// Taken usernames, whatever the case.
	if s, out := issue(admin, map[string]any{"role": "ngo", "username": "MEERA.MH", "fullName": "Copy", "state": "Goa"}); s != 400 && s != 409 {
		t.Fatalf("duplicate username: %d %v", s, out)
	}
	if s, _ := issue(admin, map[string]any{"role": "ngo", "username": "meera.mh", "fullName": "Copy Trust", "state": "Goa"}); s != 409 {
		t.Fatalf("duplicate username: %d", s)
	}
	// A state lead's NGO is always in the state lead's state.
	s, out := issue(meera, map[string]any{"role": "ngo", "username": "new.ngo", "fullName": "New NGO", "state": "Goa"})
	if s != 201 || out["state"] != "Maharashtra" || out["tempPassword"] == nil {
		t.Fatalf("state lead NGO: %d %v", s, out)
	}
	temp := out["tempPassword"].(string)
	// ...and can't put a surveyor under another state's NGO.
	if s, out := issue(meera, map[string]any{"role": "surveyor", "username": "sv.mp", "fullName": "Sv Mp", "ngoId": narmada, "mobile": "9811111112", "district": "Sehore", "blocks": []string{"Sehore"}}); s != 400 || out["field"] != "ngoId" {
		t.Fatalf("surveyor under other state's NGO: %d %v", s, out)
	}
	// An NGO's surveyor goes under that NGO whatever ngoId says.
	s, out = issue(green, map[string]any{"role": "surveyor", "username": "sv.green", "fullName": "Ganesh Field", "ngoId": narmada, "mobile": "+91 98111 11113", "district": "Hingoli", "blocks": []string{"Sengaon", "sengaon", "Hingoli"}})
	if s != 201 || out["signIn"] != "otp" || out["tempPassword"] != nil {
		t.Fatalf("NGO surveyor: %d %v", s, out)
	}
	var ngoOf, state string
	var blocks int
	db.QueryRow(ctx, `SELECT n.username, s.state, (SELECT count(*) FROM surveyor_blocks b WHERE b.surveyor_id=s.id) FROM surveyors s JOIN users n ON n.id=s.ngo_user_id WHERE s.phone='9811111113'`).Scan(&ngoOf, &state, &blocks)
	if ngoOf != "greenearth.hingoli" || state != "Maharashtra" || blocks != 2 {
		t.Fatalf("new surveyor under %s in %s with %d blocks", ngoOf, state, blocks)
	}
	if s, out := issue(green, map[string]any{"role": "surveyor", "username": "sv.green2", "fullName": "Same Phone", "mobile": "9811111113", "district": "Hingoli", "blocks": []string{"Hingoli"}}); s != 409 || out["field"] != "mobile" {
		t.Fatalf("duplicate surveyor mobile: %d %v", s, out)
	}
	// The new surveyor can sign in on the phone right away.
	pc := &client{t: t, base: base}
	var otp struct{ DevCode string }
	if s := pc.do("POST", "/v1/auth/otp/request", map[string]string{"phone": "9811111113"}, &otp); s != 200 {
		t.Fatalf("new surveyor OTP: %d", s)
	}
	var v struct{ Token string }
	pc.do("POST", "/v1/auth/otp/verify", map[string]string{"phone": "9811111113", "code": otp.DevCode}, &v)
	pc.token = v.Token
	if s := pc.do("GET", "/v1/me", nil, nil); s != 200 {
		t.Fatalf("new surveyor /v1/me: %d", s)
	}
	// The new NGO must set its own password before anything else.
	ngo := dashLogin(t, base, "new.ngo", temp)
	var e apiError
	if s := ngo.do("GET", "/v1/dash/farmers", nil, &e); s != 403 || e.Error != "password_change_required" {
		t.Fatalf("before password change: %d %+v", s, e)
	}
	if s := ngo.do("POST", "/v1/dash/password", map[string]string{"current": temp, "new": "new.ngo"}, nil); s != 400 {
		t.Fatalf("password same as username accepted: %d", s)
	}
	if s := ngo.do("POST", "/v1/dash/password", map[string]string{"current": "wrong", "new": "a-better-pass"}, nil); s != 400 {
		t.Fatalf("wrong current password accepted: %d", s)
	}
	if s := ngo.do("POST", "/v1/dash/password", map[string]string{"current": temp, "new": "a-better-pass"}, nil); s != 200 {
		t.Fatalf("password change: %d", s)
	}
	if s := ngo.do("GET", "/v1/dash/farmers", nil, nil); s != 200 {
		t.Fatalf("after password change: %d", s)
	}
	// Turning a surveyor off signs the phone out and blocks new codes; on again works.
	var svUser string
	db.QueryRow(ctx, `SELECT u.id FROM users u JOIN surveyors s ON s.id=u.surveyor_id WHERE s.phone='9811111113'`).Scan(&svUser)
	if s := meera.do("POST", "/v1/dash/users/"+svUser+"/active", map[string]any{"active": false}, nil); s != 200 {
		t.Fatalf("turn off: %d", s)
	}
	if s := pc.do("GET", "/v1/me", nil, nil); s != 401 {
		t.Fatalf("phone still signed in: %d", s)
	}
	if s := pc.do("POST", "/v1/auth/otp/request", map[string]string{"phone": "9811111113"}, nil); s != 404 {
		t.Fatalf("OTP for a turned-off surveyor: %d", s)
	}
	meera.do("POST", "/v1/dash/users/"+svUser+"/active", map[string]any{"active": true}, nil)
	if s := pc.do("POST", "/v1/auth/otp/request", map[string]string{"phone": "9811111113"}, nil); s != 200 {
		t.Fatalf("OTP after turning back on: %d", s)
	}
	// Managing only works downwards and inside your area.
	var adminID, arvind string
	db.QueryRow(ctx, `SELECT id FROM users WHERE username='admin'`).Scan(&adminID)
	db.QueryRow(ctx, `SELECT id FROM users WHERE username='arvind.mp'`).Scan(&arvind)
	for _, id := range []string{adminID, arvind, narmada} {
		if s := meera.do("POST", "/v1/dash/users/"+id+"/reset-password", nil, nil); s != 404 {
			t.Fatalf("state lead reset %s: %d", id, s)
		}
	}
	if s := green.do("POST", "/v1/dash/users/"+svUser+"/reset-password", nil, nil); s != 400 {
		t.Fatalf("password reset for an OTP surveyor: %d", s)
	}
	var rp struct{ TempPassword string }
	if s := admin.do("POST", "/v1/dash/users/"+arvind+"/reset-password", nil, &rp); s != 200 || rp.TempPassword == "" {
		t.Fatalf("admin reset: %d", s)
	}
	dashLogin(t, base, "arvind.mp", rp.TempPassword)
}
