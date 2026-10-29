package api

import (
	"context"
	"log"
	"strings"
)

// docForFileKind maps an uploaded file to the document a reviewer checks.
func docForFileKind(kind string) string {
	switch kind {
	case "aadhaar_front", "aadhaar_back":
		return "aadhaar"
	case "land_record", "land_record_pdf":
		return "land_record"
	case "water_source":
		return "water_source"
	}
	return ""
}

// fileKindsForDoc is the reverse, for checking a fix really brought something new.
func fileKindsForDoc(doc string) []string {
	switch doc {
	case "aadhaar":
		return []string{"aadhaar_front", "aadhaar_back"}
	case "land_record":
		return []string{"land_record", "land_record_pdf"}
	case "water_source":
		return []string{"water_source"}
	}
	return nil
}

// markReuploaded runs after the surveyor sends a new photo, PDF or boundary
// walk. A reviewed document goes back to New for the reviewer, the history
// records it, and the matching review task on the phone is closed.
func (s *Server) markReuploaded(ctx context.Context, farmerID, doc, surveyorID string) {
	if doc == "" {
		return
	}
	tag, err := s.db.Exec(ctx, `UPDATE farmer_docs SET status='new', updated_at=now() WHERE farmer_id=$1 AND doc=$2 AND status<>'new'`, farmerID, doc)
	if err != nil {
		log.Printf("markReuploaded %s %s: %v", farmerID, doc, err)
		return
	}
	if tag.RowsAffected() > 0 {
		s.db.Exec(ctx, `INSERT INTO doc_events(farmer_id, doc, event, surveyor_id) VALUES ($1,$2,'reuploaded',$3)`, farmerID, doc, surveyorID)
	}
	s.db.Exec(ctx, `UPDATE action_items SET status='done', done_at=now() WHERE farmer_id=$1 AND kind=$2 AND status='open'`, farmerID, "fix_"+doc)
}

// fixHasNewEvidence: something for this document arrived after the task was (last) sent.
func (s *Server) fixHasNewEvidence(ctx context.Context, itemID, farmerID, kind string) (bool, error) {
	doc := strings.TrimPrefix(kind, "fix_")
	var ok bool
	var err error
	if doc == "boundary" {
		err = s.db.QueryRow(ctx, `SELECT EXISTS(SELECT 1 FROM boundaries b, action_items a
			WHERE a.id=$1 AND b.farmer_id=$2 AND b.status='active' AND b.purpose='onboarding' AND b.created_at > a.updated_at)`, itemID, farmerID).Scan(&ok)
	} else {
		err = s.db.QueryRow(ctx, `SELECT EXISTS(SELECT 1 FROM files x, action_items a
			WHERE a.id=$1 AND x.farmer_id=$2 AND x.kind = ANY($3) AND x.created_at > a.updated_at)`,
			itemID, farmerID, fileKindsForDoc(doc)).Scan(&ok)
	}
	return ok, err
}
