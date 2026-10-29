package api

import (
	"context"
	"crypto/rand"
	"encoding/json"
	"errors"
	"fmt"
	"log"
	"math"
	"math/big"
	"net/http"
	"regexp"
	"strings"
	"time"

	"github.com/jackc/pgx/v5"
)

// Payment and plantation, after onboarding is fully approved.
//
//   payment:    none → waiting → approved | rejected | pending (surveyor resends)
//   plantation: locked → pre_walk → delivery (short) → otp → planting →
//               post_walk → to_review → done | rejected | pending (surveyor fixes)
//
// The stage is derived from the data (view farmer_plan_status), so a step can
// only happen once the step before it is done.

const (
	TimberRate   = 5  // ₹ per timber sapling
	FruitRate    = 10 // ₹ per fruit sapling
	SpacingM     = 2.0
	sqmPerAcre   = 4046.8564224
	otpResendGap = 30 * time.Second
	plantOTPTTL  = 10 * time.Minute
)

// SaplingsFor: how many saplings fit the area at 2 m × 2 m, split by the
// timber share chosen at onboarding.
func SaplingsFor(acres float64, timberPercent int) (max, timber, fruit int) {
	if acres <= 0 {
		return 0, 0, 0
	}
	max = int(math.Floor(acres*sqmPerAcre/(SpacingM*SpacingM) + 1e-9))
	timberPercent = min(100, maxInt(0, timberPercent))
	timber = int(math.Round(float64(max) * float64(timberPercent) / 100))
	return max, timber, max - timber
}

func maxInt(a, b int) int {
	if a > b {
		return a
	}
	return b
}

func AmountDue(timber, fruit int) int { return timber*TimberRate + fruit*FruitRate }

type SaplingPlan struct {
	AreaAcres     float64 `json:"areaAcres"`
	AreaM2        float64 `json:"areaM2"`
	SpacingM      float64 `json:"spacingM"`
	MaxSaplings   int     `json:"maxSaplings"`
	TimberPercent int     `json:"timberPercent"`
	Timber        int     `json:"timber"`
	Fruit         int     `json:"fruit"`
	TimberSpecies string  `json:"timberSpecies"`
	FruitSpecies  string  `json:"fruitSpecies"`
	TimberRate    int     `json:"timberRate"`
	FruitRate     int     `json:"fruitRate"`
	AmountDue     int     `json:"amountDue"`
}

var speciesNames = map[string]string{"mahogany": "Mahogany", "teak": "Teak", "bamboo": "Bamboo", "mango": "Mango", "guava": "Guava",
	"custard_apple": "Custard apple", "jackfruit": "Jackfruit", "amla": "Amla", "lemon": "Lemon", "sandalwood": "Sandalwood", "melia_dubia": "Melia dubia"}

func speciesLabel(s string) string {
	if n, ok := speciesNames[s]; ok {
		return n
	}
	s = strings.ReplaceAll(s, "_", " ")
	if s == "" {
		return ""
	}
	return strings.ToUpper(s[:1]) + s[1:]
}

func (s *Server) saplingPlan(ctx context.Context, farmerID string) (SaplingPlan, error) {
	p := SaplingPlan{SpacingM: SpacingM, TimberRate: TimberRate, FruitRate: FruitRate, TimberPercent: 40}
	var details []byte
	if err := s.db.QueryRow(ctx, `SELECT coalesce((SELECT sum(register_acres) FROM lands WHERE farmer_id=f.id),0)::float8, f.farm_details FROM farmers f WHERE f.id=$1`, farmerID).
		Scan(&p.AreaAcres, &details); err != nil {
		return p, err
	}
	var d FarmDetails
	if len(details) > 0 && json.Unmarshal(details, &d) == nil {
		if d.TimberPercent+d.FruitPercent == 100 {
			p.TimberPercent = d.TimberPercent
		}
		if len(d.TimberSpecies) > 0 {
			p.TimberSpecies = speciesLabel(d.TimberSpecies[0])
		}
		if len(d.FruitSpecies) > 0 {
			p.FruitSpecies = speciesLabel(d.FruitSpecies[0])
		}
	}
	if p.TimberSpecies == "" {
		p.TimberSpecies = "Timber"
	}
	if p.FruitSpecies == "" {
		p.FruitSpecies = "Fruit"
	}
	p.AreaM2 = p.AreaAcres * sqmPerAcre
	p.MaxSaplings, p.Timber, p.Fruit = SaplingsFor(p.AreaAcres, p.TimberPercent)
	p.AmountDue = AmountDue(p.Timber, p.Fruit)
	return p, nil
}

func (s *Server) planStage(ctx context.Context, farmerID string) (onboardingOK bool, payment, plantation string, err error) {
	err = s.db.QueryRow(ctx, `SELECT onboarding_ok, payment, plantation FROM farmer_plan_status WHERE farmer_id=$1`, farmerID).Scan(&onboardingOK, &payment, &plantation)
	return
}

func (s *Server) addPlanEvent(ctx context.Context, tx pgx.Tx, farmerID, event, detail string, userID, surveyorID *string) error {
	var err error
	if tx != nil {
		_, err = tx.Exec(ctx, `INSERT INTO plan_events(farmer_id, event, detail, user_id, surveyor_id) VALUES ($1,$2,$3,$4,$5)`, farmerID, event, detail, userID, surveyorID)
	} else {
		_, err = s.db.Exec(ctx, `INSERT INTO plan_events(farmer_id, event, detail, user_id, surveyor_id) VALUES ($1,$2,$3,$4,$5)`, farmerID, event, detail, userID, surveyorID)
	}
	return err
}

// plantWalkAllowed: "" when this plantation walk fits the farmer's current step.
func (s *Server) plantWalkAllowed(ctx context.Context, farmerID, purpose string) string {
	_, pay, stage, err := s.planStage(ctx, farmerID)
	if err != nil {
		return "Farmer not found."
	}
	switch purpose {
	case "pre_plant":
		switch stage {
		case "pre_walk", "delivery", "short", "land_review", "land_fix":
			return ""
		case "locked":
			if pay != "approved" {
				return "The payment must be approved before walking the boundary for planting."
			}
			return "Onboarding must be approved first."
		}
		return "The saplings were already delivered; the boundary before planting can't change now."
	case "post_plant":
		switch stage {
		case "post_walk", "to_review", "rejected", "pending":
			return ""
		case "done":
			return "This plantation is already approved."
		}
		return "Finish planting before walking the boundary after planting."
	}
	return "Unknown walk."
}

