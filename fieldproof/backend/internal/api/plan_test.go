package api

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"mime/multipart"
	"net/http"
	"strings"
	"testing"
	"time"
)

func TestSaplingMath(t *testing.T) {
	max, timber, fruit := SaplingsFor(1.5, 40)
	if max != 1517 || timber != 607 || fruit != 910 || AmountDue(timber, fruit) != 12135 {
		t.Fatalf("1.5 ac: %d %d %d %d", max, timber, fruit, AmountDue(timber, fruit))
	}
	if m, _, _ := SaplingsFor(0, 40); m != 0 {
		t.Fatal("zero acres")
	}
	if _, tm, fr := SaplingsFor(1, 100); fr != 0 || tm != 1011 {
		t.Fatalf("all timber: %d %d", tm, fr)
	}
	for in, want := range map[int]string{0: "0", 999: "999", 1000: "1,000", 12135: "12,135", 123456: "1,23,456", 12345678: "1,23,45,678", -4500: "-4,500"} {
		if got := num(in); got != want {
			t.Fatalf("num(%d) = %s", in, got)
		}
	}
}

var uploadSeq int

func phoneUpload(t *testing.T, c *client, fid, kind string, lat, lng float64) int {
	t.Helper()
	uploadSeq++
	var body bytes.Buffer
	mw := multipart.NewWriter(&body)
	mw.WriteField("id", fmt.Sprintf("%08x-0000-4000-8000-%012x", uploadSeq, uploadSeq))
	mw.WriteField("kind", kind)
	if lat != 0 {
		mw.WriteField("lat", fmt.Sprint(lat))
		mw.WriteField("lng", fmt.Sprint(lng))
	}
	fw, _ := mw.CreateFormFile("file", "p.jpg")
	fw.Write(sampleImage(kind))
	mw.Close()
	req, _ := http.NewRequest("POST", c.base+"/v1/farmers/"+fid+"/files", &body)
	req.Header.Set("Content-Type", mw.FormDataContentType())
	req.Header.Set("Authorization", "Bearer "+c.token)
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	resp.Body.Close()
	return resp.StatusCode
}

