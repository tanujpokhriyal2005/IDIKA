package api

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"os"
	"strings"
	"time"

	"fieldproof/backend/internal/geo"

	"github.com/jackc/pgx/v5"
)

type DocState struct {
	Status    string     `json:"status"`
	Message   string     `json:"message"`
	Reviewer  string     `json:"reviewer"`
	UpdatedAt *time.Time `json:"updatedAt"`
}

type DashFarmerRow struct {
	ID          string            `json:"id"`
	Code        string            `json:"code"`
	FullName    string            `json:"fullName"`
	Village     string            `json:"village"`
	Block       string            `json:"block"`
	District    string            `json:"district"`
	State       string            `json:"state"`
	Surveyor    string            `json:"surveyor"`
	NGO         string            `json:"ngo"`
	Acres       float64           `json:"acres"`
	Docs        map[string]string `json:"docs"`
	Payment     string            `json:"payment"`
	Plantation  string            `json:"plantation"`
	Saplings    int               `json:"saplings"`  // paid for, or what fits the land before payment
	Counted     int               `json:"counted"`   // counted by the farmer at delivery
	AmountDue   int               `json:"amountDue"`
	AmountPaid  int               `json:"amountPaid"`
	SubmittedAt *time.Time        `json:"submittedAt"`
	UpdatedAt   *time.Time        `json:"updatedAt"`
}

func (s *Server) handleDashFarmers(w http.ResponseWriter, r *http.Request) {
	f, bad := parseFarmerFilter(r.URL.Query())
	if bad != "" {
		writeErr(w, 400, "bad_filter", bad)
		return
	}
	u := dashUserFrom(r)
	q := farmerWhere(u, f)
	ctx := r.Context()
	var total int
	if err := s.db.QueryRow(ctx, `SELECT count(*) `+farmerFrom+` WHERE `+q.where(), q.args...).Scan(&total); err != nil {
		writeErr(w, 500, "db", err.Error())
		return
	}
	lim, off := q.arg(f.Size), q.arg((f.Page-1)*f.Size)
	rows, err := s.db.Query(ctx, `
		SELECT f.id, f.code, f.full_name, v.name, v.block, v.district, coalesce(v.state, sv.state, ''), sv.name, coalesce(ngo.full_name,''),
			(SELECT coalesce(sum(register_acres),0)::float8 FROM lands l WHERE l.farmer_id=f.id),
			ds.aadhaar, ds.land_record, ds.water_source, ds.boundary, ps.payment, ps.plantation,
			coalesce(p.timber_count + p.fruit_count, 0), coalesce(pl.timber_counted + pl.fruit_counted, 0), coalesce(p.amount_due, 0), coalesce(p.amount_paid, 0),
			f.submitted_at, greatest(f.submitted_at, p.submitted_at, p.reviewed_at, pl.updated_at)
		`+farmerFrom+`
		LEFT JOIN payments p ON p.farmer_id = f.id
		LEFT JOIN plantations pl ON pl.farmer_id = f.id
		WHERE `+q.where()+` ORDER BY `+farmerOrder(f.Sort)+` LIMIT `+lim+` OFFSET `+off, q.args...)
	if err != nil {
		writeErr(w, 500, "db", err.Error())
		return
	}
	items, err := pgx.CollectRows(rows, func(row pgx.CollectableRow) (DashFarmerRow, error) {
		var x DashFarmerRow
		var a, l, wa, b string
		err := row.Scan(&x.ID, &x.Code, &x.FullName, &x.Village, &x.Block, &x.District, &x.State, &x.Surveyor, &x.NGO, &x.Acres, &a, &l, &wa, &b, &x.Payment, &x.Plantation,
			&x.Saplings, &x.Counted, &x.AmountDue, &x.AmountPaid, &x.SubmittedAt, &x.UpdatedAt)
		x.Docs = map[string]string{"aadhaar": a, "land_record": l, "water_source": wa, "boundary": b}
		if x.Saplings == 0 {
			x.Saplings, _, _ = SaplingsFor(x.Acres, 40)
		}
		return x, err
	})
	if err != nil {
		writeErr(w, 500, "db", err.Error())
		return
	}
	writeJSON(w, 200, map[string]any{"total": total, "page": f.Page, "size": f.Size, "items": items})
}

