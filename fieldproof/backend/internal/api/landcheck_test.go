package api

import (
	"archive/zip"
	"bytes"
	"context"
	"encoding/base64"
	"io"
	"net/http"
	"strings"
	"testing"
)

func testKML(parcels int) string {
	var b strings.Builder
	b.WriteString(`<?xml version="1.0" encoding="UTF-8"?><kml xmlns="http://www.opengis.net/kml/2.2"><Document>`)
	for i := 0; i < parcels; i++ {
		b.WriteString(`<Placemark><name>P</name><Polygon><outerBoundaryIs><LinearRing><coordinates>
			78.7128,27.8681,0 78.7130,27.8683,0 78.7135,27.8681,0 78.7128,27.8681,0</coordinates></LinearRing></outerBoundaryIs></Polygon></Placemark>`)
	}
	b.WriteString(`</Document></kml>`)
	return b.String()
}

type landChecksPage struct {
	Items []LandCheckRow `json:"items"`
}

func (c *dashClient) landChecks() []LandCheckRow {
	c.t.Helper()
	var p landChecksPage
	if s := c.do("GET", "/v1/dash/land-checks", nil, &p); s != 200 {
		c.t.Fatalf("land-checks: %d", s)
	}
	return p.Items
}

func (c *dashClient) raw(path string) (*http.Response, string) {
	c.t.Helper()
	resp, err := c.http.Get(c.base + path)
	if err != nil {
		c.t.Fatal(err)
	}
	defer resp.Body.Close()
	b, _ := io.ReadAll(resp.Body)
	return resp, string(b)
}