func (s *Server) recordPlantWalk(ctx context.Context, tx pgx.Tx, farmerID, bid string, in BoundaryIn, areaM2 float64, surveyorID string) error {
	detail := fmt.Sprintf("%.2f ac", areaM2/sqmPerAcre)
	if in.Purpose == "pre_plant" {
		return s.recordPreWalk(ctx, tx, farmerID, bid, in, areaM2, surveyorID)
	}
	var was *string
	if err := tx.QueryRow(ctx, `SELECT review_status FROM plantations WHERE farmer_id=$1 FOR UPDATE`, farmerID).Scan(&was); err != nil {
		return err
	}
	if _, err := tx.Exec(ctx, `UPDATE plantations SET post_boundary_id=$2, updated_at=now(),
		review_status = CASE WHEN review_status IN ('rejected','pending') THEN NULL ELSE review_status END WHERE farmer_id=$1`, farmerID, bid); err != nil {
		return err
	}
	if was != nil && (*was == "rejected" || *was == "pending") {
		detail += " · sent again for review"
	}
	return s.addPlanEvent(ctx, tx, farmerID, "post_walk", detail, nil, &surveyorID)
}

// ---------- state shared by the phone and the dashboard ----------

type Check struct {
	OK   bool   `json:"ok"`
	Text string `json:"text"`
}

type Proof struct {
	ID         string    `json:"id"`
	URL        string    `json:"url"`
	UploadedAt time.Time `json:"uploadedAt"`
}

type PaymentOut struct {
	Timber      int        `json:"timber"`
	Fruit       int        `json:"fruit"`
	TimberRate  int        `json:"timberRate"`
	FruitRate   int        `json:"fruitRate"`
	AmountDue   int        `json:"amountDue"`
	AmountPaid  int        `json:"amountPaid"`
	Method      string     `json:"method"`
	Reference   string     `json:"reference"`
	PaidOn      string     `json:"paidOn"`
	Status      string     `json:"status"`
	Message     string     `json:"message"`
	Reviewer    string     `json:"reviewer"`
	ReviewedAt  *time.Time `json:"reviewedAt"`
	SubmittedAt time.Time  `json:"submittedAt"`
	Proofs      []Proof    `json:"proofs"`
	Checks      []Check    `json:"checks"`
}

type PlantOut struct {
	PreAreaM2     *float64       `json:"preAreaM2"`
	PreAt         *time.Time     `json:"preAt"`
	PostAreaM2    *float64       `json:"postAreaM2"`
	PostAt        *time.Time     `json:"postAt"`
	TimberCounted *int           `json:"timberCounted"`
	FruitCounted  *int           `json:"fruitCounted"`
	Healthy       bool           `json:"healthy"`
	RightSpecies  bool           `json:"rightSpecies"`
	Satisfied     bool           `json:"satisfied"`
	Short         bool           `json:"short"`
	DeliveryAt    *time.Time     `json:"deliveryAt"`
	OtpSentAt     *time.Time     `json:"otpSentAt"`
	OtpVerifiedAt *time.Time     `json:"otpVerifiedAt"`
	OtpDistanceM  *float64       `json:"otpDistanceM"`
	TimberPlanted *int           `json:"timberPlanted"`
	FruitPlanted  *int           `json:"fruitPlanted"`
	PlantedAt     *time.Time     `json:"plantedAt"`
	ReviewStatus  string         `json:"reviewStatus"`
	ReviewMessage string         `json:"reviewMessage"`
	Reviewer      string         `json:"reviewer"`
	ReviewedAt    *time.Time     `json:"reviewedAt"`
	Photos        []DashFile     `json:"photos"`
	Rings         map[string]any `json:"rings"`
	Checks        []Check        `json:"checks"`
	// Land swap check of the walk before planting.
	PreOverlap   *float64       `json:"preOverlap"`
	PreDistanceM *float64       `json:"preDistanceM"`
	LandNote     string         `json:"landNote"`
	LandChange   *LandChangeOut `json:"landChange"`
}

type PlanStep struct {
	Key    string `json:"key"`
	Label  string `json:"label"`
	State  string `json:"state"` // done | current | locked | stopped
	Detail string `json:"detail"`
}

type PlanEvent struct {
	Event  string    `json:"event"`
	Detail string    `json:"detail"`
	By     string    `json:"by"`
	At     time.Time `json:"at"`
}

type PlanState struct {
	FarmerID     string      `json:"farmerId"`
	FarmerMobile string      `json:"farmerMobile"`
	OnboardingOK bool        `json:"onboardingOk"`
	Payment      string      `json:"payment"`
	Plantation   string      `json:"plantation"`
	Plan         SaplingPlan `json:"plan"`
	Pay          *PaymentOut `json:"pay"`
	Plant        *PlantOut   `json:"plant"`
	Steps        []PlanStep  `json:"steps"`
	Events       []PlanEvent `json:"events"`
}

const (
	reviewerExpr     = `u.full_name || ', ' || CASE u.role WHEN 'admin' THEN 'Admin' WHEN 'state_lead' THEN 'State lead' WHEN 'ngo' THEN 'NGO' ELSE '' END`
	reviewerLabelSQL = `coalesce(` + reviewerExpr + `, '')`
)

