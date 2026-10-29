package api

import (
	"archive/zip"
	"bytes"
	"encoding/base64"
	"encoding/xml"
	"errors"
	"io"
	"net/http"
	"path"
	"strconv"
	"strings"
	"time"

	"github.com/jackc/pgx/v5"
)

// Land check: a dashboard user uploads a KML of parcels and the land check agent
// (landcheck-agent/worker.py, run where Google Earth Engine access is set up) checks
// each parcel's satellite history since 2017 for tree clearing, buildings and water.
// The API only stores the upload, lists checks and serves the finished reports.

const (
	maxLandParcels = 50
	maxKMLBytes    = 5 << 20
)

type LandCheckIn struct {
	Name     string `json:"name"`
	FileName string `json:"fileName"`
	KML      string `json:"kml"` // the file's text, for a .kml
	KMZ      string `json:"kmz"` // the file in base64, for a .kmz
}

type LandCheckRow struct {
	ID         string         `json:"id"`
	Name       string         `json:"name"`
	FileName   string         `json:"fileName"`
	Parcels    int            `json:"parcels"`
	Status     string         `json:"status"`
	Progress   int            `json:"progress"` // 0-100 while the agent works on it
	Done       int            `json:"parcelsDone"`
	Error      string         `json:"error,omitempty"`
	Summary    map[string]int `json:"summary"`
	CreatedBy  string         `json:"createdBy"`
	CreatedAt  time.Time      `json:"createdAt"`
	StartedAt  *time.Time     `json:"startedAt"`
	FinishedAt *time.Time     `json:"finishedAt"`
	InLine     int            `json:"inLine,omitempty"` // place in the queue while waiting
	CanManage  bool           `json:"canManage"`        // may pause, resume or delete it
	creatorID  string
}

// canManageLandCheck: the person who uploaded a check, or an admin, may pause,
// resume or delete it.
func canManageLandCheck(u *DashUser, creatorID string) bool {
	return u.Role == "admin" || u.ID == creatorID
}

// scopeLandChecks: the admin sees every check, a state lead the checks of people in
// their state, everyone else their own.
func scopeLandChecks(u *DashUser, q *qb) {
	switch u.Role {
	case "admin":
	case "state_lead":
		q.and("(lc.created_by = ? OR lc.state = ?)", u.ID, u.StateName())
	default:
		q.and("lc.created_by = ?", u.ID)
	}
}

const landCheckCols = `lc.id, lc.name, lc.file_name, lc.parcels, lc.status, lc.progress, lc.parcels_done, lc.error, coalesce(lc.summary, '{}'::jsonb),
	u.full_name, lc.created_at, lc.started_at, lc.finished_at, lc.created_by,
	CASE WHEN lc.status = 'queued' THEN (SELECT count(*) FROM land_checks w WHERE w.status = 'queued' AND w.created_at <= lc.created_at) ELSE 0 END`

func scanLandCheck(row pgx.Row) (LandCheckRow, error) {
	var c LandCheckRow
	err := row.Scan(&c.ID, &c.Name, &c.FileName, &c.Parcels, &c.Status, &c.Progress, &c.Done, &c.Error, &c.Summary,
		&c.CreatedBy, &c.CreatedAt, &c.StartedAt, &c.FinishedAt, &c.creatorID, &c.InLine)
	return c, err
}

func (s *Server) handleDashLandChecks(w http.ResponseWriter, r *http.Request) {
	u := dashUserFrom(r)
	q := &qb{}
	scopeLandChecks(u, q)
	rows, err := s.db.Query(r.Context(), `SELECT `+landCheckCols+`
		FROM land_checks lc JOIN users u ON u.id = lc.created_by
		WHERE `+q.where()+` ORDER BY lc.created_at DESC LIMIT 100`, q.args...)
	if err != nil {
		writeErr(w, 500, "db", err.Error())
		return
	}
	items := []LandCheckRow{}
	for rows.Next() {
		c, err := scanLandCheck(rows)
		if err != nil {
			rows.Close()
			writeErr(w, 500, "db", err.Error())
			return
		}
		c.CanManage = canManageLandCheck(u, c.creatorID)
		items = append(items, c)
	}
	rows.Close()
	var seen *time.Time
	s.db.QueryRow(r.Context(), `SELECT seen_at FROM land_check_agent WHERE id = 1`).Scan(&seen)
	writeJSON(w, 200, map[string]any{"items": items, "agentSeenAt": seen})
}