// farmerInScope: the farmer exists, finished onboarding, and the signed-in person may see it.
func (s *Server) farmerInScope(ctx context.Context, u *DashUser, id string) (bool, error) {
	if !uuidRe.MatchString(id) {
		return false, nil
	}
	q := &qb{}
	q.and("f.id = ?", id)
	q.and("f.status = 'submitted'")
	scopeFarmers(u, q)
	var ok bool
	err := s.db.QueryRow(ctx, `SELECT EXISTS(SELECT 1 FROM farmers f JOIN surveyors sv ON sv.id=f.surveyor_id JOIN villages v ON v.id=f.village_id WHERE `+q.where()+`)`, q.args...).Scan(&ok)
	return ok, err
}

type DashFile struct {
	ID         string     `json:"id"`
	Kind       string     `json:"kind"`
	LandID     *string    `json:"landId"`
	Mime       string     `json:"mime"`
	Lat        *float64   `json:"lat"`
	Lng        *float64   `json:"lng"`
	Accuracy   *float64   `json:"accuracy"`
	Sharpness  *float64   `json:"sharpness"`
	CapturedAt *time.Time `json:"capturedAt"`
	UploadedAt time.Time  `json:"uploadedAt"`
	URL        string     `json:"url"`
}

type DocEvent struct {
	Doc     string    `json:"doc"`
	Event   string    `json:"event"`
	Message string    `json:"message"`
	By      string    `json:"by"`
	At      time.Time `json:"at"`
}

type DashFarmerDetail struct {
	*FarmerOut
	State      string              `json:"state"`
	Surveyor   map[string]string   `json:"surveyor"`
	NGO        map[string]string   `json:"ngo"`
	DocFiles   []DashFile          `json:"docFiles"`
	Docs       map[string]DocState `json:"docs"`
	History    []DocEvent          `json:"history"`
	Boundaries []DashBoundary      `json:"boundaries"`
	Plan       *PlanState          `json:"plan"`
}

type DashBoundary struct {
	BoundaryOut
	ClientAreaM2 *float64 `json:"clientAreaM2"`
}

