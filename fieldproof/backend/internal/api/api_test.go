package api

// Integration tests against a real PostGIS. Run with TEST_DATABASE_URL pointing
// at a server where the user may create databases, e.g.
//   TEST_DATABASE_URL=postgres://fieldproof:fieldproof@db:5432/postgres?sslmode=disable go test ./...

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"math"
	"math/rand"
	"mime/multipart"
	"net/http"
	"net/http/httptest"
	"os"
	"strings"
	"testing"

	"fieldproof/backend/internal/geo"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"
)

func testServer(t *testing.T) (*httptest.Server, *pgxpool.Pool) {
	admin := os.Getenv("TEST_DATABASE_URL")
	if admin == "" {
		t.Skip("TEST_DATABASE_URL not set")
	}
	ctx := context.Background()
	c, err := pgx.Connect(ctx, admin)
	if err != nil {
		t.Fatal(err)
	}
	c.Exec(ctx, `DROP DATABASE IF EXISTS fieldproof_test WITH (FORCE)`)
	if _, err := c.Exec(ctx, `CREATE DATABASE fieldproof_test`); err != nil {
		t.Fatal(err)
	}
	c.Close(ctx)
	dsn := strings.Replace(admin, "/postgres?", "/fieldproof_test?", 1)
	db, err := pgxpool.New(ctx, dsn)
	if err != nil {
		t.Fatal(err)
	}
	if err := Migrate(ctx, db); err != nil {
		t.Fatal(err)
	}
	if err := Seed(ctx, db); err != nil {
		t.Fatal(err)
	}
	srv := httptest.NewServer(New(db, Config{DevMode: true, UploadDir: t.TempDir(), AdminUser: "a", AdminPassword: "b"}))
	t.Cleanup(func() { srv.Close(); db.Close() })
	return srv, db
}

type client struct {
	t     *testing.T
	base  string
	token string
}

func (c *client) do(method, path string, body any, out any) int {
	c.t.Helper()
	var rd io.Reader
	if body != nil {
		b, _ := json.Marshal(body)
		rd = bytes.NewReader(b)
	}
	req, _ := http.NewRequest(method, c.base+path, rd)
	req.Header.Set("Content-Type", "application/json")
	if c.token != "" {
		req.Header.Set("Authorization", "Bearer "+c.token)
	}
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		c.t.Fatal(err)
	}
	defer resp.Body.Close()
	data, _ := io.ReadAll(resp.Body)
	if out != nil {
		if s, ok := out.(*string); ok {
			*s = string(data)
		} else if err := json.Unmarshal(data, out); err != nil {
			c.t.Fatalf("%s %s: %d %s", method, path, resp.StatusCode, data)
		}
	}
	return resp.StatusCode
}

func login(t *testing.T, base string) *client {
	c := &client{t: t, base: base}
	var otp struct{ DevCode string }
	if s := c.do("POST", "/v1/auth/otp/request", map[string]string{"phone": "+91 98765 43210"}, &otp); s != 200 || len(otp.DevCode) != 4 {
		t.Fatalf("otp request %d %+v", s, otp)
	}
	wrong := "0000"
	if otp.DevCode == wrong {
		wrong = "1111"
	}
	if s := c.do("POST", "/v1/auth/otp/verify", map[string]string{"phone": "9876543210", "code": wrong}, nil); s != 400 {
		t.Fatalf("wrong code accepted: %d", s)
	}
	var v struct {
		Token    string
		Surveyor Surveyor
	}
	if s := c.do("POST", "/v1/auth/otp/verify", map[string]string{"phone": "9876543210", "code": otp.DevCode}, &v); s != 200 || v.Token == "" {
		t.Fatalf("verify %d", s)
	}
	if v.Surveyor.Name != "Tanuj" || len(v.Surveyor.Blocks) != 2 {
		t.Fatalf("surveyor %+v", v.Surveyor)
	}
	c.token = v.Token
	return c
}

// ring builds lat/lng points from local metre offsets around an origin.
func ring(origin geo.LatLng, xy [][2]float64) []PointIn {
	// Inverse of a local equirectangular projection; exact enough to build
	// test shapes, and the areas compared below are all computed from the
	// resulting lat/lng values, never from these metre offsets.
	const r = 6378137.0
	out := make([]PointIn, len(xy))
	for i, p := range xy {
		lat := origin.Lat + p[1]/r*180/math.Pi
		lng := origin.Lng + p[0]/(r*math.Cos(origin.Lat*math.Pi/180))*180/math.Pi
		out[i] = PointIn{Lat: lat, Lng: lng, Acc: 3, T: int64(1760000000000 + i*1000)}
	}
	return out
}

