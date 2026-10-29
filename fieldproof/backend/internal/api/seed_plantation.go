package api

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"log"
	"os"
	"path/filepath"
	"time"

	"fieldproof/backend/internal/geo"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"
)

// SeedPlantation puts the demo farmers whose onboarding is approved at every
// payment and plantation step, so each tab, filter and phone task can be seen.
// Runs once: it does nothing when any payment exists.
func SeedPlantation(ctx context.Context, db *pgxpool.Pool, uploadDir string) error {
	var n int
	if err := db.QueryRow(ctx, `SELECT count(*) FROM payments`).Scan(&n); err != nil || n > 0 {
		return err
	}
	rows, err := db.Query(ctx, `
		SELECT f.id, f.surveyor_id, coalesce((SELECT sum(register_acres) FROM lands WHERE farmer_id=f.id),0)::float8, f.farm_details,
			b.points, (SELECT max(updated_at) FROM farmer_docs d WHERE d.farmer_id=f.id), (SELECT reviewer_id FROM farmer_docs d WHERE d.farmer_id=f.id LIMIT 1)
		FROM farmers f JOIN farmer_plan_status ps ON ps.farmer_id=f.id
		JOIN LATERAL (SELECT points FROM boundaries WHERE farmer_id=f.id AND status='active' AND purpose='onboarding' ORDER BY created_at LIMIT 1) b ON true
		WHERE ps.onboarding_ok AND ps.payment='none'
		ORDER BY f.surveyor_id, f.number`)
	if err != nil {
		return err
	}
	type cand struct {
		id, surveyor, reviewer string
		acres                  float64
		details, points        []byte
		approved               time.Time
	}
	list, err := pgx.CollectRows(rows, func(row pgx.CollectableRow) (cand, error) {
		var c cand
		var rev *string
		err := row.Scan(&c.id, &c.surveyor, &c.acres, &c.details, &c.points, &c.approved, &rev)
		if rev != nil {
			c.reviewer = *rev
		}
		return c, err
	})
	if err != nil || len(list) == 0 {
		return err
	}
	log.Printf("seeding payment and plantation demo data for %d farmers", len(list))

	dir := filepath.Join(uploadDir, "demo")
	if err := os.MkdirAll(dir, 0o755); err != nil {
		return err
	}
	type sm struct {
		path, sum string
		size      int
	}
	samples := map[string]sm{}
	for _, kind := range []string{"payment_proof", "sapling_delivery", "plant_pits", "plant_planted"} {
		b := sampleImage(kind)
		p := filepath.Join(dir, kind+".jpg")
		if err := os.WriteFile(p, b, 0o644); err != nil {
			return err
		}
		h := sha256.Sum256(b)
		samples[kind] = sm{p, hex.EncodeToString(h[:]), len(b)}
	}

	// One farmer of each step in turn; the first one of every surveyor still has to pay.
	stages := []string{"none", "waiting", "rejected", "pre_walk", "delivery", "short", "planting", "post_walk",
		"to_review", "plant_pending", "done", "waiting", "to_review", "done", "pay_pending", "none"}
	tx, err := db.Begin(ctx)
	if err != nil {
		return err
	}
	defer tx.Rollback(ctx)
	now := time.Now()
	for i, c := range list {
		stage := stages[i%len(stages)]
		if stage == "none" {
			continue
		}
		var pts []PointIn
		if json.Unmarshal(c.points, &pts) != nil || len(pts) < 3 {
			continue
		}
		var d FarmDetails
		json.Unmarshal(c.details, &d)
		tp := 40
		if d.TimberPercent+d.FruitPercent == 100 {
			tp = d.TimberPercent
		}
		ring := make([]geo.LatLng, len(pts))
		var clat, clng float64
		for k, p := range pts {
			ring[k] = geo.LatLng{Lat: p.Lat, Lng: p.Lng}
			clat += p.Lat / float64(len(pts))
			clng += p.Lng / float64(len(pts))
		}
		// Pay for what fits both the 7/12 and the planted area (about 90% of the walk).
		acres := min(c.acres, geo.Area(ring)*0.85/sqmPerAcre)
		_, timber, fruit := SaplingsFor(acres, tp)
		if timber+fruit == 0 {
			continue
		}
		due := AmountDue(timber, fruit)

		base := c.approved.Add(6 * time.Hour)
		if base.Before(now.Add(-20 * 24 * time.Hour)) {
			base = now.Add(-20 * 24 * time.Hour)
		}
		step := min(24*time.Hour, now.Sub(base)/12)
		at := func(k int) time.Time { return base.Add(time.Duration(k) * step) }
		event := func(k int, ev, detail string, user bool) error {
			var uid, sid *string
			if user && c.reviewer != "" {
				uid = &c.reviewer
			} else if !user {
				sid = &c.surveyor
			}
			_, err := tx.Exec(ctx, `INSERT INTO plan_events(farmer_id, event, detail, user_id, surveyor_id, created_at) VALUES ($1,$2,$3,$4,$5,$6)`, c.id, ev, detail, uid, sid, at(k))
			return err
		}
		file := func(k int, kind string, inField bool) error {
			s := samples[kind]
			lat, lng := clat, clng
			if !inField {
				lat, lng = clat+0.01, clng+0.01
			}
			_, err := tx.Exec(ctx, `INSERT INTO files(id, farmer_id, kind, path, mime, size_bytes, sha256, lat, lng, accuracy_m, captured_at, sharpness, created_at)
				VALUES (gen_random_uuid(),$1,$2,$3,'image/jpeg',$4,$5,$6,$7,5,$8,0.8,$8)`, c.id, kind, s.path, s.size, s.sum, lat, lng, at(k))
			return err
		}
		walk := func(k int, purpose string, scale float64) (string, float64, error) {
			r := make([]geo.LatLng, len(ring))
			p := make([]PointIn, len(ring))
			for j, q := range ring {
				r[j] = geo.LatLng{Lat: clat + (q.Lat-clat)*scale, Lng: clng + (q.Lng-clng)*scale}
				p[j] = PointIn{Lat: r[j].Lat, Lng: r[j].Lng, Acc: 4, T: at(k).Add(time.Duration(j) * time.Minute).UnixMilli()}
			}
			pj, _ := json.Marshal(p)
			area := geo.Area(r)
			var id string
			err := tx.QueryRow(ctx, `INSERT INTO boundaries(id, farmer_id, geom, points, point_count, area_m2, client_area_m2, walked_m, accuracy_m, started_at, finished_at, created_at, purpose)
				VALUES (gen_random_uuid(),$1,ST_GeogFromText($2),$3,$4,$5,$5,$6,4,$7,$7,$7,$8) RETURNING id`,
				c.id, polygonWKT(r), pj, len(r), area, geo.Perimeter(r), at(k), purpose).Scan(&id)
			return id, area, err
		}

		// Payment
		if err := file(0, "payment_proof", true); err != nil {
			return err
		}
		paid, status, msg := due, "approved", ""
		switch stage {
		case "waiting":
			status = "waiting"
		case "rejected":
			status, paid, msg = "rejected", due-500, fmt.Sprintf("The screenshot shows %s but %s is due. Please collect the remaining ₹500 and send a new screenshot.", rupees(due-500), rupees(due))
		case "pay_pending":
			status, msg = "pending", "The reference number in the screenshot can't be read. Please send a clearer screenshot."
		}
		ref := fmt.Sprintf("UPI%09d", 400000000+i*7919)
		var reviewer *string
		var reviewedAt *time.Time
		if status != "waiting" && c.reviewer != "" {
			reviewer, reviewedAt = &c.reviewer, ptrTime(at(1))
		}
		if _, err := tx.Exec(ctx, `INSERT INTO payments(farmer_id, surveyor_id, timber_count, fruit_count, amount_due, amount_paid, method, reference, paid_on, status, message, reviewer_id, reviewed_at, submitted_at)
			VALUES ($1,$2,$3,$4,$5,$6,'upi',$7,$8,$9,$10,$11,$12,$13)`,
			c.id, c.surveyor, timber, fruit, due, paid, ref, at(0), status, msg, reviewer, reviewedAt, at(0)); err != nil {
			return err
		}
		if err := event(0, "payment_submitted", fmt.Sprintf("%s by UPI for %s timber + %s fruit (due %s)", rupees(paid), num(timber), num(fruit), rupees(due)), false); err != nil {
			return err
		}
		if status != "waiting" {
			detail := "Payment " + decisionWords[status]
			if msg != "" {
				detail += ": " + msg
			}
			if err := event(1, "payment_"+status, detail, true); err != nil {
				return err
			}
		}
		if status != "approved" || stage == "pre_walk" {
			continue
		}

		// Plantation
		preID, preArea, err := walk(2, "pre_plant", 0.97)
		if err != nil {
			return err
		}
		if _, err := tx.Exec(ctx, `INSERT INTO plantations(farmer_id, pre_boundary_id, updated_at) VALUES ($1,$2,$3)`, c.id, preID, at(2)); err != nil {
			return err
		}
		event(2, "pre_walk", fmt.Sprintf("%.2f ac", preArea/sqmPerAcre), false)
		if stage == "delivery" {
			continue
		}
		if err := file(3, "sapling_delivery", true); err != nil {
			return err
		}
		if stage == "short" {
			tc := max(0, timber-40)
			_, err := tx.Exec(ctx, `UPDATE plantations SET timber_counted=$2, fruit_counted=$3, healthy=true, right_species=true, satisfied=false, short=true, updated_at=$4 WHERE farmer_id=$1`,
				c.id, tc, fruit, at(3))
			if err != nil {
				return err
			}
			event(3, "delivery_short", shortText(timber-tc, "timber")+", farmer not satisfied", false)
			continue
		}
		if _, err := tx.Exec(ctx, `UPDATE plantations SET timber_counted=$2, fruit_counted=$3, healthy=true, right_species=true, satisfied=true, short=false,
				delivery_at=$4, otp_sent_at=$4, otp_verified_at=$4, otp_lat=$5, otp_lng=$6, updated_at=$4 WHERE farmer_id=$1`,
			c.id, timber, fruit, at(3), clat, clng); err != nil {
			return err
		}
		event(3, "delivery_ok", fmt.Sprintf("%s timber + %s fruit counted by the farmer", num(timber), num(fruit)), false)
		event(3, "otp_verified", "farmer confirmed the saplings", false)
		if stage == "planting" {
			continue
		}
		file(4, "plant_pits", true)
		file(4, "plant_planted", true)
		file(4, "plant_planted", stage != "plant_pending") // one photo far from the field when it was sent back
		if _, err := tx.Exec(ctx, `UPDATE plantations SET timber_planted=$2, fruit_planted=$3, planted_at=$4, updated_at=$4 WHERE farmer_id=$1`, c.id, timber, fruit, at(4)); err != nil {
			return err
		}
		event(4, "planted", fmt.Sprintf("%s saplings planted · 1 pit and 2 planting photos", num(timber+fruit)), false)
		if stage == "post_walk" {
			continue
		}
		postID, postArea, err := walk(5, "post_plant", 0.94)
		if err != nil {
			return err
		}
		if _, err := tx.Exec(ctx, `UPDATE plantations SET post_boundary_id=$2, updated_at=$3 WHERE farmer_id=$1`, c.id, postID, at(5)); err != nil {
			return err
		}
		event(5, "post_walk", fmt.Sprintf("%.2f ac", postArea/sqmPerAcre), false)
		if stage == "to_review" || c.reviewer == "" {
			continue
		}
		dec, rmsg := "approved", ""
		if stage == "plant_pending" {
			dec, rmsg = "pending", "One planting photo was taken about 1 km from the field. Please take a new photo of the planted saplings in this field."
		}
		if _, err := tx.Exec(ctx, `UPDATE plantations SET review_status=$2, review_message=$3, reviewer_id=$4, reviewed_at=$5, updated_at=$5 WHERE farmer_id=$1`,
			c.id, dec, rmsg, c.reviewer, at(6)); err != nil {
			return err
		}
		detail := "Plantation " + decisionWords[dec]
		if rmsg != "" {
			detail += ": " + rmsg
		}
		event(6, "plantation_"+dec, detail, true)
	}
	return tx.Commit(ctx)
}

func ptrTime(t time.Time) *time.Time { return &t }