func (s *Server) planState(ctx context.Context, farmerID string) (*PlanState, error) {
	st := &PlanState{FarmerID: farmerID}
	var err error
	if st.OnboardingOK, st.Payment, st.Plantation, err = s.planStage(ctx, farmerID); err != nil {
		return nil, err
	}
	if st.Plan, err = s.saplingPlan(ctx, farmerID); err != nil {
		return nil, err
	}
	s.db.QueryRow(ctx, `SELECT mobile FROM farmers WHERE id=$1`, farmerID).Scan(&st.FarmerMobile)

	var p PaymentOut
	var paidOn time.Time
	err = s.db.QueryRow(ctx, `SELECT p.timber_count, p.fruit_count, p.timber_rate, p.fruit_rate, p.amount_due, p.amount_paid, p.method, p.reference, p.paid_on,
			p.status, p.message, `+reviewerLabelSQL+`, p.reviewed_at, p.submitted_at
		FROM payments p LEFT JOIN users u ON u.id=p.reviewer_id WHERE p.farmer_id=$1`, farmerID).
		Scan(&p.Timber, &p.Fruit, &p.TimberRate, &p.FruitRate, &p.AmountDue, &p.AmountPaid, &p.Method, &p.Reference, &paidOn,
			&p.Status, &p.Message, &p.Reviewer, &p.ReviewedAt, &p.SubmittedAt)
	if err == nil {
		p.PaidOn = paidOn.Format("2006-01-02")
		rows, _ := s.db.Query(ctx, `SELECT id, created_at FROM files WHERE farmer_id=$1 AND kind='payment_proof' ORDER BY created_at`, farmerID)
		p.Proofs, _ = pgx.CollectRows(rows, func(row pgx.CollectableRow) (Proof, error) {
			var x Proof
			err := row.Scan(&x.ID, &x.UploadedAt)
			x.URL = "/v1/dash/files/" + x.ID
			return x, err
		})
		var dup int
		s.db.QueryRow(ctx, `SELECT count(*) FROM payments WHERE lower(reference)=lower($1) AND farmer_id<>$2`, p.Reference, farmerID).Scan(&dup)
		amount := Check{OK: p.AmountPaid == p.AmountDue, Text: fmt.Sprintf("Amount paid equals the amount due, %s.", rupees(p.AmountDue))}
		if p.AmountPaid < p.AmountDue {
			amount.Text = fmt.Sprintf("%s paid, %s short of the %s due.", rupees(p.AmountPaid), rupees(p.AmountDue-p.AmountPaid), rupees(p.AmountDue))
		} else if p.AmountPaid > p.AmountDue {
			amount.Text = fmt.Sprintf("%s paid, %s more than the %s due.", rupees(p.AmountPaid), rupees(p.AmountPaid-p.AmountDue), rupees(p.AmountDue))
		}
		ref := Check{OK: dup == 0, Text: "This reference number isn't used for any other farmer."}
		if dup > 0 {
			ref.Text = "This reference number is also used for another farmer."
			if dup > 1 {
				ref.Text = fmt.Sprintf("This reference number is also used for %d other farmers.", dup)
			}
		}
		total := p.Timber + p.Fruit
		fits := Check{OK: total <= st.Plan.MaxSaplings, Text: fmt.Sprintf("%s saplings fit %.2f ac at 2 m × 2 m spacing (most: %s).", num(total), st.Plan.AreaAcres, num(st.Plan.MaxSaplings))}
		proof := Check{OK: len(p.Proofs) > 0, Text: "No screenshot attached."}
		if n := len(p.Proofs); n == 1 {
			proof.Text = "1 screenshot attached."
		} else if n > 1 {
			proof.Text = fmt.Sprintf("%d screenshots attached; the latest is shown first.", n)
		}
		docs := Check{OK: st.OnboardingOK, Text: "All four onboarding documents are approved."}
		if !st.OnboardingOK {
			docs.Text = "Onboarding documents are not all approved."
		}
		p.Checks = []Check{amount, ref, fits, proof, docs}
		st.Pay = &p
	} else if !errors.Is(err, pgx.ErrNoRows) {
		return nil, err
	}

	var pl PlantOut
	var preID, postID *string
	var revStatus *string
	err = s.db.QueryRow(ctx, `SELECT pre_boundary_id::text, post_boundary_id::text, timber_counted, fruit_counted, healthy, right_species, satisfied, short,
			delivery_at, otp_sent_at, otp_verified_at, timber_planted, fruit_planted, planted_at, review_status, review_message, `+reviewerLabelSQL+`, reviewed_at,
			(SELECT ST_Distance(b.geom, ST_SetSRID(ST_MakePoint(pl.otp_lng, pl.otp_lat), 4326)::geography) FROM boundaries b WHERE b.id=pl.pre_boundary_id AND pl.otp_lat IS NOT NULL)
		FROM plantations pl LEFT JOIN users u ON u.id=pl.reviewer_id WHERE pl.farmer_id=$1`, farmerID).
		Scan(&preID, &postID, &pl.TimberCounted, &pl.FruitCounted, &pl.Healthy, &pl.RightSpecies, &pl.Satisfied, &pl.Short,
			&pl.DeliveryAt, &pl.OtpSentAt, &pl.OtpVerifiedAt, &pl.TimberPlanted, &pl.FruitPlanted, &pl.PlantedAt, &revStatus, &pl.ReviewMessage, &pl.Reviewer, &pl.ReviewedAt, &pl.OtpDistanceM)
	if err == nil {
		if revStatus != nil {
			pl.ReviewStatus = *revStatus
		}
		pl.Rings = map[string]any{}
		ring := func(id *string, key string) (*float64, *time.Time) {
			if id == nil {
				return nil, nil
			}
			var area float64
			var at time.Time
			var pts []byte
			if s.db.QueryRow(ctx, `SELECT area_m2, created_at, points FROM boundaries WHERE id=$1`, *id).Scan(&area, &at, &pts) != nil {
				return nil, nil
			}
			var points []PointIn
			json.Unmarshal(pts, &points)
			pl.Rings[key] = points
			return &area, &at
		}
		pl.PreAreaM2, pl.PreAt = ring(preID, "prePlant")
		pl.PostAreaM2, pl.PostAt = ring(postID, "postPlant")
		rows, _ := s.db.Query(ctx, `SELECT id, kind, land_id::text, mime, lat, lng, accuracy_m, sharpness, captured_at, created_at FROM files
			WHERE farmer_id=$1 AND kind IN ('sapling_delivery','plant_pits','plant_planted') ORDER BY created_at`, farmerID)
		pl.Photos, _ = pgx.CollectRows(rows, func(row pgx.CollectableRow) (DashFile, error) {
			var x DashFile
			err := row.Scan(&x.ID, &x.Kind, &x.LandID, &x.Mime, &x.Lat, &x.Lng, &x.Accuracy, &x.Sharpness, &x.CapturedAt, &x.UploadedAt)
			x.URL = "/v1/dash/files/" + x.ID
			return x, err
		})
		s.landChange(ctx, farmerID, &pl)
		pl.Checks = s.plantChecks(ctx, farmerID, &pl, st.Pay, preID, postID)
		if c := landCheck(&pl); c != nil {
			pl.Checks = append([]Check{*c}, pl.Checks...)
		}
		st.Plant = &pl
	} else if !errors.Is(err, pgx.ErrNoRows) {
		return nil, err
	}

	st.Steps = planSteps(st)
	rows, _ := s.db.Query(ctx, `SELECT e.event, e.detail,
			coalesce(`+reviewerExpr+`, sv.name || ', Surveyor', ''), e.created_at
		FROM plan_events e LEFT JOIN users u ON u.id=e.user_id LEFT JOIN surveyors sv ON sv.id=e.surveyor_id
		WHERE e.farmer_id=$1 ORDER BY e.created_at, e.id`, farmerID)
	st.Events, err = pgx.CollectRows(rows, func(row pgx.CollectableRow) (PlanEvent, error) {
		var x PlanEvent
		err := row.Scan(&x.Event, &x.Detail, &x.By, &x.At)
		return x, err
	})
	return st, err
}