func densify(xy [][2]float64, step float64) [][2]float64 {
	var out [][2]float64
	for i := range xy {
		a, b := xy[i], xy[(i+1)%len(xy)]
		n := int(math.Ceil(math.Hypot(b[0]-a[0], b[1]-a[1]) / step))
		for k := 0; k < n; k++ {
			f := float64(k) / float64(n)
			out = append(out, [2]float64{a[0] + f*(b[0]-a[0]), a[1] + f*(b[1]-a[1])})
		}
	}
	return out
}

var kurunda = geo.LatLng{Lat: 19.4512, Lng: 77.1874}

func TestEndToEnd(t *testing.T) {
	srv, db := testServer(t)
	ctx := context.Background()
	c := login(t, srv.URL)

	var villages []Village
	c.do("GET", "/v1/villages", nil, &villages)
	vid := 0
	for _, v := range villages {
		if v.Name == "Kurunda" {
			vid = v.ID
		}
	}
	if vid == 0 || len(villages) != 16 {
		t.Fatalf("villages %d", len(villages))
	}
	var nums struct{ Numbers []int }
	c.do("POST", "/v1/farmer-numbers", map[string]int{"count": 5}, &nums)
	if len(nums.Numbers) != 5 || nums.Numbers[0] != 4418 {
		t.Fatalf("numbers %+v", nums)
	}

	var items []ActionItem
	c.do("GET", "/v1/action-items", nil, &items)
	if len(items) != 4 || items[0].Kind != "walk_boundary" || items[0].FarmerCode != "4417sun" {
		t.Fatalf("action items %+v", items)
	}

	fid := "6f1d8d7e-8a51-4b8e-9d3a-5b1a2c3d4e5f"
	landID := "0a1b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d"
	farmer := FarmerIn{
		Number: nums.Numbers[0], FullName: "  Sunita  Baburao Pawar ", Mobile: "9822041399", WhatsappSame: true,
		VillageID: vid, TreeInterest: true, AadhaarNameMatch: true, AadhaarConsent: true,
		Lands: []LandIn{{ID: landID, SurveyNo: "214/2B", AreaHa: 0.61, RegisterAcres: 1.5, OwnerType: "farmer", RecordsConsent: true}},
	}
	// 0.61 ha = 1.507 ac, so 1.75 ac must be refused.
	bad := farmer
	bad.Lands = []LandIn{{ID: landID, SurveyNo: "214/2B", AreaHa: 0.61, RegisterAcres: 1.75, OwnerType: "farmer"}}
	if s := c.do("PUT", "/v1/farmers/"+fid, bad, nil); s != 400 {
		t.Fatalf("over-cap land accepted: %d", s)
	}
	var f FarmerOut
	if s := c.do("PUT", "/v1/farmers/"+fid, farmer, &f); s != 201 {
		t.Fatalf("create farmer %d", s)
	}
	if f.Code != fmt.Sprintf("%dsun", nums.Numbers[0]) || f.FullName != "Sunita Baburao Pawar" || f.Whatsapp != "9822041399" {
		t.Fatalf("farmer %+v", f)
	}
	if s := c.do("PUT", "/v1/farmers/"+fid, farmer, &f); s != 200 {
		t.Fatalf("idempotent re-put %d", s)
	}

	// Photo upload is idempotent by id.
	upload := func() int {
		var buf bytes.Buffer
		mw := multipart.NewWriter(&buf)
		mw.WriteField("id", "11111111-2222-4333-8444-555555555555")
		mw.WriteField("kind", "aadhaar_front")
		mw.WriteField("lat", "19.45")
		mw.WriteField("capturedAt", "2026-10-18T10:00:00Z")
		fw, _ := mw.CreateFormFile("file", "a.jpg")
		fw.Write([]byte("\xff\xd8\xff fake jpeg"))
		mw.Close()
		req, _ := http.NewRequest("POST", srv.URL+"/v1/farmers/"+fid+"/files", &buf)
		req.Header.Set("Content-Type", mw.FormDataContentType())
		req.Header.Set("Authorization", "Bearer "+c.token)
		resp, err := http.DefaultClient.Do(req)
		if err != nil {
			t.Fatal(err)
		}
		resp.Body.Close()
		return resp.StatusCode
	}
	if s := upload(); s != 201 {
		t.Fatalf("upload %d", s)
	}
	if s := upload(); s != 200 {
		t.Fatalf("re-upload %d", s)
	}

	// A walked ~1.5 acre field: irregular hexagon, points every 2 m, with
	// ±1.5 m jitter like a real walk.
	shape := [][2]float64{{0, 0}, {82, -6}, {96, 40}, {78, 84}, {12, 80}, {-8, 38}}
	rng := rand.New(rand.NewSource(1))
	walk := densify(shape, 2)
	for i := range walk {
		walk[i][0] += rng.NormFloat64() * 0.5
		walk[i][1] += rng.NormFloat64() * 0.5
	}
	pts := ring(kurunda, walk)
	ll := ringOf(pts)
	clientArea := geo.Area(ll)

	var chk BoundaryCheck
	c.do("POST", "/v1/boundaries/check", BoundaryIn{FarmerID: fid, Points: pts}, &chk)
	if !chk.Valid || !chk.OverlapOK {
		t.Fatalf("check %+v", chk)
	}
	// Our tangent-plane area must agree with PostGIS's ellipsoidal area.
	if rel := math.Abs(chk.AreaM2-clientArea) / chk.AreaM2; rel > 1e-5 {
		t.Fatalf("area mismatch: postgis %.3f m², geo %.3f m² (rel %.2e)", chk.AreaM2, clientArea, rel)
	}
	t.Logf("walked area %.2f m² = %.4f ac, postgis %.2f m², %d points", clientArea, clientArea/geo.SqMetersPerAcre, chk.AreaM2, len(pts))

	bid := "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee"
	in := BoundaryIn{LandID: &landID, Points: pts, ClientAreaM2: clientArea, WalkedM: geo.Perimeter(ll), AccuracyM: 3}
	var put struct {
		ID    string
		Check BoundaryCheck
	}
	if s := c.do("PUT", "/v1/farmers/"+fid+"/boundaries/"+bid, in, &put); s != 200 {
		t.Fatalf("put boundary %d", s)
	}

	// KML: well-formed, closed CCW ring, vertices exact, and PostGIS reads it
	// back to the same area.
	var kml string
	if s := c.do("GET", "/v1/farmers/"+fid+"/kml", nil, &kml); s != 200 {
		t.Fatalf("kml %d", s)
	}
	rings, err := geo.ParseKMLRings(kml)
	if err != nil || len(rings) != 1 {
		t.Fatalf("kml parse %v", err)
	}
	kr := rings[0]
	if kr[0] != kr[len(kr)-1] || len(kr) != len(pts)+1 || geo.SignedArea(kr) <= 0 {
		t.Fatalf("kml ring closed=%v n=%d ccw=%v", kr[0] == kr[len(kr)-1], len(kr), geo.SignedArea(kr) > 0)
	}
	coords := kml[strings.Index(kml, "<coordinates>")+13 : strings.Index(kml, "</coordinates>")]
	var kmlArea float64
	var kmlValid bool
	err = db.QueryRow(ctx, `SELECT ST_Area(g::geography, true), ST_IsValid(g) FROM (SELECT ST_SetSRID(ST_GeomFromKML($1),4326) g) x`,
		"<Polygon><outerBoundaryIs><LinearRing><coordinates>"+coords+"</coordinates></LinearRing></outerBoundaryIs></Polygon>").Scan(&kmlArea, &kmlValid)
	if err != nil || !kmlValid || math.Abs(kmlArea-chk.AreaM2)/chk.AreaM2 > 1e-5 { // 8-decimal coordinates = ~1 mm rounding
		t.Fatalf("PostGIS KML read-back: area %.4f vs %.4f valid %v err %v", kmlArea, chk.AreaM2, kmlValid, err)
	}
	for _, p := range kr {
		best := math.Inf(1)
		for _, q := range ll {
			best = math.Min(best, geo.Distance(p, q))
		}
		if best > 0.002 {
			t.Fatalf("KML vertex is %.4f m from the walked point", best)
		}
	}

	// Overlap: Sunanda (seeded) walked over half of this field must be flagged.
	var sunandaID string
	db.QueryRow(ctx, `SELECT id FROM farmers WHERE code='4417sun'`).Scan(&sunandaID)
	over := ring(kurunda, densify([][2]float64{{40, 0}, {140, 0}, {140, 80}, {40, 80}}, 2))
	c.do("POST", "/v1/boundaries/check", BoundaryIn{FarmerID: sunandaID, Points: over}, &chk)
	if chk.OverlapOK || len(chk.Overlaps) != 1 || !chk.Overlaps[0].Flagged {
		t.Fatalf("overlap not flagged: %+v", chk)
	}
	// A neighbour sharing the east bund, walked 1.5 m inside it, is only a sliver.
	edge := ring(kurunda, densify([][2]float64{{94, -40}, {200, -40}, {200, 100}, {78, 100}, {78, 84}, {94.5, 40}}, 2))
	c.do("POST", "/v1/boundaries/check", BoundaryIn{FarmerID: sunandaID, Points: edge}, &chk)
	if !chk.Valid || !chk.OverlapOK {
		t.Fatalf("shared-bund sliver flagged: %+v", chk)
	}

	// Self-crossing walk is refused.
	bow := ring(kurunda, densify([][2]float64{{0, 0}, {60, 60}, {60, 0}, {0, 60}}, 2))
	if s := c.do("PUT", "/v1/farmers/"+fid+"/boundaries/bbbbbbbb-bbbb-4ccc-8ddd-eeeeeeeeeeee", BoundaryIn{Points: bow}, nil); s != 422 {
		t.Fatalf("bow-tie accepted: %d", s)
	}
	// Too few points refused.
	if s := c.do("PUT", "/v1/farmers/"+fid+"/boundaries/cccccccc-bbbb-4ccc-8ddd-eeeeeeeeeeee",
		BoundaryIn{Points: ring(kurunda, [][2]float64{{0, 0}, {50, 0}, {50, 50}, {0, 50}})}, nil); s != 422 {
		t.Fatalf("4-point ring accepted: %d", s)
	}

	// Re-walking the same land replaces the old boundary.
	bid2 := "dddddddd-bbbb-4ccc-8ddd-eeeeeeeeeeee"
	if s := c.do("PUT", "/v1/farmers/"+fid+"/boundaries/"+bid2, in, nil); s != 200 {
		t.Fatalf("re-walk %d", s)
	}
	var active int
	db.QueryRow(ctx, `SELECT count(*) FROM boundaries WHERE farmer_id=$1 AND status='active'`, fid).Scan(&active)
	if active != 1 {
		t.Fatalf("active boundaries %d", active)
	}

	details := FarmDetails{WaterSources: []string{"borewell"}, TimberSpecies: []string{"mahogany"}, FruitSpecies: []string{"mango"},
		TimberPercent: 40, FruitPercent: 60, LandUse: []string{"cropland"}, KharifSame: true, RabiSame: true}
	bad2 := details
	bad2.FruitPercent = 50
	if s := c.do("PUT", "/v1/farmers/"+fid+"/farm-details", bad2, nil); s != 400 {
		t.Fatalf("90%% split accepted: %d", s)
	}
	if s := c.do("PUT", "/v1/farmers/"+fid+"/farm-details", details, nil); s != 200 {
		t.Fatalf("farm details %d", s)
	}
	c.do("GET", "/v1/farmers/"+fid, nil, &f)
	if f.Status != "submitted" || len(f.Boundaries) != 1 || len(f.Files) != 1 {
		t.Fatalf("final farmer %+v", f)
	}
	c.do("GET", "/v1/action-items", nil, &items)
	for _, it := range items {
		if it.FarmerID == fid {
			t.Fatalf("walk action still open: %+v", it)
		}
	}
	// Admin pages render.
	req, _ := http.NewRequest("GET", srv.URL+"/admin/farmers/"+fid, nil)
	req.SetBasicAuth("a", "b")
	resp, err := http.DefaultClient.Do(req)
	if err != nil || resp.StatusCode != 200 {
		t.Fatalf("admin farmer page: %v %d", err, resp.StatusCode)
	}
	resp.Body.Close()
}