func TestPaymentAndPlantation(t *testing.T) {
	base, db := dashServer(t)
	ctx := context.Background()
	meera := dashLogin(t, base, "meera.mh", DemoPassword)
	arvind := dashLogin(t, base, "arvind.mp", DemoPassword) // other state: can't see Tanuj's farmers
	phone := login(t, base)

	var fid, notReady string
	if err := db.QueryRow(ctx, `SELECT ps.farmer_id FROM farmer_plan_status ps JOIN farmers f ON f.id=ps.farmer_id JOIN surveyors s ON s.id=f.surveyor_id
		WHERE s.phone='9876543210' AND ps.onboarding_ok ORDER BY f.number LIMIT 1`).Scan(&fid); err != nil {
		t.Fatal(err)
	}
	db.QueryRow(ctx, `SELECT ps.farmer_id FROM farmer_plan_status ps JOIN farmers f ON f.id=ps.farmer_id JOIN surveyors s ON s.id=f.surveyor_id
		WHERE s.phone='9876543210' AND NOT ps.onboarding_ok AND f.status='submitted' LIMIT 1`).Scan(&notReady)

	var st PlanState
	get := func() PlanState {
		t.Helper()
		var x PlanState
		if s := phone.do("GET", "/v1/farmers/"+fid+"/plantation", nil, &x); s != 200 {
			t.Fatalf("plantation state: %d", s)
		}
		return x
	}
	stage := func(want string) {
		t.Helper()
		if st = get(); st.Plantation != want {
			t.Fatalf("stage %s, want %s (payment %s)", st.Plantation, want, st.Payment)
		}
	}
	task := func(kind string) *ActionItem {
		t.Helper()
		var items []ActionItem
		phone.do("GET", "/v1/action-items", nil, &items)
		var found *ActionItem
		for i := range items {
			if items[i].FarmerID == fid && strings.HasPrefix(items[i].ID, "plan-") {
				if found != nil {
					t.Fatalf("two plan tasks for one farmer: %+v / %+v", *found, items[i])
				}
				found = &items[i]
			}
		}
		if kind == "" {
			if found != nil {
				t.Fatalf("unexpected task %+v", *found)
			}
			return nil
		}
		if found == nil || found.Kind != kind || found.ID != "plan-"+fid+"-"+kind {
			t.Fatalf("task %+v, want %s", found, kind)
		}
		return found
	}

	st = get()
	if st.Payment != "none" || st.Plantation != "locked" || st.Plan.MaxSaplings <= 0 || st.Plan.AmountDue != AmountDue(st.Plan.Timber, st.Plan.Fruit) {
		t.Fatalf("start %+v", st)
	}
	task("collect_payment")
	if s := phone.do("GET", "/v1/farmers/00000000-0000-4000-8000-000000000000/plantation", nil, nil); s != 404 {
		t.Fatalf("unknown farmer: %d", s)
	}

	// Payment: refused until onboarding is approved, and until the screenshot is there.
	pay := map[string]any{"timber": 100, "fruit": 150, "amountPaid": 1500, "method": "upi", "reference": "upi 4127 8890 12", "paidOn": time.Now().Format("2006-01-02")}
	if notReady != "" {
		if s := phone.do("PUT", "/v1/farmers/"+notReady+"/payment", pay, nil); s != 409 {
			t.Fatalf("payment before onboarding approved: %d", s)
		}
	}
	walk := func(purpose string, scale float64) int {
		t.Helper()
		var raw []byte
		db.QueryRow(ctx, `SELECT points FROM boundaries WHERE farmer_id=$1 AND purpose='onboarding' AND status='active' LIMIT 1`, fid).Scan(&raw)
		var pts []PointIn
		json.Unmarshal(raw, &pts)
		var clat, clng float64
		for _, p := range pts {
			clat += p.Lat / float64(len(pts))
			clng += p.Lng / float64(len(pts))
		}
		var dense []PointIn // a real walk has a fix every few metres
		for i, a := range pts {
			b := pts[(i+1)%len(pts)]
			for k := 0; k < 4; k++ {
				f := float64(k) / 4
				dense = append(dense, PointIn{Lat: clat + (a.Lat+(b.Lat-a.Lat)*f-clat)*scale, Lng: clng + (a.Lng+(b.Lng-a.Lng)*f-clng)*scale, Acc: 4, T: int64(len(dense)) * 5000})
			}
		}
		pts = dense
		uploadSeq++
		bid := fmt.Sprintf("%08x-1111-4000-8000-%012x", uploadSeq, uploadSeq)
		var out string
		s := phone.do("PUT", "/v1/farmers/"+fid+"/boundaries/"+bid, map[string]any{"points": pts, "purpose": purpose, "accuracyM": 4}, &out)
		if s == 422 {
			t.Logf("walk %s: %s (%d points)", purpose, out, len(pts))
		}
		return s
	}
	if s := walk("pre_plant", 0.97); s != 409 {
		t.Fatalf("walk before payment approved: %d", s)
	}
	if s := walk("harvest", 0.97); s != 400 {
		t.Fatalf("unknown purpose: %d", s)
	}
	expectField := func(body map[string]any, field string) {
		t.Helper()
		var out struct{ Field string }
		if s := phone.do("PUT", "/v1/farmers/"+fid+"/payment", body, &out); s != 400 || out.Field != field {
			t.Fatalf("want 400 on %s, got %d %s", field, s, out.Field)
		}
	}
	with := func(k string, v any) map[string]any {
		m := map[string]any{}
		for a, b := range pay {
			m[a] = b
		}
		m[k] = v
		return m
	}
	expectField(pay, "proof")
	center := func() (float64, float64) {
		var lat, lng float64
		db.QueryRow(ctx, `SELECT ST_Y(ST_Centroid(geom::geometry)), ST_X(ST_Centroid(geom::geometry)) FROM boundaries WHERE farmer_id=$1 AND purpose='onboarding' AND status='active' LIMIT 1`, fid).Scan(&lat, &lng)
		return lat, lng
	}
	lat, lng := center()
	if s := phoneUpload(t, phone, fid, "payment_proof", 0, 0); s != 201 {
		t.Fatalf("proof upload: %d", s)
	}
	zero := with("timber", 0)
	zero["fruit"] = 0
	expectField(zero, "saplings")
	expectField(with("timber", st.Plan.MaxSaplings), "saplings")
	expectField(with("timber", -1), "saplings")
	expectField(with("method", "cash"), "method")
	expectField(with("amountPaid", 0), "amountPaid")
	expectField(with("reference", "ab"), "reference")
	expectField(with("reference", "<script>"), "reference")
	expectField(with("paidOn", time.Now().AddDate(0, 0, 3).Format("2006-01-02")), "paidOn")
	expectField(with("paidOn", "yesterday"), "paidOn")

	if s := phone.do("PUT", "/v1/farmers/"+fid+"/payment", pay, &st); s != 200 || st.Payment != "waiting" || st.Pay.AmountDue != 2000 || st.Pay.Reference != "UPI4127889012" {
		t.Fatalf("payment: %d %+v", s, st.Pay)
	}
	task("payment_review")

	// Dashboard: checks show the ₹500 short; other states can't see it.
	var d DashFarmerDetail
	meera.do("GET", "/v1/dash/farmers/"+fid, nil, &d)
	if d.Plan == nil || d.Plan.Pay == nil || d.Plan.Pay.Checks[0].OK || !strings.Contains(d.Plan.Pay.Checks[0].Text, "₹500 short") || len(d.Plan.Pay.Proofs) != 1 {
		t.Fatalf("payment checks %+v", d.Plan)
	}
	if s := arvind.do("POST", "/v1/dash/farmers/"+fid+"/payment-review", map[string]string{"decision": "approved"}, nil); s != 404 {
		t.Fatalf("other state reviewed: %d", s)
	}
	if s := meera.do("POST", "/v1/dash/farmers/"+fid+"/plantation-review", map[string]string{"decision": "approved"}, nil); s != 409 {
		t.Fatalf("plantation reviewed before planting: %d", s)
	}
	for _, bad := range []map[string]string{{"decision": "rejected"}, {"decision": "pending", "message": "  "}, {"decision": "maybe"}, {"decision": "pending", "message": strings.Repeat("x", 1001)}} {
		if s := meera.do("POST", "/v1/dash/farmers/"+fid+"/payment-review", bad, nil); s != 400 {
			t.Fatalf("bad review %v: %d", bad, s)
		}
	}
	msg := "₹500 is short. Collect the rest and send a new screenshot."
	if s := meera.do("POST", "/v1/dash/farmers/"+fid+"/payment-review", map[string]string{"decision": "rejected", "message": msg}, nil); s != 200 {
		t.Fatalf("reject: %d", s)
	}
	fix := task("fix_payment")
	if !fix.Attention || fix.Note != msg || fix.ReviewerName != "Meera Kulkarni, State lead" || fix.Decision != "rejected" {
		t.Fatalf("fix task %+v", fix)
	}
	if s := phone.do("PUT", "/v1/farmers/"+fid+"/payment", pay, nil); s != 400 {
		t.Fatalf("resent without a new screenshot: %d", s)
	}
	phoneUpload(t, phone, fid, "payment_proof", 0, 0)
	if s := phone.do("PUT", "/v1/farmers/"+fid+"/payment", with("amountPaid", 2000), &st); s != 200 || st.Payment != "waiting" || len(st.Pay.Proofs) != 2 {
		t.Fatalf("resend: %d %+v", s, st.Pay)
	}
	meera.do("GET", "/v1/dash/farmers/"+fid, nil, &d)
	for _, c := range d.Plan.Pay.Checks {
		if !c.OK {
			t.Fatalf("check failed after the full payment: %s", c.Text)
		}
	}
	if s := meera.do("POST", "/v1/dash/farmers/"+fid+"/payment-review", map[string]string{"decision": "approved", "message": "dropped"}, nil); s != 200 {
		t.Fatalf("approve: %d", s)
	}
	if s := meera.do("POST", "/v1/dash/farmers/"+fid+"/payment-review", map[string]string{"decision": "rejected", "message": "changed my mind"}, nil); s != 409 {
		t.Fatalf("changed an approved payment: %d", s)
	}
	if s := phone.do("PUT", "/v1/farmers/"+fid+"/payment", pay, nil); s != 409 {
		t.Fatalf("resent an approved payment: %d", s)
	}
	stage("pre_walk")
	if st.Pay.Message != "" {
		t.Fatal("approval kept a message")
	}
	task("walk_pre_plant")

	// Boundary before planting, then the tractor arrives.
	delivery := func(tc, fc int, ok bool) (int, map[string]any) {
		var out map[string]any
		s := phone.do("POST", "/v1/farmers/"+fid+"/delivery", map[string]any{"timberCounted": tc, "fruitCounted": fc, "healthy": true, "rightSpecies": true, "satisfied": ok}, &out)
		return s, out
	}
	if s, _ := delivery(100, 150, true); s != 409 {
		t.Fatalf("delivery before the walk: %d", s)
	}
	if s := walk("post_plant", 0.9); s != 409 {
		t.Fatalf("post walk too early: %d", s)
	}
	if s := walk("pre_plant", 0.97); s != 200 {
		t.Fatalf("pre walk: %d", s)
	}
	stage("delivery")
	task("sapling_delivery")
	// The onboarding boundary is untouched by the new walk.
	var onboarding int
	db.QueryRow(ctx, `SELECT count(*) FROM boundaries WHERE farmer_id=$1 AND purpose='onboarding' AND status='active'`, fid).Scan(&onboarding)
	if onboarding != 1 {
		t.Fatalf("%d onboarding walks after the pre-plant walk", onboarding)
	}
	if s, out := delivery(100, 150, true); s != 400 || out["field"] != "photo" {
		t.Fatalf("delivery without a photo: %d %v", s, out)
	}
	phoneUpload(t, phone, fid, "sapling_delivery", lat, lng)
	if s := phone.do("POST", "/v1/farmers/"+fid+"/delivery", map[string]any{"timberCounted": 100}, nil); s != 400 {
		t.Fatalf("delivery without the fruit count: %d", s)
	}
	if s, out := delivery(60, 150, true); s != 200 || out["matched"] != false || fmt.Sprint(out["problems"]) != "[40 timber short]" {
		t.Fatalf("short delivery: %d %v", s, out)
	}
	stage("short")
	if it := task("sapling_delivery"); it.Note != "40 timber short" {
		t.Fatalf("short note %q", it.Note)
	}
	if s := phone.do("POST", "/v1/farmers/"+fid+"/planting-otp/request", nil, nil); s != 409 {
		t.Fatalf("OTP while short: %d", s)
	}
	if s, _ := delivery(100, 150, false); s != 200 {
		t.Fatalf("not satisfied: %d", s)
	}
	stage("short")
	if s, out := delivery(100, 150, true); s != 200 || out["matched"] != true {
		t.Fatalf("full delivery: %d %v", s, out)
	}
	stage("otp")
	if s, _ := delivery(100, 150, true); s != 409 {
		t.Fatalf("counted twice: %d", s)
	}

	// Farmer's OTP.
	planted := map[string]any{"timberPlanted": 100, "fruitPlanted": 150}
	if s := phone.do("POST", "/v1/farmers/"+fid+"/planted", planted, nil); s != 409 {
		t.Fatalf("planted before the OTP: %d", s)
	}
	if s := phone.do("POST", "/v1/farmers/"+fid+"/planting-otp/verify", map[string]any{"code": "1234"}, nil); s != 400 {
		t.Fatalf("verify before sending: %d", s)
	}
	var otp struct {
		DevCode, SentTo string
	}
	if s := phone.do("POST", "/v1/farmers/"+fid+"/planting-otp/request", nil, &otp); s != 200 || len(otp.DevCode) != 4 || otp.SentTo == "" {
		t.Fatalf("OTP request: %d %+v", s, otp)
	}
	if s := phone.do("POST", "/v1/farmers/"+fid+"/planting-otp/request", nil, nil); s != 429 {
		t.Fatalf("resend too soon: %d", s)
	}
	wrong := "0000"
	if otp.DevCode == wrong {
		wrong = "1111"
	}
	if s := phone.do("POST", "/v1/farmers/"+fid+"/planting-otp/verify", map[string]any{"code": wrong}, nil); s != 400 {
		t.Fatalf("wrong code: %d", s)
	}
	if s := phone.do("POST", "/v1/farmers/"+fid+"/planting-otp/verify", map[string]any{"code": otp.DevCode, "lat": lat, "lng": lng}, nil); s != 200 {
		t.Fatalf("verify: %d", s)
	}
	stage("planting")
	task("planting")

	// Planting.
	if s := phone.do("POST", "/v1/farmers/"+fid+"/planted", map[string]any{"timberPlanted": 90, "fruitPlanted": 150}, nil); s != 400 {
		t.Fatalf("planted fewer than received: %d", s)
	}
	if s := phone.do("POST", "/v1/farmers/"+fid+"/planted", planted, nil); s != 400 {
		t.Fatalf("planted without photos: %d", s)
	}
	phoneUpload(t, phone, fid, "plant_pits", lat, lng)
	phoneUpload(t, phone, fid, "plant_planted", lat, lng)
	if s := phone.do("POST", "/v1/farmers/"+fid+"/planted", planted, nil); s != 200 {
		t.Fatalf("planted: %d", s)
	}
	stage("post_walk")
	task("walk_post_plant")
	if s := walk("post_plant", 0.94); s != 200 {
		t.Fatalf("post walk: %d", s)
	}
	stage("to_review")
	task("plantation_review")
	if s := walk("pre_plant", 0.97); s != 409 {
		t.Fatalf("pre walk after delivery: %d", s)
	}

	// Review.
	meera.do("GET", "/v1/dash/farmers/"+fid, nil, &d)
	if d.Plan.Plant == nil || len(d.Plan.Plant.Checks) != 6 || d.Plan.Plant.Rings["prePlant"] == nil || d.Plan.Plant.Rings["postPlant"] == nil || len(d.Plan.Plant.Photos) != 3 {
		t.Fatalf("plantation detail %+v", d.Plan.Plant)
	}
	for _, c := range d.Plan.Plant.Checks {
		if !c.OK {
			t.Fatalf("plantation check failed: %s", c.Text)
		}
	}
	if s := meera.do("POST", "/v1/dash/farmers/"+fid+"/plantation-review", map[string]string{"decision": "pending"}, nil); s != 400 {
		t.Fatalf("pending without message: %d", s)
	}
	pmsg := "Take a closer photo of the planted saplings."
	if s := meera.do("POST", "/v1/dash/farmers/"+fid+"/plantation-review", map[string]string{"decision": "pending", "message": pmsg}, nil); s != 200 {
		t.Fatalf("plantation pending: %d", s)
	}
	stage("pending")
	if it := task("fix_plantation"); !it.Attention || it.Note != pmsg {
		t.Fatalf("plantation fix %+v", it)
	}
	phoneUpload(t, phone, fid, "plant_planted", lat, lng)
	stage("to_review")
	if s := meera.do("POST", "/v1/dash/farmers/"+fid+"/plantation-review", map[string]string{"decision": "approved"}, nil); s != 200 {
		t.Fatalf("plantation approve: %d", s)
	}
	stage("done")
	task("")
	if s := walk("post_plant", 0.94); s != 409 {
		t.Fatalf("post walk after approval: %d", s)
	}
	if s := meera.do("POST", "/v1/dash/farmers/"+fid+"/plantation-review", map[string]string{"decision": "pending", "message": "again"}, nil); s != 409 {
		t.Fatalf("reviewed an approved plantation: %d", s)
	}
	for _, step := range st.Steps {
		if step.State != "done" {
			t.Fatalf("step %s is %s", step.Key, step.State)
		}
	}
	var sawReject, sawResubmit bool
	for _, e := range st.Events {
		sawReject = sawReject || (e.Event == "payment_rejected" && e.By == "Meera Kulkarni, State lead" && strings.Contains(e.Detail, msg))
		sawResubmit = sawResubmit || e.Event == "plant_resubmitted"
	}
	if !sawReject || !sawResubmit {
		t.Fatalf("history %+v", st.Events)
	}

	// Farmers list: status columns and filters.
	if p := meera.farmers("payment=approved&plantation=done"); p.Total != 1 || p.Items[0].ID != fid || p.Items[0].Payment != "approved" || p.Items[0].Plantation != "done" {
		t.Fatalf("done filter %+v", p)
	}
	if p := meera.farmers("plantation=to_review"); p.Total != 0 {
		t.Fatalf("to_review filter %d", p.Total)
	}
	if p := meera.farmers("payment=none"); p.Total == 0 {
		t.Fatal("no farmer left to pay")
	}
	for _, q := range []string{"payment=maybe", "plantation=planting"} {
		var e map[string]any
		if s := meera.do("GET", "/v1/dash/farmers?"+q, nil, &e); s != 400 {
			t.Fatalf("%s: %d", q, s)
		}
	}
	if p := meera.farmers("payment=locked"); p.Total == 0 {
		t.Fatal("no locked payments")
	}
	for _, it := range meera.farmers("payment=locked&size=100").Items {
		if it.Docs["aadhaar"] == "approved" && it.Docs["land_record"] == "approved" && it.Docs["water_source"] == "approved" && it.Docs["boundary"] == "approved" {
			t.Fatalf("approved farmer shown as locked: %+v", it)
		}
	}
}