func (s *Server) plantChecks(ctx context.Context, farmerID string, pl *PlantOut, pay *PaymentOut, preID, postID *string) []Check {
	var out []Check
	if pay != nil && pl.TimberCounted != nil {
		ok := *pl.TimberCounted == pay.Timber && *pl.FruitCounted == pay.Fruit
		out = append(out, Check{OK: ok, Text: fmt.Sprintf("Farmer counted %s timber and %s fruit; paid for %s and %s.",
			num(*pl.TimberCounted), num(*pl.FruitCounted), num(pay.Timber), num(pay.Fruit))})
	}
	if pl.OtpVerifiedAt != nil {
		t := "Farmer confirmed with the OTP sent to their registered mobile."
		ok := true
		if d := pl.OtpDistanceM; d != nil && *d < 1 {
			t += " Entered inside the field."
		} else if d != nil {
			t += fmt.Sprintf(" Entered %.0f m from the field.", *d)
			ok = *pl.OtpDistanceM <= 500
		}
		out = append(out, Check{OK: ok, Text: t})
	}
	if postID != nil && pl.PostAreaM2 != nil && pl.TimberPlanted != nil {
		planted := *pl.TimberPlanted + *pl.FruitPlanted
		need := float64(planted) * SpacingM * SpacingM
		out = append(out, Check{OK: *pl.PostAreaM2+1 >= need, Text: fmt.Sprintf("Planted area %.2f ac holds %s saplings at 2 m × 2 m (needs %.2f ac).", *pl.PostAreaM2/sqmPerAcre, num(planted), need/sqmPerAcre)})
	}
	if preID != nil && postID != nil {
		var share float64
		s.db.QueryRow(ctx, `SELECT coalesce(ST_Area(ST_Intersection(a.geom::geometry, b.geom::geometry)::geography) / nullif(ST_Area(b.geom), 0), 0)
			FROM boundaries a, boundaries b WHERE a.id=$1 AND b.id=$2`, *preID, *postID).Scan(&share)
		out = append(out, Check{OK: share >= 0.95, Text: fmt.Sprintf("%.0f%% of the planted area lies inside the boundary walked before planting.", share*100)})
	}
	if preID != nil {
		var total, inside, noLoc int
		s.db.QueryRow(ctx, `SELECT count(*), count(*) FILTER (WHERE x.lat IS NOT NULL AND ST_DWithin(b.geom, ST_SetSRID(ST_MakePoint(x.lng, x.lat), 4326)::geography, 100)),
				count(*) FILTER (WHERE x.lat IS NULL)
			FROM files x, boundaries b WHERE x.farmer_id=$1 AND x.kind IN ('plant_pits','plant_planted') AND b.id=$2`, farmerID, *preID).Scan(&total, &inside, &noLoc)
		if total > 0 {
			t := fmt.Sprintf("%d of %d planting photos were taken in the field.", inside, total)
			if noLoc > 0 {
				t += fmt.Sprintf(" %d have no location.", noLoc)
			}
			out = append(out, Check{OK: inside == total, Text: t})
		}
	}
	return out
}

func planSteps(st *PlanState) []PlanStep {
	order := []string{"onboarding", "payment", "approval", "pre_walk", "delivery", "planting", "post_walk", "review"}
	labels := map[string]string{"onboarding": "Onboarding approved", "payment": "Payment received", "approval": "Payment approved",
		"pre_walk": "Boundary before planting", "delivery": "Saplings counted, farmer's OTP", "planting": "Pit digging and planting",
		"post_walk": "Boundary after planting", "review": "Plantation approved"}
	reached := 0 // index of the current step
	stopped := false
	switch {
	case !st.OnboardingOK:
		reached = 0
	case st.Payment == "none":
		reached = 1
	case st.Payment != "approved":
		reached = 2
		stopped = st.Payment == "rejected" || st.Payment == "pending"
	default:
		switch st.Plantation {
		case "pre_walk", "land_review":
			reached = 3
		case "land_fix":
			reached, stopped = 3, true
		case "delivery", "otp":
			reached = 4
		case "short":
			reached, stopped = 4, true
		case "planting":
			reached = 5
		case "post_walk":
			reached = 6
		case "to_review":
			reached = 7
		case "rejected", "pending":
			reached, stopped = 7, true
		case "done":
			reached = 8
		}
	}
	var steps []PlanStep
	for i, k := range order {
		state := "locked"
		if i < reached {
			state = "done"
		} else if i == reached {
			state = "current"
			if stopped {
				state = "stopped"
			}
		}
		steps = append(steps, PlanStep{Key: k, Label: labels[k], State: state})
	}
	return steps
}

func rupees(n int) string { return "₹" + num(n) }

// num formats with Indian digit grouping: 1,23,456.
func num(n int) string {
	neg := n < 0
	if neg {
		n = -n
	}
	s := fmt.Sprint(n)
	if len(s) > 3 {
		head, tail := s[:len(s)-3], s[len(s)-3:]
		var parts []string
		for len(head) > 2 {
			parts = append([]string{head[len(head)-2:]}, parts...)
			head = head[:len(head)-2]
		}
		if head != "" {
			parts = append([]string{head}, parts...)
		}
		s = strings.Join(parts, ",") + "," + tail
	}
	if neg {
		return "-" + s
	}
	return s
}

