package api

import (
	"fmt"
	"net/http"
	"strings"
)

type PlanReviewIn struct {
	Decision string `json:"decision"`
	Message  string `json:"message"`
}

func (in *PlanReviewIn) check(w http.ResponseWriter) bool {
	in.Message = strings.TrimSpace(in.Message)
	switch in.Decision {
	case "approved":
		in.Message = "" // approvals carry no message
	case "rejected", "pending":
		if in.Message == "" {
			writeErr(w, 400, "message_required", "Write a message to the surveyor saying what to fix.")
			return false
		}
	default:
		writeErr(w, 400, "bad_decision", "Decision must be approved, rejected or pending.")
		return false
	}
	if len([]rune(in.Message)) > maxReviewMessage {
		writeErr(w, 400, "message_too_long", fmt.Sprintf("Keep the message under %d characters.", maxReviewMessage))
		return false
	}
	return true
}

var decisionWords = map[string]string{"approved": "approved", "rejected": "rejected", "pending": "marked pending"}

// handleDashPaymentReview: NGO, state lead or admin approves, rejects or
// holds the payment. Approval opens the plantation steps on the phone.
func (s *Server) handleDashPaymentReview(w http.ResponseWriter, r *http.Request) {
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
	tx, err := s.db.Begin(ctx)
	if err != nil {
		writeErr(w, 500, "db", err.Error())
		return
	}
	defer tx.Rollback(ctx)
	var status string
	if err := tx.QueryRow(ctx, `SELECT status FROM payments WHERE farmer_id=$1 FOR UPDATE`, id).Scan(&status); err != nil {
		writeErr(w, 409, "no_payment", "The surveyor hasn't sent a payment for this farmer yet.")
		return
	}
	if status == "approved" {
		writeErr(w, 409, "already_approved", "This payment is already approved and can't be changed.")
		return
	}
	if in.Decision == "approved" {
		var ok bool
		tx.QueryRow(ctx, `SELECT onboarding_ok FROM farmer_plan_status WHERE farmer_id=$1`, id).Scan(&ok)
		if !ok {
			writeErr(w, 409, "onboarding_not_approved", "Approve all four onboarding documents before approving the payment.")
			return
		}
	}
	if _, err := tx.Exec(ctx, `UPDATE payments SET status=$2, message=$3, reviewer_id=$4, reviewed_at=now() WHERE farmer_id=$1`, id, in.Decision, in.Message, u.ID); err != nil {
		writeErr(w, 500, "db", err.Error())
		return
	}
	detail := "Payment " + decisionWords[in.Decision]
	if in.Message != "" {
		detail += ": " + in.Message
	}
	if err := s.addPlanEvent(ctx, tx, id, "payment_"+in.Decision, detail, &u.ID, nil); err != nil {
		writeErr(w, 500, "db", err.Error())
		return
	}
	if err := tx.Commit(ctx); err != nil {
		writeErr(w, 500, "db", err.Error())
		return
	}
	s.writeDashFarmer(w, r, id)
}

// handleDashPlantationReview: the last step, once the boundary after planting is walked.
func (s *Server) handleDashPlantationReview(w http.ResponseWriter, r *http.Request) {
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
	switch stage {
	case "to_review", "rejected", "pending":
	case "done":
		writeErr(w, 409, "already_approved", "This plantation is already approved.")
		return
	default:
		writeErr(w, 409, "not_ready", "The plantation can be reviewed after the boundary after planting is walked.")
		return
	}
	tx, err := s.db.Begin(ctx)
	if err != nil {
		writeErr(w, 500, "db", err.Error())
		return
	}
	defer tx.Rollback(ctx)
	if _, err := tx.Exec(ctx, `UPDATE plantations SET review_status=$2, review_message=$3, reviewer_id=$4, reviewed_at=now(), updated_at=now() WHERE farmer_id=$1`,
		id, in.Decision, in.Message, u.ID); err != nil {
		writeErr(w, 500, "db", err.Error())
		return
	}
	detail := "Plantation " + decisionWords[in.Decision]
	if in.Message != "" {
		detail += ": " + in.Message
	}
	if err := s.addPlanEvent(ctx, tx, id, "plantation_"+in.Decision, detail, &u.ID, nil); err != nil {
		writeErr(w, 500, "db", err.Error())
		return
	}
	if err := tx.Commit(ctx); err != nil {
		writeErr(w, 500, "db", err.Error())
		return
	}
	s.writeDashFarmer(w, r, id)
}

func (s *Server) writeDashFarmer(w http.ResponseWriter, r *http.Request, id string) {
	d, err := s.dashFarmerDetail(r.Context(), id)
	if err != nil {
		writeErr(w, 500, "db", err.Error())
		return
	}
	writeJSON(w, 200, map[string]any{"farmer": d, "surveyor": d.Surveyor["name"]})
}