func TestSeedPlantation(t *testing.T) {
	base, db := dashServer(t)
	ctx := context.Background()
	if err := SeedPlantation(ctx, db, t.TempDir()); err != nil {
		t.Fatal(err)
	}
	if err := SeedPlantation(ctx, db, t.TempDir()); err != nil { // second run does nothing
		t.Fatal(err)
	}
	admin := dashLogin(t, base, "admin", testAdminPassword)
	seen := map[string]bool{}
	var items []DashFarmerRow
	for _, p := range []string{"none", "waiting", "approved", "sent_back"} {
		items = append(items, admin.farmers("size=100&payment="+p).Items...)
	}
	for _, it := range items {
		var d DashFarmerDetail
		if s := admin.do("GET", "/v1/dash/farmers/"+it.ID, nil, &d); s != 200 || d.Plan == nil || d.Plan.Plantation != it.Plantation {
			t.Fatalf("detail %s: %d", it.ID, s)
		}
		seen[it.Payment+"/"+it.Plantation] = true
	}
	for _, want := range []string{"none/locked", "waiting/locked", "rejected/locked", "approved/pre_walk", "approved/short", "approved/to_review", "approved/done"} {
		if !seen[want] {
			t.Fatalf("no demo farmer at %s (have %v)", want, seen)
		}
	}
	var counts map[string]int
	admin.do("GET", "/v1/dash/counts", nil, &counts)
	if counts["payments"] == 0 || counts["plantations"] == 0 {
		t.Fatalf("counts %v", counts)
	}
}