// ---------- phone endpoints ----------

func (s *Server) ownedFarmer(w http.ResponseWriter, r *http.Request) (string, bool) {
	id := r.PathValue("id")
	if !uuidRe.MatchString(id) {
		writeErr(w, 404, "not_found", "Farmer not found.")
		return "", false
	}
	ok, err := s.ownsFarmer(r.Context(), surveyorFrom(r).ID, id)
	if err != nil {
		writeErr(w, 500, "db", err.Error())
		return "", false
	}
	if !ok {
		writeErr(w, 404, "not_found", "Farmer not found.")
		return "", false
	}
	return id, true
}

func (s *Server) writePlan(w http.ResponseWriter, ctx context.Context, farmerID string, status int, extra map[string]any) {
	st, err := s.planState(ctx, farmerID)
	if err != nil {
		writeErr(w, 500, "db", err.Error())
		return
	}
	if extra == nil {
		writeJSON(w, status, st)
		return
	}
	extra["state"] = st
	writeJSON(w, status, extra)
}

func (s *Server) handlePlanState(w http.ResponseWriter, r *http.Request) {
	id, ok := s.ownedFarmer(w, r)
	if !ok {
		return
	}
	s.writePlan(w, r.Context(), id, 200, nil)
}

var referenceRe = regexp.MustCompile(`^[A-Za-z0-9/-]{4,40}$`)

type PaymentIn struct {
	Timber     int    `json:"timber"`
	Fruit      int    `json:"fruit"`
	AmountPaid int    `json:"amountPaid"`
	Method     string `json:"method"`
	Reference  string `json:"reference"`
	PaidOn     string `json:"paidOn"` // YYYY-MM-DD
}

func fieldErr(w http.ResponseWriter, field, msg string) {
	writeJSON(w, 400, map[string]any{"error": "bad_input", "field": field, "message": msg})
}

func (s *Server) handlePutPayment(w http.ResponseWriter, r *http.Request) {
	id, ok := s.ownedFarmer(w, r)
	if !ok {
		return
	}
	var in PaymentIn
	if !readJSON(w, r, &in) {
		return
	}
	ctx := r.Context()
	okb, pay, _, err := s.planStage(ctx, id)
	if err != nil {
		writeErr(w, 500, "db", err.Error())
		return
	}
	if !okb {
		writeErr(w, 409, "onboarding_not_approved", "All four onboarding documents must be approved before collecting payment.")
		return
	}
	if pay == "approved" {
		writeErr(w, 409, "already_approved", "This payment is already approved.")
		return
	}
	plan, err := s.saplingPlan(ctx, id)
	if err != nil {
		writeErr(w, 500, "db", err.Error())
		return
	}
	in.Method = strings.ToLower(strings.TrimSpace(in.Method))
	in.Reference = strings.ToUpper(strings.ReplaceAll(strings.TrimSpace(in.Reference), " ", ""))
	switch {
	case in.Timber < 0 || in.Fruit < 0 || in.Timber+in.Fruit == 0:
		fieldErr(w, "saplings", "Choose how many timber and fruit saplings the farmer pays for.")
		return
	case in.Timber+in.Fruit > plan.MaxSaplings:
		fieldErr(w, "saplings", fmt.Sprintf("%s saplings won't fit at 2 m spacing. The most for this land is %s.", num(in.Timber+in.Fruit), num(plan.MaxSaplings)))
		return
	case in.Method != "upi" && in.Method != "bank":
		fieldErr(w, "method", "Choose UPI or bank transfer.")
		return
	case in.AmountPaid <= 0 || in.AmountPaid > 10_000_000:
		fieldErr(w, "amountPaid", "Enter the amount the farmer paid.")
		return
	case !referenceRe.MatchString(in.Reference):
		fieldErr(w, "reference", "Enter the UPI or bank reference number from the screenshot.")
		return
	}
	paidOn, err := time.Parse("2006-01-02", in.PaidOn)
	if err != nil || paidOn.After(time.Now().Add(36*time.Hour)) || paidOn.Year() < 2025 {
		fieldErr(w, "paidOn", "Enter the date of the payment.")
		return
	}
	// A screenshot uploaded after the last decision (any, the first time).
	var proofs int
	if err := s.db.QueryRow(ctx, `SELECT count(*) FROM files x WHERE x.farmer_id=$1 AND x.kind='payment_proof'
		AND x.created_at > coalesce((SELECT reviewed_at FROM payments WHERE farmer_id=$1 AND status IN ('rejected','pending')), '-infinity')`, id).Scan(&proofs); err != nil {
		writeErr(w, 500, "db", err.Error())
		return
	}
	if proofs == 0 {
		fieldErr(w, "proof", "Add the screenshot of the payment first. If it was sent back, add a new one.")
		return
	}
	due := AmountDue(in.Timber, in.Fruit)
	sv := surveyorFrom(r)
	tx, err := s.db.Begin(ctx)
	if err != nil {
		writeErr(w, 500, "db", err.Error())
		return
	}
	defer tx.Rollback(ctx)
	if _, err := tx.Exec(ctx, `INSERT INTO payments(farmer_id, surveyor_id, timber_count, fruit_count, timber_rate, fruit_rate, amount_due, amount_paid, method, reference, paid_on, status)
		VALUES ($1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$11,'waiting')
		ON CONFLICT (farmer_id) DO UPDATE SET surveyor_id=EXCLUDED.surveyor_id, timber_count=EXCLUDED.timber_count, fruit_count=EXCLUDED.fruit_count,
			amount_due=EXCLUDED.amount_due, amount_paid=EXCLUDED.amount_paid, method=EXCLUDED.method, reference=EXCLUDED.reference, paid_on=EXCLUDED.paid_on,
			status='waiting', submitted_at=now()`,
		id, sv.ID, in.Timber, in.Fruit, TimberRate, FruitRate, due, in.AmountPaid, in.Method, in.Reference, paidOn); err != nil {
		writeErr(w, 500, "db", err.Error())
		return
	}
	detail := fmt.Sprintf("%s by %s for %s timber + %s fruit (due %s)", rupees(in.AmountPaid), map[string]string{"upi": "UPI", "bank": "bank transfer"}[in.Method], num(in.Timber), num(in.Fruit), rupees(due))
	if err := s.addPlanEvent(ctx, tx, id, "payment_submitted", detail, nil, &sv.ID); err != nil {
		writeErr(w, 500, "db", err.Error())
		return
	}
	if err := tx.Commit(ctx); err != nil {
		writeErr(w, 500, "db", err.Error())
		return
	}
	s.writePlan(w, ctx, id, 200, nil)
}