func (s *Server) dashFarmerDetail(ctx context.Context, id string) (*DashFarmerDetail, error) {
	f, err := s.loadFarmer(ctx, id)
	if err != nil {
		return nil, err
	}
	d := &DashFarmerDetail{FarmerOut: f, Surveyor: map[string]string{}, NGO: map[string]string{}}
	var svID, svName, svPhone, ngoID, ngoName, state string
	err = s.db.QueryRow(ctx, `
		SELECT sv.id, sv.name, sv.phone, coalesce(v.state, sv.state, ''), coalesce(n.id::text,''), coalesce(n.full_name,'')
		FROM farmers f JOIN surveyors sv ON sv.id=f.surveyor_id JOIN villages v ON v.id=f.village_id LEFT JOIN users n ON n.id=sv.ngo_user_id WHERE f.id=$1`, id).
		Scan(&svID, &svName, &svPhone, &state, &ngoID, &ngoName)
	if err != nil {
		return nil, err
	}
	d.State = state
	d.Surveyor = map[string]string{"id": svID, "name": svName, "phone": svPhone}
	d.NGO = map[string]string{"id": ngoID, "name": ngoName}

	rows, _ := s.db.Query(ctx, `SELECT id, kind, land_id::text, mime, lat, lng, accuracy_m, sharpness, captured_at, created_at
		FROM files WHERE farmer_id=$1 ORDER BY created_at DESC`, id)
	d.DocFiles, err = pgx.CollectRows(rows, func(row pgx.CollectableRow) (DashFile, error) {
		var x DashFile
		err := row.Scan(&x.ID, &x.Kind, &x.LandID, &x.Mime, &x.Lat, &x.Lng, &x.Accuracy, &x.Sharpness, &x.CapturedAt, &x.UploadedAt)
		x.URL = "/v1/dash/files/" + x.ID
		return x, err
	})
	if err != nil {
		return nil, err
	}
	d.FarmerOut.Files = nil

	var st [4]string
	if err := s.db.QueryRow(ctx, `SELECT aadhaar, land_record, water_source, boundary FROM farmer_doc_status WHERE farmer_id=$1`, id).
		Scan(&st[0], &st[1], &st[2], &st[3]); err != nil {
		return nil, err
	}
	d.Docs = map[string]DocState{}
	for i, k := range docKeys {
		d.Docs[k] = DocState{Status: st[i]}
	}
	rows, _ = s.db.Query(ctx, `SELECT d.doc, d.message, coalesce(u.full_name,''), coalesce(u.role,''), d.updated_at
		FROM farmer_docs d LEFT JOIN users u ON u.id=d.reviewer_id WHERE d.farmer_id=$1`, id)
	for rows.Next() {
		var doc, msg, name, role string
		var at time.Time
		if err := rows.Scan(&doc, &msg, &name, &role, &at); err != nil {
			rows.Close()
			return nil, err
		}
		ds := d.Docs[doc]
		ds.Message, ds.UpdatedAt = msg, &at
		if name != "" {
			ds.Reviewer = name + ", " + roleNames[role]
		}
		d.Docs[doc] = ds
	}
	rows.Close()

	rows, _ = s.db.Query(ctx, `SELECT e.doc, e.event, e.message,
			coalesce(u.full_name || ', ' || CASE u.role WHEN 'admin' THEN 'Admin' WHEN 'state_lead' THEN 'State lead' WHEN 'ngo' THEN 'NGO' ELSE '' END, s.name || ', Surveyor', ''),
			e.created_at
		FROM doc_events e LEFT JOIN users u ON u.id=e.user_id LEFT JOIN surveyors s ON s.id=e.surveyor_id
		WHERE e.farmer_id=$1 ORDER BY e.created_at, e.id`, id)
	d.History, err = pgx.CollectRows(rows, func(row pgx.CollectableRow) (DocEvent, error) {
		var x DocEvent
		err := row.Scan(&x.Doc, &x.Event, &x.Message, &x.By, &x.At)
		return x, err
	})
	if err != nil {
		return nil, err
	}

	rows, _ = s.db.Query(ctx, `SELECT id, land_id::text, points, area_m2, point_count, accuracy_m, walked_m, finished_at, client_area_m2
		FROM boundaries WHERE farmer_id=$1 AND status='active' AND purpose='onboarding' ORDER BY created_at`, id)
	d.Boundaries, err = pgx.CollectRows(rows, func(row pgx.CollectableRow) (DashBoundary, error) {
		var b DashBoundary
		var pts []byte
		err := row.Scan(&b.ID, &b.LandID, &pts, &b.AreaM2, &b.PointCount, &b.AccuracyM, &b.WalkedM, &b.FinishedAt, &b.ClientAreaM2)
		if err == nil {
			err = json.Unmarshal(pts, &b.Points)
		}
		return b, err
	})
	if err != nil {
		return nil, err
	}
	d.FarmerOut.Boundaries = nil
	if d.Plan, err = s.planState(ctx, id); err != nil {
		return nil, err
	}
	return d, nil
}

func (s *Server) handleDashFarmer(w http.ResponseWriter, r *http.Request) {
	id := r.PathValue("id")
	ok, err := s.farmerInScope(r.Context(), dashUserFrom(r), id)
	if err != nil {
		writeErr(w, 500, "db", err.Error())
		return
	}
	if !ok {
		writeErr(w, 404, "not_found", "Farmer not found.")
		return
	}
	d, err := s.dashFarmerDetail(r.Context(), id)
	if err != nil {
		writeErr(w, 500, "db", err.Error())
		return
	}
	writeJSON(w, 200, d)
}

