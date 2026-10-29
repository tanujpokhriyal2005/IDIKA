package api

import (
	"context"
	"fmt"
	"net/http"
	"strings"
	"time"

	"github.com/jackc/pgx/v5"
)

// Land swap check. Sometimes a farmer offers one field at onboarding and a
// different one on planting day. The boundary walked just before planting is
// compared with the onboarding land:
//
//   ≥70% on the onboarding land   fine
//   30–70%                        fine, with the surveyor's note; the reviewer sees it
//   <30%                          a different land: refused, unless the surveyor
//                                 reports a land change (reason, new Gat, 7/12),
//                                 which an NGO, state lead or admin approves.
//
// The phone checks the same thresholds before and during the walk, so the
// surveyor is warned before anything is saved.

const (
	LandFitOK      = 0.70
	LandFitMin     = 0.30
	landAllowanceM = 20.0 // GPS drift at the edges
)

type LandChangeIn struct {
	Reason    string  `json:"reason"`
	SurveyNo  string  `json:"surveyNo"`
	AreaHa    float64 `json:"areaHa"`
	OwnerType string  `json:"ownerType"`
}

var landChangeReasons = map[string]string{
	"changed_mind": "Farmer changed his mind",
	"no_water":     "No water on the registered land",
	"family":       "Land given to family",
	"dispute":      "Land under dispute",
	"other":        "Other",
}

type LandChangeOut struct {
	Status     string     `json:"status"` // waiting | approved | rejected | pending
	Reason     string     `json:"reason"`
	ReasonText string     `json:"reasonText"`
	SurveyNo   string     `json:"surveyNo"`
	AreaHa     *float64   `json:"areaHa"`
	OwnerType  string     `json:"ownerType"`
	At         *time.Time `json:"at"`
	Message    string     `json:"message"`
	Reviewer   string     `json:"reviewer"`
	ReviewedAt *time.Time `json:"reviewedAt"`
	Photos     []DashFile `json:"photos"`
}

// landSwapError refuses a walk; the phone shows the numbers.
type landSwapError struct {
	status    int
	code, msg string
	overlap   float64
	distanceM float64
}

func (e *landSwapError) Error() string { return e.msg }

func (e *landSwapError) write(w http.ResponseWriter) {
	writeJSON(w, e.status, map[string]any{"error": e.code, "message": e.msg, "overlap": e.overlap, "distanceM": e.distanceM})
}

func distText(m float64) string {
	if m < 1000 {
		return fmt.Sprintf("%.0f m", m)
	}
	return fmt.Sprintf("%.1f km", m/1000)
}

// landFit: share of boundary bid lying on the farmer's onboarding land (with
// the GPS allowance) and the distance between their middles. has=false when
// the farmer has no onboarding walk to compare with.
func landFit(ctx context.Context, q pgx.Tx, farmerID, bid string) (share, dist float64, has bool, err error) {
	err = q.QueryRow(ctx, `
		WITH u AS (SELECT ST_Union(geom::geometry) AS g FROM boundaries WHERE farmer_id=$1 AND status='active' AND purpose='onboarding')
		SELECT u.g IS NOT NULL,
			coalesce(ST_Area(ST_Intersection(n.geom::geometry, ST_Buffer(u.g::geography, $3)::geometry)::geography) / nullif(ST_Area(n.geom), 0), 0),
			coalesce(ST_Distance(ST_Centroid(n.geom::geometry)::geography, ST_Centroid(u.g)::geography), 0)
		FROM boundaries n, u WHERE n.id=$2`, farmerID, bid, landAllowanceM).Scan(&has, &share, &dist)
	if !has {
		share = 1
	}
	return min(share, 1), dist, has, err
}

