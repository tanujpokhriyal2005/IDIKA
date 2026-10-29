package api

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"math"
	"net/http"
	"strings"
	"time"

	"fieldproof/backend/internal/geo"

	"github.com/jackc/pgx/v5"
)

// PointIn is one recorded boundary vertex as sent by the phone.
type PointIn struct {
	Lat float64 `json:"lat"`
	Lng float64 `json:"lng"`
	Acc float64 `json:"acc"` // horizontal accuracy (m) of the fix
	T   int64   `json:"t"`   // fix time, unix ms
}

type BoundaryIn struct {
	FarmerID     string     `json:"farmerId"`
	LandID       *string    `json:"landId"`
	Points       []PointIn  `json:"points"` // ring as walked; closing vertex optional
	ClientAreaM2 float64    `json:"clientAreaM2"`
	WalkedM      float64    `json:"walkedM"`
	AccuracyM    float64    `json:"accuracyM"`
	StartedAt    *time.Time `json:"startedAt"`
	FinishedAt   *time.Time `json:"finishedAt"`
	// onboarding (default), pre_plant (just before planting) or post_plant (after planting).
	Purpose string `json:"purpose"`
	// pre_plant only: why a walk partly off the onboarding land is fine, or a
	// reported land change when it is mostly on other land.
	LandNote   string        `json:"landNote"`
	LandChange *LandChangeIn `json:"landChange"`
}

type Overlap struct {
	FarmerCode string  `json:"farmerCode"`
	FarmerName string  `json:"farmerName"`
	OverlapM2  float64 `json:"overlapM2"`
	Flagged    bool    `json:"flagged"`
}

type BoundaryCheck struct {
	AreaM2      float64   `json:"areaM2"`
	AreaAcres   float64   `json:"areaAcres"`
	Points      int       `json:"points"`
	Valid       bool      `json:"valid"`
	Problem     string    `json:"problem,omitempty"`
	Overlaps    []Overlap `json:"overlaps"`
	OverlapOK   bool      `json:"overlapOk"`
	ToleranceM2 float64   `json:"toleranceM2"`
}

func ringOf(pts []PointIn) []geo.LatLng {
	out := make([]geo.LatLng, 0, len(pts))
	for _, p := range pts {
		out = append(out, geo.LatLng{Lat: p.Lat, Lng: p.Lng})
	}
	if len(out) > 1 && out[0] == out[len(out)-1] {
		out = out[:len(out)-1]
	}
	return out
}

// polygonWKT writes a closed, counter-clockwise WKT polygon (lon lat order).
func polygonWKT(ring []geo.LatLng) string {
	ccw := geo.CCW(ring)
	ccw = append(ccw, ccw[0])
	parts := make([]string, len(ccw))
	for i, p := range ccw {
		parts[i] = fmt.Sprintf("%.9f %.9f", p.Lng, p.Lat)
	}
	return "SRID=4326;POLYGON((" + strings.Join(parts, ",") + "))"
}

// Edges walked by two surveyors along a shared bund overlap by a GPS-width
// sliver; only overlaps above this are treated as a real conflict.
func overlapTolerance(areaM2 float64) float64 { return math.Max(100, 0.03*areaM2) }

func (s *Server) checkBoundary(ctx context.Context, q pgx.Tx, farmerID string, ring []geo.LatLng) (BoundaryCheck, error) {
	c := BoundaryCheck{Points: len(ring), Overlaps: []Overlap{}, OverlapOK: true}
	if err := geo.ValidateRing(ring); err != nil {
		c.Problem = err.Error()
		return c, nil
	}
	wkt := polygonWKT(ring)
	var valid bool
	var reason string
	// PostGIS computes the authoritative ellipsoidal area (GeographicLib).
	err := q.QueryRow(ctx, `SELECT ST_IsValid(g::geometry), ST_IsValidReason(g::geometry), ST_Area(g::geography, true)
		FROM (SELECT ST_GeomFromEWKT($1) g) x`, wkt).Scan(&valid, &reason, &c.AreaM2)
	if err != nil {
		return c, err
	}
	if !valid {
		c.Problem = reason
		return c, nil
	}
	c.Valid = true
	c.AreaAcres = c.AreaM2 / geo.SqMetersPerAcre
	c.ToleranceM2 = overlapTolerance(c.AreaM2)
	rows, err := q.Query(ctx, `
		SELECT f.code, f.full_name, ST_Area(ST_Intersection(b.geom, n.g), true)
		FROM boundaries b JOIN farmers f ON f.id=b.farmer_id,
			(SELECT ST_GeogFromText($1) g) n
		WHERE b.status='active' AND b.purpose='onboarding' AND b.farmer_id <> $2::uuid AND ST_Intersects(b.geom, n.g)`, wkt, farmerID)
	if err != nil {
		return c, err
	}
	c.Overlaps, err = pgx.CollectRows(rows, func(row pgx.CollectableRow) (Overlap, error) {
		var o Overlap
		err := row.Scan(&o.FarmerCode, &o.FarmerName, &o.OverlapM2)
		o.Flagged = o.OverlapM2 > c.ToleranceM2
		return o, err
	})
	if err != nil {
		return c, err
	}
	for _, o := range c.Overlaps {
		if o.Flagged {
			c.OverlapOK = false
		}
	}
	return c, nil
}