type ReviewIn struct {
	Decisions map[string]struct {
		Decision string `json:"decision"`
		Message  string `json:"message"`
	} `json:"decisions"`
}

const maxReviewMessage = 1000

func (s *Server) handleDashReview(w http.ResponseWriter, r *http.Request) {
	id := r.PathValue("id")
	u := dashUserFrom(r)
	ctx := r.Context()
	ok, err := s.farmerInScope(ctx, u, id)
	if err != nil {
		writeErr(w, 500, "db", err.Error())
		return
	}
	if !ok {
		writeErr(w, 404, "not_found", "Farmer not found.")
		return
	}
	var in ReviewIn
	if !readJSON(w, r, &in) {
		return
	}
	if len(in.Decisions) == 0 {
		writeErr(w, 400, "nothing_to_save", "Choose Approve, Reject or Pending for at least one document.")
		return
	}
	var st [4]string
	if err := s.db.QueryRow(ctx, `SELECT aadhaar, land_record, water_source, boundary FROM farmer_doc_status WHERE farmer_id=$1`, id).
		Scan(&st[0], &st[1], &st[2], &st[3]); err != nil {
		writeErr(w, 500, "db", err.Error())
		return
	}
	current := map[string]string{}
	for i, k := range docKeys {
		current[k] = st[i]
	}
	type dec struct{ doc, decision, message string }
	var decs []dec
	var problems []string
	for _, doc := range docKeys {
		d, ok := in.Decisions[doc]
		if !ok {
			continue
		}
		msg := strings.TrimSpace(d.Message)
		switch d.Decision {
		case "approved":
			if current[doc] == "missing" {
				problems = append(problems, docNames[doc]+": nothing was uploaded, so it can't be approved.")
				continue
			}
		case "rejected", "pending":
			if msg == "" {
				problems = append(problems, docNames[doc]+": write a message to the surveyor.")
				continue
			}
		default:
			problems = append(problems, docNames[doc]+": decision must be approved, rejected or pending.")
			continue
		}
		if len([]rune(msg)) > maxReviewMessage {
			problems = append(problems, fmt.Sprintf("%s: keep the message under %d characters.", docNames[doc], maxReviewMessage))
			continue
		}
		decs = append(decs, dec{doc, d.Decision, msg})
	}
	for k := range in.Decisions {
		if _, known := docNames[k]; !known {
			problems = append(problems, "Unknown document: "+k)
		}
	}
	if len(problems) > 0 {
		writeJSON(w, 400, map[string]any{"error": "review_incomplete", "message": strings.Join(problems, " "), "problems": problems})
		return
	}

	tx, err := s.db.Begin(ctx)
	if err != nil {
		writeErr(w, 500, "db", err.Error())
		return
	}
	defer tx.Rollback(ctx)
	var surveyorID string
	if err := tx.QueryRow(ctx, `SELECT surveyor_id FROM farmers WHERE id=$1 FOR UPDATE`, id).Scan(&surveyorID); err != nil {
		writeErr(w, 500, "db", err.Error())
		return
	}
	reviewer := u.FullName + ", " + roleNames[u.Role]
	tasks := []string{} // never null in JSON: the page lists them
	for _, d := range decs {
		msg := d.message
		if d.decision == "approved" {
			msg = "" // approvals carry no message
		}
		if _, err := tx.Exec(ctx, `
			INSERT INTO farmer_docs(farmer_id, doc, status, message, reviewer_id, updated_at) VALUES ($1,$2,$3,$4,$5,now())
			ON CONFLICT (farmer_id, doc) DO UPDATE SET status=EXCLUDED.status, message=EXCLUDED.message, reviewer_id=EXCLUDED.reviewer_id, updated_at=now()`,
			id, d.doc, d.decision, msg, u.ID); err != nil {
			writeErr(w, 500, "db", err.Error())
			return
		}
		if _, err := tx.Exec(ctx, `INSERT INTO doc_events(farmer_id, doc, event, message, user_id) VALUES ($1,$2,$3,$4,$5)`,
			id, d.doc, d.decision, msg, u.ID); err != nil {
			writeErr(w, 500, "db", err.Error())
			return
		}
		kind := "fix_" + d.doc
		if d.decision == "approved" {
			if _, err := tx.Exec(ctx, `UPDATE action_items SET status='done', done_at=now() WHERE farmer_id=$1 AND kind=$2 AND status='open'`, id, kind); err != nil {
				writeErr(w, 500, "db", err.Error())
				return
			}
			continue
		}
		// Only the surveyor who onboarded this farmer gets the task.
		if _, err := tx.Exec(ctx, `
			INSERT INTO action_items(surveyor_id, farmer_id, kind, note, doc, reviewer_name, decision, updated_at)
			VALUES ($1,$2,$3,$4,$5,$6,$7,now())
			ON CONFLICT (farmer_id, kind) WHERE status = 'open'
			DO UPDATE SET note=EXCLUDED.note, reviewer_name=EXCLUDED.reviewer_name, decision=EXCLUDED.decision, updated_at=now(), surveyor_id=EXCLUDED.surveyor_id`,
			surveyorID, id, kind, msg, d.doc, reviewer, d.decision); err != nil {
			writeErr(w, 500, "db", err.Error())
			return
		}
		tasks = append(tasks, d.doc)
	}
	if err := tx.Commit(ctx); err != nil {
		writeErr(w, 500, "db", err.Error())
		return
	}
	detail, err := s.dashFarmerDetail(ctx, id)
	if err != nil {
		writeErr(w, 500, "db", err.Error())
		return
	}
	writeJSON(w, 200, map[string]any{"farmer": detail, "tasksSent": tasks, "surveyor": detail.Surveyor["name"]})
}