func (s *Server) handleDashLandCheckCreate(w http.ResponseWriter, r *http.Request) {
	u := dashUserFrom(r)
	var in LandCheckIn
	if !readJSON(w, r, &in) {
		return
	}
	fail := func(field, msg string) {
		writeJSON(w, 400, map[string]any{"error": "bad_input", "field": field, "message": msg})
	}
	in.FileName = path.Base(strings.ReplaceAll(strings.TrimSpace(in.FileName), `\`, "/"))
	ext := strings.ToLower(path.Ext(in.FileName))
	if ext != ".kml" && ext != ".kmz" {
		fail("file", "Choose a .kml or .kmz file.")
		return
	}
	if len([]rune(in.FileName)) > 120 {
		in.FileName = string([]rune(in.FileName)[:120])
	}
	kml := in.KML
	if ext == ".kmz" {
		var err error
		if kml, err = kmlFromKMZ(in.KMZ); err != nil {
			fail("file", err.Error())
			return
		}
	}
	if len(kml) > maxKMLBytes {
		fail("file", "This file is too big. Keep it under 5 MB.")
		return
	}
	n, err := countKMLPolygons(kml)
	if err != nil {
		fail("file", "This doesn't look like a KML file. Export it again from Google Earth or the Fieldproof app.")
		return
	}
	if n == 0 {
		fail("file", "No parcels found. The KML needs at least one polygon (a closed boundary), not just points or lines.")
		return
	}
	if n > maxLandParcels {
		fail("file", "This file has "+strconv.Itoa(n)+" parcels. Check at most 50 at a time.")
		return
	}
	in.Name = strings.Join(strings.Fields(in.Name), " ")
	if in.Name == "" {
		in.Name = strings.TrimSuffix(in.FileName, path.Ext(in.FileName))
	}
	if len([]rune(in.Name)) > 80 {
		fail("name", "Keep the name under 80 characters.")
		return
	}
	var id string
	if err := s.db.QueryRow(r.Context(), `INSERT INTO land_checks (name, file_name, kml, parcels, created_by, state)
		VALUES ($1, $2, $3, $4, $5, $6) RETURNING id`, in.Name, in.FileName, kml, n, u.ID, u.State).Scan(&id); err != nil {
		writeErr(w, 500, "db", err.Error())
		return
	}
	c, err := scanLandCheck(s.db.QueryRow(r.Context(), `SELECT `+landCheckCols+`
		FROM land_checks lc JOIN users u ON u.id = lc.created_by WHERE lc.id = $1`, id))
	if err != nil {
		writeErr(w, 500, "db", err.Error())
		return
	}
	c.CanManage = true
	writeJSON(w, 201, c)
}

// handleDashLandCheckAction pauses, resumes or deletes a check. The agent notices
// within a few seconds when the check it is working on is paused or deleted and
// stops. Resuming puts the check back in line; it starts again from the beginning.
func (s *Server) handleDashLandCheckAction(action string) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		u := dashUserFrom(r)
		q := &qb{}
		q.and("lc.id::text = ?", r.PathValue("id"))
		scopeLandChecks(u, q)
		var id, status, creator string
		err := s.db.QueryRow(r.Context(), `SELECT lc.id, lc.status, lc.created_by FROM land_checks lc WHERE `+q.where(), q.args...).
			Scan(&id, &status, &creator)
		if errors.Is(err, pgx.ErrNoRows) {
			writeErr(w, 404, "not_found", "No such check.")
			return
		} else if err != nil {
			writeErr(w, 500, "db", err.Error())
			return
		}
		if !canManageLandCheck(u, creator) {
			writeErr(w, 403, "forbidden", "Only the person who uploaded this check, or an admin, can change it.")
			return
		}
		var sql string
		switch {
		case action == "delete":
			sql = `DELETE FROM land_checks WHERE id = $1`
		case action == "pause" && (status == "queued" || status == "running"):
			sql = `UPDATE land_checks SET status = 'paused' WHERE id = $1`
		case action == "resume" && (status == "paused" || status == "stopped" || status == "failed"):
			sql = `UPDATE land_checks SET status = 'queued', error = '', progress = 0, parcels_done = 0,
				started_at = NULL, finished_at = NULL, report = NULL, summary = NULL WHERE id = $1`
		default:
			writeErr(w, 409, "bad_state", "This check can't be "+map[string]string{"pause": "paused", "resume": "resumed"}[action]+" now.")
			return
		}
		if _, err := s.db.Exec(r.Context(), sql, id); err != nil {
			writeErr(w, 500, "db", err.Error())
			return
		}
		writeJSON(w, 200, map[string]any{"ok": true})
	}
}

// handleDashLandCheckReport serves a finished report as its own page. Reports are
// built by the agent with every picture inside, and have no scripts, so a strict
// policy that allows only inline styles and data: images fits them.
func (s *Server) handleDashLandCheckReport(w http.ResponseWriter, r *http.Request) {
	u := dashUserFrom(r)
	q := &qb{}
	q.and("lc.id::text = ?", r.PathValue("id"))
	scopeLandChecks(u, q)
	var name, status string
	var report *string
	err := s.db.QueryRow(r.Context(), `SELECT lc.name, lc.status, lc.report FROM land_checks lc WHERE `+q.where(), q.args...).
		Scan(&name, &status, &report)
	if errors.Is(err, pgx.ErrNoRows) {
		writeErr(w, 404, "not_found", "No such check.")
		return
	} else if err != nil {
		writeErr(w, 500, "db", err.Error())
		return
	}
	if status != "done" || report == nil {
		writeErr(w, 409, "not_ready", "The report isn't ready yet.")
		return
	}
	w.Header().Set("Content-Type", "text/html; charset=utf-8")
	w.Header().Set("Content-Security-Policy", "default-src 'none'; img-src data:; style-src 'unsafe-inline'; base-uri 'none'; form-action 'none'; frame-ancestors 'none'")
	w.Header().Set("X-Content-Type-Options", "nosniff")
	w.Header().Set("Cache-Control", "private, no-store")
	disp := "inline"
	if r.URL.Query().Get("download") == "1" {
		disp = "attachment"
	}
	w.Header().Set("Content-Disposition", disp+`; filename="`+safeFileName(name)+`.html"`)
	io.WriteString(w, *report)
}

// countKMLPolygons counts the parcels (Polygon elements) in a KML document.
func countKMLPolygons(kml string) (int, error) {
	d := xml.NewDecoder(strings.NewReader(kml))
	d.Strict = false
	n, sawKML := 0, false
	for {
		tok, err := d.Token()
		if errors.Is(err, io.EOF) {
			break
		}
		if err != nil {
			return 0, err
		}
		if se, ok := tok.(xml.StartElement); ok {
			switch se.Name.Local {
			case "kml", "Document", "Placemark":
				sawKML = true
			case "Polygon":
				n++
			}
		}
	}
	if !sawKML {
		return 0, errors.New("not KML")
	}
	return n, nil
}

// kmlFromKMZ unpacks the main KML from a KMZ (a zip with doc.kml inside).
func kmlFromKMZ(b64 string) (string, error) {
	raw, err := base64.StdEncoding.DecodeString(b64)
	if err != nil || len(raw) == 0 {
		return "", errors.New("This KMZ file could not be read. Try exporting it as .kml instead.")
	}
	if len(raw) > maxKMLBytes {
		return "", errors.New("This file is too big. Keep it under 5 MB.")
	}
	z, err := zip.NewReader(bytes.NewReader(raw), int64(len(raw)))
	if err != nil {
		return "", errors.New("This KMZ file could not be read. Try exporting it as .kml instead.")
	}
	var pick *zip.File
	for _, f := range z.File {
		if strings.EqualFold(path.Ext(f.Name), ".kml") && (pick == nil || strings.EqualFold(path.Base(f.Name), "doc.kml")) {
			pick = f
		}
	}
	if pick == nil {
		return "", errors.New("There is no KML inside this KMZ file.")
	}
	rc, err := pick.Open()
	if err != nil {
		return "", errors.New("This KMZ file could not be read. Try exporting it as .kml instead.")
	}
	defer rc.Close()
	b, err := io.ReadAll(io.LimitReader(rc, maxKMLBytes+1))
	if err != nil {
		return "", errors.New("This KMZ file could not be read. Try exporting it as .kml instead.")
	}
	return string(b), nil
}

func safeFileName(s string) string {
	var b strings.Builder
	for _, r := range s {
		if r == ' ' || r == '-' || r == '_' || r == '.' || (r >= '0' && r <= '9') || (r >= 'a' && r <= 'z') || (r >= 'A' && r <= 'Z') {
			b.WriteRune(r)
		}
	}
	if out := strings.TrimSpace(b.String()); out != "" {
		return out
	}
	return "land-check"
}