type DeliveryIn struct {
	TimberCounted *int `json:"timberCounted"`
	FruitCounted  *int `json:"fruitCounted"`
	Healthy       bool `json:"healthy"`
	RightSpecies  bool `json:"rightSpecies"`
	Satisfied     bool `json:"satisfied"`
}

func (s *Server) handleDelivery(w http.ResponseWriter, r *http.Request) {
	id, ok := s.ownedFarmer(w, r)
	if !ok {
		return
	}
	var in DeliveryIn
	if !readJSON(w, r, &in) {
		return
	}
	ctx := r.Context()
	_, _, stage, err := s.planStage(ctx, id)
	if err != nil {
		writeErr(w, 500, "db", err.Error())
		return
	}
	if stage != "delivery" && stage != "short" {
		writeErr(w, 409, "wrong_step", "Saplings are counted after the boundary before planting is walked, and only once.")
		return
	}
	if in.TimberCounted == nil || in.FruitCounted == nil || *in.TimberCounted < 0 || *in.FruitCounted < 0 || *in.TimberCounted > 100000 || *in.FruitCounted > 100000 {
		fieldErr(w, "counted", "Enter how many saplings of each kind the farmer counted.")
		return
	}
	var photos int
	s.db.QueryRow(ctx, `SELECT count(*) FROM files x JOIN plantations pl ON pl.farmer_id=x.farmer_id JOIN boundaries b ON b.id=pl.pre_boundary_id
		WHERE x.farmer_id=$1 AND x.kind='sapling_delivery' AND x.created_at >= b.created_at`, id).Scan(&photos)
	if photos == 0 {
		fieldErr(w, "photo", "Take a photo of the saplings on the tractor first.")
		return
	}
	var timber, fruit int
	if err := s.db.QueryRow(ctx, `SELECT timber_count, fruit_count FROM payments WHERE farmer_id=$1`, id).Scan(&timber, &fruit); err != nil {
		writeErr(w, 500, "db", err.Error())
		return
	}
	matched := *in.TimberCounted == timber && *in.FruitCounted == fruit && in.Healthy && in.RightSpecies && in.Satisfied
	var deliveryAt *time.Time
	if matched {
		now := time.Now()
		deliveryAt = &now
	}
	tx, err := s.db.Begin(ctx)
	if err != nil {
		writeErr(w, 500, "db", err.Error())
		return
	}
	defer tx.Rollback(ctx)
	if _, err := tx.Exec(ctx, `UPDATE plantations SET timber_counted=$2, fruit_counted=$3, healthy=$4, right_species=$5, satisfied=$6, short=$7, delivery_at=$8, updated_at=now()
		WHERE farmer_id=$1`, id, *in.TimberCounted, *in.FruitCounted, in.Healthy, in.RightSpecies, in.Satisfied, !matched, deliveryAt); err != nil {
		writeErr(w, 500, "db", err.Error())
		return
	}
	sv := surveyorFrom(r)
	problems := []string{}
	if d := timber - *in.TimberCounted; d != 0 {
		problems = append(problems, shortText(d, "timber"))
	}
	if d := fruit - *in.FruitCounted; d != 0 {
		problems = append(problems, shortText(d, "fruit"))
	}
	if !in.Healthy {
		problems = append(problems, "saplings not healthy")
	}
	if !in.RightSpecies {
		problems = append(problems, "wrong species")
	}
	if !in.Satisfied {
		problems = append(problems, "farmer not satisfied")
	}
	event, detail := "delivery_ok", fmt.Sprintf("%s timber + %s fruit counted by the farmer", num(*in.TimberCounted), num(*in.FruitCounted))
	if !matched {
		event, detail = "delivery_short", strings.Join(problems, ", ")
	}
	if err := s.addPlanEvent(ctx, tx, id, event, detail, nil, &sv.ID); err != nil {
		writeErr(w, 500, "db", err.Error())
		return
	}
	if err := tx.Commit(ctx); err != nil {
		writeErr(w, 500, "db", err.Error())
		return
	}
	s.writePlan(w, ctx, id, 200, map[string]any{"matched": matched, "problems": problems})
}

func shortText(d int, kind string) string {
	if d > 0 {
		return fmt.Sprintf("%s %s short", num(d), kind)
	}
	return fmt.Sprintf("%s %s extra", num(-d), kind)
}

func (s *Server) handlePlantOTPRequest(w http.ResponseWriter, r *http.Request) {
	id, ok := s.ownedFarmer(w, r)
	if !ok {
		return
	}
	ctx := r.Context()
	_, _, stage, err := s.planStage(ctx, id)
	if err != nil {
		writeErr(w, 500, "db", err.Error())
		return
	}
	if stage != "otp" {
		writeErr(w, 409, "wrong_step", "The OTP is asked only after the farmer has counted all the saplings and is satisfied.")
		return
	}
	var sentAt *time.Time
	var mobile string
	var timber, fruit int
	if err := s.db.QueryRow(ctx, `SELECT pl.otp_sent_at, f.mobile, pl.timber_counted, pl.fruit_counted FROM plantations pl JOIN farmers f ON f.id=pl.farmer_id WHERE pl.farmer_id=$1`, id).
		Scan(&sentAt, &mobile, &timber, &fruit); err != nil {
		writeErr(w, 500, "db", err.Error())
		return
	}
	if sentAt != nil && time.Since(*sentAt) < otpResendGap {
		writeErr(w, 429, "wait", fmt.Sprintf("Wait %d seconds before sending a new code.", int((otpResendGap-time.Since(*sentAt)).Seconds())+1))
		return
	}
	n, _ := rand.Int(rand.Reader, big.NewInt(10000))
	code := fmt.Sprintf("%04d", n.Int64())
	if _, err := s.db.Exec(ctx, `UPDATE plantations SET otp_hash=$2, otp_expires_at=$3, otp_attempts=0, otp_sent_at=now() WHERE farmer_id=$1`,
		id, sha(id+":"+code), time.Now().Add(plantOTPTTL)); err != nil {
		writeErr(w, 500, "db", err.Error())
		return
	}
	sms := fmt.Sprintf("Idika: you received %s timber and %s fruit saplings for planting. If you agree, give code %s to the surveyor. Do not share it otherwise.", num(timber), num(fruit), code)
	log.Printf("planting OTP SMS to %s: %s", mobile, strings.Replace(sms, code, "****", 1))
	sv := surveyorFrom(r)
	s.addPlanEvent(ctx, nil, id, "otp_sent", "to "+mobile, nil, &sv.ID)
	resp := map[string]any{"sentTo": mobile, "resendAfter": int(otpResendGap.Seconds()), "sms": strings.Replace(sms, code, "••••", 1)}
	if s.cfg.DevMode {
		resp["devCode"] = code
	}
	writeJSON(w, 200, resp)
}