func (s *Server) handleBoundaryCheck(w http.ResponseWriter, r *http.Request) {
	var in BoundaryIn
	if !readJSON(w, r, &in) {
		return
	}
	if !uuidRe.MatchString(in.FarmerID) {
		in.FarmerID = "00000000-0000-0000-0000-000000000000"
	}
	tx, err := s.db.Begin(r.Context())
	if err != nil {
		writeErr(w, 500, "db", err.Error())
		return
	}
	defer tx.Rollback(r.Context())
	c, err := s.checkBoundary(r.Context(), tx, in.FarmerID, ringOf(in.Points))
	if err != nil {
		writeErr(w, 500, "db", err.Error())
		return
	}
	writeJSON(w, 200, c)
}

func (s *Server) handlePutBoundary(w http.ResponseWriter, r *http.Request) {
	farmerID, bid := r.PathValue("id"), r.PathValue("bid")
	if !uuidRe.MatchString(bid) {
		writeErr(w, 400, "bad_id", "Boundary id must be a UUID.")
		return
	}
	ctx := r.Context()
	if ok, err := s.ownsFarmer(ctx, surveyorFrom(r).ID, farmerID); err != nil || !ok {
		writeErr(w, 404, "not_found", "Farmer not found. Sync the farmer first.")
		return
	}
	var in BoundaryIn
	if !readJSON(w, r, &in) {
		return
	}
	if in.LandID != nil && !uuidRe.MatchString(*in.LandID) {
		in.LandID = nil
	}
	switch in.Purpose {
	case "", "onboarding":
		in.Purpose = "onboarding"
	case "pre_plant", "post_plant":
		in.LandID = nil // a plantation walk covers the whole planting area
		if msg := s.plantWalkAllowed(ctx, farmerID, in.Purpose); msg != "" {
			writeErr(w, 409, "wrong_step", msg)
			return
		}
	default:
		writeErr(w, 400, "bad_purpose", "purpose must be onboarding, pre_plant or post_plant")
		return
	}
	var clash bool
	s.db.QueryRow(ctx, `SELECT EXISTS(SELECT 1 FROM boundaries WHERE id=$1 AND (farmer_id<>$2 OR purpose<>$3))`, bid, farmerID, in.Purpose).Scan(&clash)
	if clash {
		writeErr(w, 409, "id_in_use", "This boundary id belongs to another walk.")
		return
	}
	ring := ringOf(in.Points)
	tx, err := s.db.Begin(ctx)
	if err != nil {
		writeErr(w, 500, "db", err.Error())
		return
	}
	defer tx.Rollback(ctx)
	c, err := s.checkBoundary(ctx, tx, farmerID, ring)
	if err != nil {
		writeErr(w, 500, "db", err.Error())
		return
	}
	if !c.Valid {
		writeJSON(w, 422, map[string]any{"error": "invalid_boundary", "message": "Boundary is not a clean shape: " + c.Problem, "check": c})
		return
	}
	pts := in.Points
	if len(pts) > 1 && pts[0].Lat == pts[len(pts)-1].Lat && pts[0].Lng == pts[len(pts)-1].Lng {
		pts = pts[:len(pts)-1]
	}
	ptsJSON, _ := json.Marshal(pts)
	// A new walk of the same parcel (and purpose) replaces the previous one.
	if _, err := tx.Exec(ctx, `UPDATE boundaries SET status='replaced'
		WHERE farmer_id=$1 AND id<>$2 AND status='active' AND purpose=$4 AND land_id IS NOT DISTINCT FROM $3::uuid`, farmerID, bid, in.LandID, in.Purpose); err != nil {
		writeErr(w, 500, "db", err.Error())
		return
	}
	_, err = tx.Exec(ctx, `
		INSERT INTO boundaries(id, farmer_id, land_id, geom, points, point_count, area_m2, client_area_m2, walked_m, accuracy_m, started_at, finished_at, purpose)
		VALUES ($1,$2,$3,ST_GeogFromText($4),$5,$6,$7,$8,$9,$10,$11,$12,$13)
		ON CONFLICT (id) DO UPDATE SET geom=EXCLUDED.geom, points=EXCLUDED.points, point_count=EXCLUDED.point_count,
			area_m2=EXCLUDED.area_m2, client_area_m2=EXCLUDED.client_area_m2, walked_m=EXCLUDED.walked_m,
			accuracy_m=EXCLUDED.accuracy_m, started_at=EXCLUDED.started_at, finished_at=EXCLUDED.finished_at, status='active'`,
		bid, farmerID, in.LandID, polygonWKT(ring), ptsJSON, len(ring), c.AreaM2, in.ClientAreaM2, in.WalkedM, in.AccuracyM, in.StartedAt, in.FinishedAt, in.Purpose)
	if err != nil {
		writeErr(w, 500, "db", err.Error())
		return
	}
	if in.Purpose == "onboarding" {
		tx.Exec(ctx, `UPDATE farmers SET status='boundary_done', updated_at=now() WHERE id=$1 AND status='registered'`, farmerID)
	} else if err := s.recordPlantWalk(ctx, tx, farmerID, bid, in, c.AreaM2, surveyorFrom(r).ID); err != nil {
		var lse *landSwapError
		if errors.As(err, &lse) {
			lse.write(w)
			return
		}
		writeErr(w, 500, "db", err.Error())
		return
	}
	if err := tx.Commit(ctx); err != nil {
		writeErr(w, 500, "db", err.Error())
		return
	}
	if in.Purpose == "onboarding" {
		s.markReuploaded(ctx, farmerID, "boundary", surveyorFrom(r).ID)
	}
	writeJSON(w, 200, map[string]any{"id": bid, "check": c, "purpose": in.Purpose})
}