// PostGIS (GeographicLib ellipsoidal area) is the reference; our tangent-plane
// area must match it across field sizes and latitudes.
func TestAreaAgainstPostGIS(t *testing.T) {
	srv, _ := testServer(t)
	c := login(t, srv.URL)
	cases := []struct {
		origin geo.LatLng
		size   float64
	}{
		{kurunda, 20}, {kurunda, 78}, {kurunda, 300}, {kurunda, 1500},
		{geo.LatLng{Lat: 8.5, Lng: 76.9}, 120}, {geo.LatLng{Lat: 34.1, Lng: 74.8}, 120}, {geo.LatLng{Lat: 60, Lng: 10}, 200},
	}
	for _, tc := range cases {
		s := tc.size
		shape := densify([][2]float64{{0, 0}, {s, s * 0.05}, {s * 1.1, s * 0.7}, {s * 0.4, s}, {-s * 0.1, s * 0.5}}, math.Max(2, s/60))
		pts := ring(tc.origin, shape)
		var chk BoundaryCheck
		c.do("POST", "/v1/boundaries/check", BoundaryIn{Points: pts}, &chk)
		mine := geo.Area(ringOf(pts))
		rel := math.Abs(chk.AreaM2-mine) / chk.AreaM2
		t.Logf("lat %5.1f size %5.0f m: postgis %.3f m², geo %.3f m², rel diff %.1e", tc.origin.Lat, s, chk.AreaM2, mine, rel)
		if !chk.Valid || rel > 2e-5 {
			t.Fatalf("area disagreement %.2e", rel)
		}
	}
}