func (s *Server) handlePlantOTPVerify(w http.ResponseWriter, r *http.Request) {
	id, ok := s.ownedFarmer(w, r)
	if !ok {
		return
	}
	var in struct {
		Code string   `json:"code"`
		Lat  *float64 `json:"lat"`
		Lng  *float64 `json:"lng"`
	}
	if !readJSON(w, r, &in) {
		return
	}
	ctx := r.Context()
	_, _, stage, err := s.planStage(ctx, id)
	if err != nil {
		writeErr(w, 500, "db", err.Error())
		return
	}
	if stage != "otp" {
		writeErr(w, 409, "wrong_step", "There is no code to check at this step.")
		return
	}
	var hash *string
	var exp *time.Time
	var attempts int
	s.db.QueryRow(ctx, `SELECT otp_hash, otp_expires_at, otp_attempts FROM plantations WHERE farmer_id=$1`, id).Scan(&hash, &exp, &attempts)
	switch {
	case hash == nil || exp == nil:
		writeErr(w, 400, "no_code", "Send the code to the farmer first.")
		return
	case time.Now().After(*exp):
		writeErr(w, 400, "expired", "The code has expired. Send a new one.")
		return
	case attempts >= otpMaxAttempt:
		writeErr(w, 400, "too_many", "Too many wrong tries. Send a new code.")
		return
	case sha(id+":"+strings.TrimSpace(in.Code)) != *hash:
		s.db.Exec(ctx, `UPDATE plantations SET otp_attempts=otp_attempts+1 WHERE farmer_id=$1`, id)
		writeErr(w, 400, "wrong_code", "That code is not right. Ask the farmer to read it again.")
		return
	}
	if in.Lat != nil && (math.IsNaN(*in.Lat) || *in.Lat < -90 || *in.Lat > 90 || in.Lng == nil || *in.Lng < -180 || *in.Lng > 180) {
		in.Lat, in.Lng = nil, nil
	}
	if _, err := s.db.Exec(ctx, `UPDATE plantations SET otp_verified_at=now(), otp_hash=NULL, otp_lat=$2, otp_lng=$3, updated_at=now() WHERE farmer_id=$1`, id, in.Lat, in.Lng); err != nil {
		writeErr(w, 500, "db", err.Error())
		return
	}
	sv := surveyorFrom(r)
	s.addPlanEvent(ctx, nil, id, "otp_verified", "farmer confirmed the saplings", nil, &sv.ID)
	s.writePlan(w, ctx, id, 200, nil)
}

func (s *Server) handlePlanted(w http.ResponseWriter, r *http.Request) {
	id, ok := s.ownedFarmer(w, r)
	if !ok {
		return
	}
	var in struct {
		TimberPlanted *int `json:"timberPlanted"`
		FruitPlanted  *int `json:"fruitPlanted"`
	}
	if !readJSON(w, r, &in) {
		return
	}
	ctx := r.Context()
	_, _, stage, err := s.planStage(ctx, id)
	if err != nil {
		writeErr(w, 500, "db", err.Error())
		return
	}
	if stage != "planting" {
		writeErr(w, 409, "wrong_step", "Planting starts only after the farmer confirms the saplings with the OTP.")
		return
	}
	var timber, fruit int
	var verified time.Time
	s.db.QueryRow(ctx, `SELECT timber_counted, fruit_counted, otp_verified_at FROM plantations WHERE farmer_id=$1`, id).Scan(&timber, &fruit, &verified)
	if in.TimberPlanted == nil || in.FruitPlanted == nil || *in.TimberPlanted != timber || *in.FruitPlanted != fruit {
		fieldErr(w, "planted", fmt.Sprintf("Plant all %s timber and %s fruit saplings the farmer received, then mark planting done.", num(timber), num(fruit)))
		return
	}
	var pits, planted int
	s.db.QueryRow(ctx, `SELECT count(*) FILTER (WHERE kind='plant_pits'), count(*) FILTER (WHERE kind='plant_planted') FROM files
		WHERE farmer_id=$1 AND created_at >= $2`, id, verified).Scan(&pits, &planted)
	if pits == 0 || planted == 0 {
		fieldErr(w, "photos", "Take at least one photo of the dug pits and one of the planted saplings.")
		return
	}
	if _, err := s.db.Exec(ctx, `UPDATE plantations SET timber_planted=$2, fruit_planted=$3, planted_at=now(), updated_at=now() WHERE farmer_id=$1`,
		id, *in.TimberPlanted, *in.FruitPlanted); err != nil {
		writeErr(w, 500, "db", err.Error())
		return
	}
	sv := surveyorFrom(r)
	s.addPlanEvent(ctx, nil, id, "planted", fmt.Sprintf("%s saplings planted · %d pit and %d planting photos", num(timber+fruit), pits, planted), nil, &sv.ID)
	s.writePlan(w, ctx, id, 200, nil)
}