// ---------- KML ----------

func (s *Server) farmerKML(ctx context.Context, farmerID, onlyBoundary string) (name string, doc string, err error) {
	f, parcels, err := s.farmerParcelsOnly(ctx, farmerID, onlyBoundary)
	if err != nil {
		return "", "", err
	}
	if len(parcels) == 0 {
		return "", "", pgx.ErrNoRows
	}
	return f.Code, geo.BuildKML(f.FullName+" · "+f.Code, parcels), nil
}

// farmerParcels: all active boundaries of one farmer as KML parcels.
func (s *Server) farmerParcels(ctx context.Context, farmerID string) ([]geo.KMLParcel, error) {
	_, p, err := s.farmerParcelsOnly(ctx, farmerID, "")
	return p, err
}

func (s *Server) farmerParcelsOnly(ctx context.Context, farmerID, onlyBoundary string) (*FarmerOut, []geo.KMLParcel, error) {
	f, err := s.loadFarmer(ctx, farmerID)
	if err != nil {
		return nil, nil, err
	}
	lands := map[string]LandOut{}
	for _, l := range f.Lands {
		lands[l.ID] = l
	}
	var parcels []geo.KMLParcel
	for i, b := range f.Boundaries {
		if onlyBoundary != "" && b.ID != onlyBoundary {
			continue
		}
		var started *time.Time
		s.db.QueryRow(ctx, `SELECT started_at FROM boundaries WHERE id=$1`, b.ID).Scan(&started)
		survey := ""
		label := fmt.Sprintf("parcel %d", i+1)
		if b.LandID != nil {
			if l, ok := lands[*b.LandID]; ok {
				survey = l.SurveyNo
				label = "Gat " + l.SurveyNo
			}
		}
		p := geo.KMLParcel{
			Name:       f.Code + " · " + label,
			Ring:       ringOf(b.Points),
			FarmerName: f.FullName,
			FarmerCode: f.Code,
			Village:    f.Village.Name + ", " + f.Village.Block + ", " + f.Village.District,
			SurveyNo:   survey,
		}
		if b.AccuracyM != nil {
			p.AccuracyM = *b.AccuracyM
		}
		if b.WalkedM != nil {
			p.WalkedM = *b.WalkedM
		}
		if started != nil {
			p.StartedAt = *started
		}
		if b.FinishedAt != nil {
			p.FinishedAt = *b.FinishedAt
		}
		parcels = append(parcels, p)
	}
	return f, parcels, nil
}

func writeKML(w http.ResponseWriter, name, doc string) {
	w.Header().Set("Content-Type", "application/vnd.google-earth.kml+xml; charset=utf-8")
	w.Header().Set("Content-Disposition", fmt.Sprintf(`attachment; filename="%s-boundary.kml"`, name))
	w.Write([]byte(doc))
}

func (s *Server) handleFarmerKML(w http.ResponseWriter, r *http.Request) {
	id := r.PathValue("id")
	if ok, err := s.ownsFarmer(r.Context(), surveyorFrom(r).ID, id); err != nil || !ok {
		writeErr(w, 404, "not_found", "Farmer not found.")
		return
	}
	name, doc, err := s.farmerKML(r.Context(), id, "")
	if err != nil {
		writeErr(w, 404, "no_boundary", "No boundary walked yet.")
		return
	}
	writeKML(w, name, doc)
}

func (s *Server) handleBoundaryKML(w http.ResponseWriter, r *http.Request) {
	bid := r.PathValue("bid")
	var farmerID string
	err := s.db.QueryRow(r.Context(), `SELECT b.farmer_id FROM boundaries b JOIN farmers f ON f.id=b.farmer_id WHERE b.id=$1 AND f.surveyor_id=$2`,
		bid, surveyorFrom(r).ID).Scan(&farmerID)
	if err != nil {
		writeErr(w, 404, "not_found", "Boundary not found.")
		return
	}
	name, doc, err := s.farmerKML(r.Context(), farmerID, bid)
	if err != nil {
		writeErr(w, 404, "not_found", "Boundary not active.")
		return
	}
	writeKML(w, name, doc)
}