func (s *Server) handleDashFile(w http.ResponseWriter, r *http.Request) {
	fid := r.PathValue("fid")
	if !uuidRe.MatchString(fid) {
		writeErr(w, 404, "not_found", "File not found.")
		return
	}
	var farmerID, path, mime string
	if err := s.db.QueryRow(r.Context(), `SELECT farmer_id, path, mime FROM files WHERE id=$1`, fid).Scan(&farmerID, &path, &mime); err != nil {
		writeErr(w, 404, "not_found", "File not found.")
		return
	}
	ok, err := s.farmerInScope(r.Context(), dashUserFrom(r), farmerID)
	if err != nil || !ok {
		writeErr(w, 404, "not_found", "File not found.")
		return
	}
	f, err := os.Open(path)
	if err != nil {
		writeErr(w, 404, "not_found", "The file is missing on the server.")
		return
	}
	defer f.Close()
	w.Header().Set("Content-Type", mime)
	w.Header().Set("Cache-Control", "private, max-age=3600")
	w.Header().Set("X-Content-Type-Options", "nosniff")
	st, _ := f.Stat()
	http.ServeContent(w, r, "", st.ModTime(), f)
}

func (s *Server) handleDashFarmerKML(w http.ResponseWriter, r *http.Request) {
	id := r.PathValue("id")
	ok, err := s.farmerInScope(r.Context(), dashUserFrom(r), id)
	if err != nil || !ok {
		writeErr(w, 404, "not_found", "Farmer not found.")
		return
	}
	if r.URL.Query().Get("walks") == "all" {
		s.writeAllWalksKML(w, r, id)
		return
	}
	name, doc, err := s.farmerKML(r.Context(), id, "")
	if err != nil {
		writeErr(w, 404, "no_boundary", "No boundary walked yet.")
		return
	}
	writeKML(w, name, doc)
}