// markPlantPhoto: a new planting photo answers a plantation that was sent back.
func (s *Server) markPlantPhoto(ctx context.Context, farmerID, kind, surveyorID string) {
	if kind == "land_change_record" {
		tag, err := s.db.Exec(ctx, `UPDATE plantations SET land_change_status='waiting', land_change_at=now(), updated_at=now() WHERE farmer_id=$1 AND land_change_status='pending'`, farmerID)
		if err == nil && tag.RowsAffected() > 0 {
			s.addPlanEvent(ctx, nil, farmerID, "land_change_resubmitted", "new 7/12 of the new land sent for review", nil, &surveyorID)
		}
		return
	}
	if kind != "plant_pits" && kind != "plant_planted" {
		return
	}
	tag, err := s.db.Exec(ctx, `UPDATE plantations SET review_status=NULL, updated_at=now() WHERE farmer_id=$1 AND review_status IN ('rejected','pending') AND post_boundary_id IS NOT NULL`, farmerID)
	if err == nil && tag.RowsAffected() > 0 {
		s.addPlanEvent(ctx, nil, farmerID, "plant_resubmitted", "new photo sent for review", nil, &surveyorID)
	}
}

// planActionItems: the next payment or plantation step for each of the surveyor's farmers.
func (s *Server) planActionItems(ctx context.Context, surveyorID string) ([]ActionItem, error) {
	rows, err := s.db.Query(ctx, `
		SELECT f.id, f.full_name, f.code, v.name, ps.payment, ps.plantation,
			coalesce(p.message,''), coalesce(p.amount_due,0), coalesce(p.timber_count + p.fruit_count, 0), coalesce(pl.review_message,''),
			`+reviewerLabelSQL+`, coalesce(u2.full_name || ', ' || CASE u2.role WHEN 'admin' THEN 'Admin' WHEN 'state_lead' THEN 'State lead' WHEN 'ngo' THEN 'NGO' ELSE '' END, ''),
			greatest(p.reviewed_at, p.submitted_at, pl.updated_at, f.updated_at),
			coalesce(pl.timber_counted,0), coalesce(pl.fruit_counted,0), coalesce(p.timber_count,0), coalesce(p.fruit_count,0),
			coalesce(pl.land_change_status,''), coalesce(pl.land_change_message,''), coalesce(pl.land_change_survey_no,''),
			coalesce(u3.full_name || ', ' || CASE u3.role WHEN 'admin' THEN 'Admin' WHEN 'state_lead' THEN 'State lead' WHEN 'ngo' THEN 'NGO' ELSE '' END, '')
		FROM farmers f
		JOIN villages v ON v.id=f.village_id
		JOIN farmer_plan_status ps ON ps.farmer_id=f.id
		LEFT JOIN payments p ON p.farmer_id=f.id
		LEFT JOIN users u ON u.id=p.reviewer_id
		LEFT JOIN plantations pl ON pl.farmer_id=f.id
		LEFT JOIN users u2 ON u2.id=pl.reviewer_id
		LEFT JOIN users u3 ON u3.id=pl.land_change_reviewer_id
		WHERE f.surveyor_id=$1 AND ps.onboarding_ok AND ps.plantation <> 'done'`, surveyorID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var items []ActionItem
	for rows.Next() {
		var fid, name, code, village, pay, stage, payMsg, plantMsg, payReviewer, plantReviewer string
		var due, saplings, tc, fc, tp, fp int
		var at *time.Time
		var lcStatus, lcMsg, lcGat, lcReviewer string
		if err := rows.Scan(&fid, &name, &code, &village, &pay, &stage, &payMsg, &due, &saplings, &plantMsg, &payReviewer, &plantReviewer, &at, &tc, &fc, &tp, &fp,
			&lcStatus, &lcMsg, &lcGat, &lcReviewer); err != nil {
			return nil, err
		}
		it := ActionItem{FarmerID: fid, FarmerName: name, FarmerCode: code, Village: village}
		if at != nil {
			it.UpdatedAt = *at
		}
		switch {
		case pay == "none":
			it.Kind = "collect_payment"
			plan, err := s.saplingPlan(ctx, fid)
			if err == nil {
				it.Note = rupees(plan.AmountDue)
			}
		case pay == "waiting":
			it.Kind, it.Note = "payment_review", rupees(due)
		case pay == "rejected" || pay == "pending":
			it.Kind, it.Note, it.Decision, it.ReviewerName, it.Attention, it.Doc = "fix_payment", payMsg, pay, payReviewer, true, "payment"
		default:
			switch stage {
			case "pre_walk":
				it.Kind = "walk_pre_plant"
				if lcStatus == "rejected" {
					// The reviewer refused the new land: walk the onboarding land.
					it.Note, it.Decision, it.ReviewerName, it.Attention, it.Doc = lcMsg, "rejected", lcReviewer, true, "land_change"
				}
			case "land_review":
				it.Kind, it.Note = "land_change_review", "Gat "+lcGat
			case "land_fix":
				it.Kind, it.Note, it.Decision, it.ReviewerName, it.Attention, it.Doc = "fix_land_change", lcMsg, "pending", lcReviewer, true, "land_change"
			case "delivery":
				it.Kind, it.Note = "sapling_delivery", num(saplings)+" saplings"
			case "short":
				parts := []string{}
				if tp != tc {
					parts = append(parts, shortText(tp-tc, "timber"))
				}
				if fp != fc {
					parts = append(parts, shortText(fp-fc, "fruit"))
				}
				if len(parts) == 0 {
					parts = append(parts, "farmer not satisfied")
				}
				it.Kind, it.Note = "sapling_delivery", strings.Join(parts, ", ")
			case "otp":
				it.Kind, it.Note = "sapling_delivery", "ask the farmer for the OTP"
			case "planting":
				it.Kind, it.Note = "planting", num(tc+fc)+" saplings"
			case "post_walk":
				it.Kind = "walk_post_plant"
			case "to_review":
				it.Kind = "plantation_review"
			case "rejected", "pending":
				it.Kind, it.Note, it.Decision, it.ReviewerName, it.Attention, it.Doc = "fix_plantation", plantMsg, stage, plantReviewer, true, "plantation"
			default:
				continue
			}
		}
		it.ID = "plan-" + fid + "-" + it.Kind
		it.Lands = saplings
		items = append(items, it)
	}
	return items, rows.Err()
}