func (s *Server) recordPreWalk(ctx context.Context, tx pgx.Tx, farmerID, bid string, in BoundaryIn, areaM2 float64, surveyorID string) error {
	share, dist, has, err := landFit(ctx, tx, farmerID, bid)
	if err != nil {
		return err
	}
	lc := in.LandChange
	if share >= LandFitMin {
		lc = nil // the registered land after all
	} else if lc == nil {
		return &landSwapError{409, "different_land", fmt.Sprintf(
			"Only %.0f%% of this walk is on the land checked at onboarding; the two are %s apart. Walk the registered land, or report a land change.",
			share*100, distText(dist)), share, dist}
	}

	var lastDecision *time.Time
	tx.QueryRow(ctx, `SELECT land_change_reviewed_at FROM plantations WHERE farmer_id=$1 FOR UPDATE`, farmerID).Scan(&lastDecision)
	if _, err := tx.Exec(ctx, `INSERT INTO plantations(farmer_id, pre_boundary_id, pre_overlap, pre_distance_m) VALUES ($1,$2,$3,$4)
		ON CONFLICT (farmer_id) DO UPDATE SET pre_boundary_id=EXCLUDED.pre_boundary_id, pre_overlap=EXCLUDED.pre_overlap,
			pre_distance_m=EXCLUDED.pre_distance_m, updated_at=now()`, farmerID, bid, nullIf(has, share), nullIf(has, dist)); err != nil {
		return err
	}
	detail := fmt.Sprintf("%.2f ac", areaM2/sqmPerAcre)
	if has {
		detail += fmt.Sprintf(" · %.0f%% on the onboarding land", share*100)
	}

	if lc == nil {
		note := strings.TrimSpace(in.LandNote)
		if share >= LandFitOK || !has {
			note = ""
		}
		if len([]rune(note)) > 300 {
			note = string([]rune(note)[:300])
		}
		if _, err := tx.Exec(ctx, `UPDATE plantations SET land_note=$2, land_change_status=NULL, land_change_reason='', land_change_survey_no='',
			land_change_area_ha=NULL, land_change_owner='', land_change_at=NULL, land_change_message='' WHERE farmer_id=$1`, farmerID, note); err != nil {
			return err
		}
		if note != "" {
			detail += " · note: " + note
		}
		return s.addPlanEvent(ctx, tx, farmerID, "pre_walk", detail, nil, &surveyorID)
	}

	lc.SurveyNo = strings.TrimSpace(lc.SurveyNo)
	if lc.OwnerType == "" {
		lc.OwnerType = "farmer"
	}
	switch {
	case landChangeReasons[lc.Reason] == "":
		return &landSwapError{400, "bad_land_change", "Choose why the farmer is changing land.", share, dist}
	case lc.SurveyNo == "" || len(lc.SurveyNo) > 40:
		return &landSwapError{400, "bad_land_change", "Enter the survey / Gat number of the new land.", share, dist}
	case lc.AreaHa <= 0 || lc.AreaHa > 100:
		return &landSwapError{400, "bad_land_change", "Enter the area of the new land from its 7/12.", share, dist}
	case lc.OwnerType != "farmer" && lc.OwnerType != "family":
		return &landSwapError{400, "bad_land_change", "Owner must be the farmer or a family member.", share, dist}
	}
	var photos int
	since := time.Time{}
	if lastDecision != nil {
		since = *lastDecision
	}
	tx.QueryRow(ctx, `SELECT count(*) FROM files WHERE farmer_id=$1 AND kind='land_change_record' AND created_at > $2`, farmerID, since).Scan(&photos)
	if photos == 0 {
		return &landSwapError{400, "photo", "Add a photo or PDF of the new land's 7/12 first.", share, dist}
	}
	if _, err := tx.Exec(ctx, `UPDATE plantations SET land_note='', land_change_status='waiting', land_change_reason=$2, land_change_survey_no=$3,
		land_change_area_ha=$4, land_change_owner=$5, land_change_at=now(), land_change_message='', land_change_reviewer_id=NULL WHERE farmer_id=$1`,
		farmerID, lc.Reason, lc.SurveyNo, lc.AreaHa, lc.OwnerType); err != nil {
		return err
	}
	s.addPlanEvent(ctx, tx, farmerID, "pre_walk", detail, nil, &surveyorID)
	return s.addPlanEvent(ctx, tx, farmerID, "land_change_reported", fmt.Sprintf("Gat %s instead of the onboarding land · %s · %s apart",
		lc.SurveyNo, landChangeReasons[lc.Reason], distText(dist)), nil, &surveyorID)
}

func nullIf(ok bool, v float64) *float64 {
	if !ok {
		return nil
	}
	return &v
}