// writeAllWalksKML: onboarding, before-planting and after-planting walks in one file.
func (s *Server) writeAllWalksKML(w http.ResponseWriter, r *http.Request, id string) {
	ctx := r.Context()
	f, parcels, err := s.farmerParcelsOnly(ctx, id, "")
	if err != nil {
		writeErr(w, 404, "not_found", "Farmer not found.")
		return
	}
	rows, err := s.db.Query(ctx, `SELECT b.purpose, b.points, coalesce(b.accuracy_m,0), coalesce(b.walked_m,0), b.started_at, b.finished_at
		FROM plantations pl JOIN boundaries b ON b.id IN (pl.pre_boundary_id, pl.post_boundary_id) WHERE pl.farmer_id=$1 ORDER BY b.created_at`, id)
	if err != nil {
		writeErr(w, 500, "db", err.Error())
		return
	}
	defer rows.Close()
	for rows.Next() {
		var purpose string
		var raw []byte
		var p geo.KMLParcel
		var started, finished *time.Time
		if err := rows.Scan(&purpose, &raw, &p.AccuracyM, &p.WalkedM, &started, &finished); err != nil {
			writeErr(w, 500, "db", err.Error())
			return
		}
		var pts []PointIn
		json.Unmarshal(raw, &pts)
		label := map[string]string{"pre_plant": "before planting", "post_plant": "after planting"}[purpose]
		p.Name, p.Ring, p.FarmerName, p.FarmerCode = f.Code+" · "+label, ringOf(pts), f.FullName, f.Code
		p.Village = f.Village.Name + ", " + f.Village.Block + ", " + f.Village.District
		if started != nil {
			p.StartedAt = *started
		}
		if finished != nil {
			p.FinishedAt = *finished
		}
		parcels = append(parcels, p)
	}
	if len(parcels) == 0 {
		writeErr(w, 404, "no_boundary", "No boundary walked yet.")
		return
	}
	writeKML(w, f.Code+"-all-walks", geo.BuildKML(f.FullName+" · "+f.Code, parcels))
}

const maxKMLFarmers = 5000

// handleDashFarmersKML: every walked boundary of the farmers matching the filters, in one file.
func (s *Server) handleDashFarmersKML(w http.ResponseWriter, r *http.Request) {
	f, bad := parseFarmerFilter(r.URL.Query())
	if bad != "" {
		writeErr(w, 400, "bad_filter", bad)
		return
	}
	q := farmerWhere(dashUserFrom(r), f)
	q.and("EXISTS (SELECT 1 FROM boundaries b WHERE b.farmer_id=f.id AND b.status='active' AND b.purpose='onboarding')")
	ctx := r.Context()
	rows, err := s.db.Query(ctx, `SELECT f.id `+farmerFrom+` WHERE `+q.where()+` ORDER BY f.number LIMIT `+fmt.Sprint(maxKMLFarmers+1), q.args...)
	if err != nil {
		writeErr(w, 500, "db", err.Error())
		return
	}
	ids, err := pgx.CollectRows(rows, pgx.RowTo[string])
	if err != nil {
		writeErr(w, 500, "db", err.Error())
		return
	}
	if len(ids) == 0 {
		writeErr(w, 404, "no_boundary", "None of these farmers has a walked boundary yet.")
		return
	}
	if len(ids) > maxKMLFarmers {
		writeErr(w, 400, "too_many", fmt.Sprintf("More than %d farmers match. Narrow the filters first.", maxKMLFarmers))
		return
	}
	var parcels []geo.KMLParcel
	for _, id := range ids {
		ps, err := s.farmerParcels(ctx, id)
		if err != nil && !errors.Is(err, pgx.ErrNoRows) {
			writeErr(w, 500, "db", err.Error())
			return
		}
		parcels = append(parcels, ps...)
	}
	writeKML(w, fmt.Sprintf("fieldproof-%d-farmers", len(ids)), geo.BuildKML(fmt.Sprintf("Fieldproof · %d farmers", len(ids)), parcels))
}