func TestLandCheck(t *testing.T) {
	base, db := dashServer(t)
	ctx := context.Background()
	admin := dashLogin(t, base, "admin", testAdminPassword)
	meera := dashLogin(t, base, "meera.mh", DemoPassword)
	arvind := dashLogin(t, base, "arvind.mp", DemoPassword)

	// Uploads that can't be checked are refused with a reason.
	for name, in := range map[string]LandCheckIn{
		"wrong type": {FileName: "farm.csv", KML: testKML(1)},
		"not KML":    {FileName: "farm.kml", KML: "hello"},
		"no parcels": {FileName: "farm.kml", KML: `<kml><Document><Placemark><Point><coordinates>78,27</coordinates></Point></Placemark></Document></kml>`},
		"too many":   {FileName: "farm.kml", KML: testKML(maxLandParcels + 1)},
		"broken kmz": {FileName: "farm.kmz", KMZ: "not base64!"},
		"long name":  {FileName: "farm.kml", KML: testKML(1), Name: strings.Repeat("x", 81)},
	} {
		var e map[string]any
		if s := admin.do("POST", "/v1/dash/land-checks", in, &e); s != 400 || e["message"] == "" {
			t.Fatalf("%s: %d %v", name, s, e)
		}
	}

	var mine LandCheckRow
	if s := admin.do("POST", "/v1/dash/land-checks", LandCheckIn{FileName: `C:\Downloads\Aligarh batch.kml`, KML: testKML(8)}, &mine); s != 201 {
		t.Fatalf("upload: %d", s)
	}
	if mine.Parcels != 8 || mine.Status != "queued" || mine.FileName != "Aligarh batch.kml" || mine.Name != "Aligarh batch" || mine.InLine != 1 {
		t.Fatalf("upload: %+v", mine)
	}

	// A KMZ is unpacked.
	var zbuf bytes.Buffer
	zw := zip.NewWriter(&zbuf)
	f, _ := zw.Create("doc.kml")
	f.Write([]byte(testKML(2)))
	zw.Close()
	var meeras LandCheckRow
	if s := meera.do("POST", "/v1/dash/land-checks", LandCheckIn{Name: "Hingoli new farms", FileName: "farms.kmz", KMZ: base64.StdEncoding.EncodeToString(zbuf.Bytes())}, &meeras); s != 201 || meeras.Parcels != 2 || meeras.InLine != 2 {
		t.Fatalf("kmz: %d %+v", s, meeras)
	}

	// The admin sees everything, a state lead their own state's checks.
	if n := len(admin.landChecks()); n != 2 {
		t.Fatalf("admin sees %d", n)
	}
	if got := meera.landChecks(); len(got) != 1 || got[0].ID != meeras.ID || got[0].CreatedBy == "" {
		t.Fatalf("Maharashtra lead sees %+v", got)
	}
	if n := len(arvind.landChecks()); n != 0 {
		t.Fatalf("Madhya Pradesh lead sees %d", n)
	}

	// The report opens only when it's ready, and only for people who can see the check.
	if resp, _ := admin.raw("/v1/dash/land-checks/" + mine.ID + "/report"); resp.StatusCode != 409 {
		t.Fatalf("report before done: %d", resp.StatusCode)
	}
	if _, err := db.Exec(ctx, `UPDATE land_checks SET status='done', report='<html><body>Report for Aligarh</body></html>',
		summary='{"Flag": 2, "Approve": 6}', finished_at=now() WHERE id=$1`, mine.ID); err != nil {
		t.Fatal(err)
	}
	resp, body := admin.raw("/v1/dash/land-checks/" + mine.ID + "/report")
	if resp.StatusCode != 200 || !strings.Contains(body, "Report for Aligarh") ||
		!strings.HasPrefix(resp.Header.Get("Content-Type"), "text/html") ||
		!strings.Contains(resp.Header.Get("Content-Security-Policy"), "default-src 'none'") {
		t.Fatalf("report: %d %q %v", resp.StatusCode, body, resp.Header)
	}
	if resp, _ := admin.raw("/v1/dash/land-checks/" + mine.ID + "/report?download=1"); !strings.HasPrefix(resp.Header.Get("Content-Disposition"), "attachment") {
		t.Fatalf("download: %v", resp.Header)
	}
	if resp, _ := meera.raw("/v1/dash/land-checks/" + mine.ID + "/report"); resp.StatusCode != 404 {
		t.Fatalf("other state's report: %d", resp.StatusCode)
	}
	for _, c := range admin.landChecks() {
		if c.ID == mine.ID && (c.Summary["Flag"] != 2 || c.Summary["Approve"] != 6 || c.InLine != 0) {
			t.Fatalf("summary: %+v", c)
		}
	}
	// The agent's progress shows on the list while it works.
	if _, err := db.Exec(ctx, `UPDATE land_checks SET status='running', started_at=now(), progress=43, parcels_done=1 WHERE id=$1`, meeras.ID); err != nil {
		t.Fatal(err)
	}
	if got := meera.landChecks(); got[0].Status != "running" || got[0].Progress != 43 || got[0].Done != 1 || got[0].StartedAt == nil {
		t.Fatalf("progress: %+v", got[0])
	}
	// Pause, resume and delete: the uploader or an admin, and only from a state where it makes sense.
	if got := meera.landChecks(); !got[0].CanManage {
		t.Fatalf("uploader can't manage own check: %+v", got[0])
	}
	if s := meera.do("POST", "/v1/dash/land-checks/"+meeras.ID+"/pause", nil, nil); s != 200 {
		t.Fatalf("pause running: %d", s)
	}
	if got := meera.landChecks(); got[0].Status != "paused" {
		t.Fatalf("after pause: %+v", got[0])
	}
	if s := meera.do("POST", "/v1/dash/land-checks/"+meeras.ID+"/pause", nil, nil); s != 409 {
		t.Fatalf("pause twice: %d", s)
	}
	if s := meera.do("POST", "/v1/dash/land-checks/"+meeras.ID+"/resume", nil, nil); s != 200 {
		t.Fatalf("resume: %d", s)
	}
	if got := meera.landChecks(); got[0].Status != "queued" || got[0].Progress != 0 || got[0].StartedAt != nil {
		t.Fatalf("after resume: %+v", got[0])
	}
	if s := meera.do("POST", "/v1/dash/land-checks/"+mine.ID+"/pause", nil, nil); s != 404 {
		t.Fatalf("another state's check: %d", s)
	}
	// A state lead sees an NGO's check in their state but may not change it; an admin may.
	if _, err := db.Exec(ctx, `UPDATE land_checks SET created_by=(SELECT id FROM users WHERE role='ngo' AND state='Maharashtra' LIMIT 1) WHERE id=$1`, meeras.ID); err != nil {
		t.Fatal(err)
	}
	if got := meera.landChecks(); len(got) != 1 || got[0].CanManage {
		t.Fatalf("state lead on an NGO's check: %+v", got)
	}
	if s := meera.do("DELETE", "/v1/dash/land-checks/"+meeras.ID, nil, nil); s != 403 {
		t.Fatalf("state lead deleting an NGO's check: %d", s)
	}
	if s := admin.do("DELETE", "/v1/dash/land-checks/"+meeras.ID, nil, nil); s != 200 {
		t.Fatalf("admin delete: %d", s)
	}
	if n := len(meera.landChecks()); n != 0 {
		t.Fatalf("after delete: %d", n)
	}
	// Uploads need the dashboard's request header, like every other change.
	if s := admin.req("POST", "/v1/dash/land-checks", LandCheckIn{FileName: "a.kml", KML: testKML(1)}, nil, false); s != 403 {
		t.Fatalf("without header: %d", s)
	}
}