// landChange: the land check and any land change of a farmer's plantation.
func (s *Server) landChange(ctx context.Context, farmerID string, pl *PlantOut) {
	var status *string
	lc := LandChangeOut{}
	err := s.db.QueryRow(ctx, `SELECT pl.pre_overlap, pl.pre_distance_m, pl.land_note, pl.land_change_status, pl.land_change_reason, pl.land_change_survey_no,
			pl.land_change_area_ha, pl.land_change_owner, pl.land_change_at, pl.land_change_message, `+reviewerLabelSQL+`, pl.land_change_reviewed_at
		FROM plantations pl LEFT JOIN users u ON u.id=pl.land_change_reviewer_id WHERE pl.farmer_id=$1`, farmerID).
		Scan(&pl.PreOverlap, &pl.PreDistanceM, &pl.LandNote, &status, &lc.Reason, &lc.SurveyNo, &lc.AreaHa, &lc.OwnerType, &lc.At, &lc.Message, &lc.Reviewer, &lc.ReviewedAt)
	if err != nil || status == nil {
		return
	}
	lc.Status = *status
	lc.ReasonText = landChangeReasons[lc.Reason]
	rows, _ := s.db.Query(ctx, `SELECT id, kind, land_id::text, mime, lat, lng, accuracy_m, sharpness, captured_at, created_at FROM files
		WHERE farmer_id=$1 AND kind='land_change_record' ORDER BY created_at DESC`, farmerID)
	lc.Photos, _ = pgx.CollectRows(rows, func(row pgx.CollectableRow) (DashFile, error) {
		var x DashFile
		err := row.Scan(&x.ID, &x.Kind, &x.LandID, &x.Mime, &x.Lat, &x.Lng, &x.Accuracy, &x.Sharpness, &x.CapturedAt, &x.UploadedAt)
		x.URL = "/v1/dash/files/" + x.ID
		return x, err
	})
	pl.LandChange = &lc
}

// landCheck: the line about the land in the plantation checks.
func landCheck(pl *PlantOut) *Check {
	if pl.PreOverlap == nil {
		return nil
	}
	share := *pl.PreOverlap
	approved := pl.LandChange != nil && pl.LandChange.Status == "approved"
	c := Check{OK: share >= LandFitOK || approved, Text: fmt.Sprintf("%.0f%% of the boundary before planting is on the land checked at onboarding", share*100)}
	if pl.PreDistanceM != nil && share < LandFitOK {
		c.Text += fmt.Sprintf(" (%s apart)", distText(*pl.PreDistanceM))
	}
	c.Text += "."
	if approved {
		c.Text += fmt.Sprintf(" Land change to Gat %s approved.", pl.LandChange.SurveyNo)
	} else if pl.LandNote != "" {
		c.Text += fmt.Sprintf(" Surveyor's note: %s", pl.LandNote)
	}
	return &c
}

// handleDashLandChangeReview: approve the new land, ask for more, or send the
// surveyor back to walk the onboarding land.
func (s *Server) handleDashLandChangeReview(w http.ResponseWriter, r *http.Request) {
	id := r.PathValue("id")
	u := dashUserFrom(r)
	ctx := r.Context()
	if ok, err := s.farmerInScope(ctx, u, id); err != nil || !ok {
		writeErr(w, 404, "not_found", "Farmer not found.")
		return
	}
	var in PlanReviewIn
	if !readJSON(w, r, &in) || !in.check(w) {
		return
	}
	_, _, stage, err := s.planStage(ctx, id)
	if err != nil {
		writeErr(w, 500, "db", err.Error())
		return
	}
	if stage != "land_review" && stage != "land_fix" {
		writeErr(w, 409, "nothing_to_decide", "No land change is waiting for a decision.")
		return
	}
	tx, err := s.db.Begin(ctx)
	if err != nil {
		writeErr(w, 500, "db", err.Error())
		return
	}
	defer tx.Rollback(ctx)
	q := `UPDATE plantations SET land_change_status=$2, land_change_message=$3, land_change_reviewer_id=$4, land_change_reviewed_at=now(), updated_at=now() WHERE farmer_id=$1`
	if in.Decision == "rejected" {
		// Back to walking the onboarding land; the refused walk is kept for the record.
		if _, err := tx.Exec(ctx, `UPDATE boundaries SET status='replaced' WHERE id=(SELECT pre_boundary_id FROM plantations WHERE farmer_id=$1)`, id); err != nil {
			writeErr(w, 500, "db", err.Error())
			return
		}
		q = `UPDATE plantations SET land_change_status=$2, land_change_message=$3, land_change_reviewer_id=$4, land_change_reviewed_at=now(),
			pre_boundary_id=NULL, pre_overlap=NULL, pre_distance_m=NULL, updated_at=now() WHERE farmer_id=$1`
	}
	if _, err := tx.Exec(ctx, q, id, in.Decision, in.Message, u.ID); err != nil {
		writeErr(w, 500, "db", err.Error())
		return
	}
	detail := "Land change " + decisionWords[in.Decision]
	if in.Message != "" {
		detail += ": " + in.Message
	}
	if err := s.addPlanEvent(ctx, tx, id, "land_change_"+in.Decision, detail, &u.ID, nil); err != nil {
		writeErr(w, 500, "db", err.Error())
		return
	}
	if err := tx.Commit(ctx); err != nil {
		writeErr(w, 500, "db", err.Error())
		return
	}
	s.writeDashFarmer(w, r, id)
}
