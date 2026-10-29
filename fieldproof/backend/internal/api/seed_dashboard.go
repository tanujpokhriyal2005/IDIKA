package api

import (
	"bytes"
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"image"
	"image/color"
	"image/jpeg"
	"log"
	"math"
	"math/rand"
	"os"
	"path/filepath"
	"strings"
	"time"

	"fieldproof/backend/internal/geo"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"
)

// DemoPassword signs in every demo dashboard login made by SeedDashboard
// (meera.mh, arvind.mp, greenearth.hingoli, vasundhara.trust, narmada.seva).
// Only used when SEED_DEMO=true; real logins are issued from the dashboard.
const DemoPassword = "fieldproof-demo"

// SeedDashboard adds a demo hierarchy (two states, three NGOs, more surveyors),
// sample documents and boundaries, and some reviews, so every page, filter and
// chart of the dashboard has something to show. Runs once: skipped when any
// state lead exists.
func SeedDashboard(ctx context.Context, db *pgxpool.Pool, uploadDir string) error {
	var n int
	if err := db.QueryRow(ctx, `SELECT count(*) FROM users WHERE role='state_lead'`).Scan(&n); err != nil || n > 0 {
		return err
	}
	var adminID string
	if err := db.QueryRow(ctx, `SELECT id FROM users WHERE role='admin' ORDER BY created_at LIMIT 1`).Scan(&adminID); err != nil {
		return fmt.Errorf("seed dashboard needs an admin: %w", err)
	}
	log.Print("seeding dashboard demo data")
	tx, err := db.Begin(ctx)
	if err != nil {
		return err
	}
	defer tx.Rollback(ctx)
	pw := HashPassword(DemoPassword)
	addUser := func(username, role, name, state, district string, ngo, createdBy *string) (string, error) {
		var id string
		err := tx.QueryRow(ctx, `INSERT INTO users(username, password_hash, role, full_name, state, district, ngo_id, created_by, must_change_password, last_login_at)
			VALUES ($1,$2,$3,$4,$5,$6,$7,$8,false, now() - interval '1 day') RETURNING id`, username, pw, role, name, state, district, ngo, createdBy).Scan(&id)
		return id, err
	}
	meera, err := addUser("meera.mh", "state_lead", "Meera Kulkarni", "Maharashtra", "", nil, &adminID)
	if err != nil {
		return err
	}
	arvind, err := addUser("arvind.mp", "state_lead", "Arvind Singh", "Madhya Pradesh", "", nil, &adminID)
	if err != nil {
		return err
	}
	green, err := addUser("greenearth.hingoli", "ngo", "Green Earth Hingoli", "Maharashtra", "Hingoli", nil, &meera)
	if err != nil {
		return err
	}
	vasu, err := addUser("vasundhara.trust", "ngo", "Vasundhara Trust", "Maharashtra", "Hingoli", nil, &meera)
	if err != nil {
		return err
	}
	narmada, err := addUser("narmada.seva", "ngo", "Narmada Gram Seva", "Madhya Pradesh", "Sehore", nil, &arvind)
	if err != nil {
		return err
	}

	// Surveyors added before the dashboard existed go under Green Earth Hingoli.
	rows, _ := tx.Query(ctx, `SELECT id, name FROM surveyors WHERE ngo_user_id IS NULL AND NOT EXISTS (SELECT 1 FROM users u WHERE u.surveyor_id=surveyors.id)`)
	type sv struct{ id, name string }
	old, err := pgx.CollectRows(rows, func(row pgx.CollectableRow) (sv, error) {
		var x sv
		err := row.Scan(&x.id, &x.name)
		return x, err
	})
	if err != nil {
		return err
	}
	used := map[string]bool{}
	for _, x := range old {
		base := strings.Map(func(r rune) rune {
			if r >= 'a' && r <= 'z' || r >= '0' && r <= '9' || r == '.' {
				return r
			}
			if r == ' ' {
				return '.'
			}
			return -1
		}, strings.ToLower(strings.TrimSpace(x.name)))
		base = strings.Trim(base, ".")
		if len(base) < 3 {
			base = "surveyor"
		}
		u := base
		for i := 2; used[u]; i++ {
			u = fmt.Sprintf("%s%d", base, i)
		}
		used[u] = true
		if _, err := tx.Exec(ctx, `UPDATE surveyors SET ngo_user_id=$1, state='Maharashtra', last_seen_at=now() - interval '1 hour' WHERE id=$2`, green, x.id); err != nil {
			return err
		}
		if _, err := tx.Exec(ctx, `INSERT INTO users(username, password_hash, role, full_name, state, ngo_id, surveyor_id, created_by, must_change_password)
			VALUES ($1,$2,'surveyor',$3,'Maharashtra',$4,$5,$6,true)`, u, pw, x.name, green, x.id, green); err != nil {
			return err
		}
	}

	village := func(district, block, name string) (int, error) {
		var id int
		err := tx.QueryRow(ctx, `INSERT INTO villages(district, block, name) VALUES ($1,$2,$3)
			ON CONFLICT (district, block, name) DO UPDATE SET name=EXCLUDED.name RETURNING id`, district, block, name).Scan(&id)
		return id, err
	}
	addSurveyor := func(phone, name, username, district string, blocks []string, ngo, state string, lastSeen *time.Time) (string, error) {
		var id string
		if err := tx.QueryRow(ctx, `INSERT INTO surveyors(phone, name, district, home_block, ngo_user_id, state, last_seen_at) VALUES ($1,$2,$3,$4,$5,$6,$7)
			ON CONFLICT (phone) DO UPDATE SET ngo_user_id=EXCLUDED.ngo_user_id, state=EXCLUDED.state RETURNING id`,
			phone, name, district, blocks[0], ngo, state, lastSeen).Scan(&id); err != nil {
			return "", err
		}
		for _, b := range blocks {
			tx.Exec(ctx, `INSERT INTO surveyor_blocks VALUES ($1,$2,$3) ON CONFLICT DO NOTHING`, id, district, b)
		}
		_, err := tx.Exec(ctx, `INSERT INTO users(username, password_hash, role, full_name, mobile, state, district, blocks, ngo_id, surveyor_id, created_by, must_change_password)
			VALUES ($1,$2,'surveyor',$3,$4,$5,$6,$7,$8,$9,$8,true) ON CONFLICT DO NOTHING`, username, pw, name, phone, state, district, blocks, ngo, id)
		return id, err
	}
	t := func(d time.Duration) *time.Time { x := time.Now().Add(-d); return &x }
	priya, err := addSurveyor("9822077001", "Priya Shinde", "priya.shinde", "Hingoli", []string{"Hingoli"}, vasu, "Maharashtra", t(3*time.Hour))
	if err != nil {
		return err
	}
	arjun, err := addSurveyor("9822077002", "Arjun Verma", "arjun.verma", "Sehore", []string{"Sehore", "Ichhawar"}, narmada, "Madhya Pradesh", t(9*24*time.Hour))
	if err != nil {
		return err
	}
	if _, err := addSurveyor("9822077003", "Sachin More", "sachin.more", "Hingoli", []string{"Sengaon"}, green, "Maharashtra", nil); err != nil {
		return err
	}

	rng := rand.New(rand.NewSource(11))
	var nextNumber int
	if err := tx.QueryRow(ctx, `SELECT nextval('farmer_number_seq')`).Scan(&nextNumber); err != nil {
		return err
	}
	type demoFarmer struct {
		id, surveyor string
		base         [2]float64
		idx          int
	}
	var demo []demoFarmer
	addFarmers := func(surveyor string, villages []int, count int, base [2]float64) error {
		for i := 0; i < count; i++ {
			name := firstNames[rng.Intn(len(firstNames))] + " " + lastNames[rng.Intn(len(lastNames))]
			ha := 0.4 + float64(rng.Intn(120))/100
			acres := math.Max(0.25, MaxRegisterAcres(ha))
			num := nextNumber
			nextNumber++
			var id string
			err := tx.QueryRow(ctx, `INSERT INTO farmers(id, number, code, surveyor_id, full_name, mobile, whatsapp_same, whatsapp, village_id, tree_interest,
				aadhaar_name_match, aadhaar_consent, status, submitted_at, farm_details)
				VALUES (gen_random_uuid(), $1, $2, $3, $4, $5, true, $5, $6, true, true, true, 'submitted', now() - make_interval(days => $7, hours => $8),
				'{"waterSources":["borewell"],"timberSpecies":["mahogany"],"fruitSpecies":["mango"],"timberPercent":40,"fruitPercent":60,"landUse":["farming"]}')
				RETURNING id`, num, FarmerCode(num, name), surveyor, name, fmt.Sprintf("9%09d", rng.Intn(1e9)), villages[rng.Intn(len(villages))],
				rng.Intn(80), rng.Intn(24)).Scan(&id)
			if err != nil {
				return err
			}
			tx.Exec(ctx, `INSERT INTO farmer_numbers(number, surveyor_id) VALUES ($1,$2)`, num, surveyor)
			if _, err := tx.Exec(ctx, `INSERT INTO lands(id, farmer_id, idx, survey_no, area_ha, register_acres, owner_type, records_consent)
				VALUES (gen_random_uuid(), $1, 0, $2, $3, $4, 'farmer', true)`, id, fmt.Sprintf("%d/%d", 10+rng.Intn(400), 1+rng.Intn(4)), ha, acres); err != nil {
				return err
			}
			demo = append(demo, demoFarmer{id, surveyor, base, len(demo)})
		}
		return nil
	}
	var hv []int
	for _, name := range []string{"Narsi Namdev", "Liamba", "Khandala", "Bhatsawangi"} {
		id, err := village("Hingoli", "Hingoli", name)
		if err != nil {
			return err
		}
		hv = append(hv, id)
	}
	var sv2 []int
	for _, x := range [][2]string{{"Sehore", "Sehore"}, {"Sehore", "Shyampur"}, {"Ichhawar", "Ichhawar"}, {"Ichhawar", "Amlaha"}} {
		id, err := village("Sehore", x[0], x[1])
		if err != nil {
			return err
		}
		sv2 = append(sv2, id)
	}
	if err := addFarmers(priya, hv, 18, [2]float64{19.7170, 77.1470}); err != nil {
		return err
	}
	if err := addFarmers(arjun, sv2, 14, [2]float64{23.2030, 77.0840}); err != nil {
		return err
	}
	tx.Exec(ctx, `SELECT setval('farmer_number_seq', $1)`, nextNumber)

	// Seeded farmers of the first surveyor: give them onboarding dates and
	// documents for about a third of them.
	if _, err := tx.Exec(ctx, `UPDATE farmers SET submitted_at = now() - make_interval(days => abs(hashtext(id::text)) % 85, hours => abs(hashtext(code)) % 24)
		WHERE status='submitted' AND submitted_at IS NULL`); err != nil {
		return err
	}
	rows, _ = tx.Query(ctx, `SELECT f.id, f.surveyor_id FROM farmers f JOIN surveyors s ON s.id=f.surveyor_id
		WHERE f.status='submitted' AND s.ngo_user_id=$1 AND NOT EXISTS (SELECT 1 FROM files x WHERE x.farmer_id=f.id)
		AND NOT EXISTS (SELECT 1 FROM boundaries b WHERE b.farmer_id=f.id) ORDER BY f.number LIMIT 45`, green)
	tanujFarmers, err := pgx.CollectRows(rows, func(row pgx.CollectableRow) (demoFarmer, error) {
		var x demoFarmer
		err := row.Scan(&x.id, &x.surveyor)
		x.base = [2]float64{19.5850, 77.2090}
		return x, err
	})
	if err != nil {
		return err
	}
	for i := range tanujFarmers {
		tanujFarmers[i].idx = i
	}
	for i := range demo {
		if demo[i].surveyor == arjun {
			demo[i].idx -= 18
		}
	}
	all := append(tanujFarmers, demo...)

	// Sample photos: one generated image per kind, shared by all demo rows.
	dir := filepath.Join(uploadDir, "demo")
	if err := os.MkdirAll(dir, 0o755); err != nil {
		return err
	}
	sample := map[string]struct{ path, sum string; size int }{}
	for _, kind := range []string{"aadhaar_front", "aadhaar_back", "land_record", "water_source"} {
		b := sampleImage(kind)
		p := filepath.Join(dir, kind+".jpg")
		if err := os.WriteFile(p, b, 0o644); err != nil {
			return err
		}
		h := sha256.Sum256(b)
		sample[kind] = struct{ path, sum string; size int }{p, hex.EncodeToString(h[:]), len(b)}
	}

	messages := map[string]string{
		"aadhaar":      "The photo is blurry and the name can't be read. Please retake it in good light.",
		"land_record":  "The 7/12 photo is cut off at the bottom; the owner name is not visible. Please photograph the full page again.",
		"water_source": "This looks like a neighbour's well. Please photograph the farmer's own water source.",
		"boundary":     "The walked area is much larger than the 7/12. Please walk the boundary again with the farmer.",
	}
	reviewers := map[string][2]string{ // surveyor -> reviewer id, reviewer label
		priya: {meera, "Meera Kulkarni, State lead"},
		arjun: {arvind, "Arvind Singh, State lead"},
	}
	tanujFixes := 0
	for i, f := range all {
		var submitted time.Time
		var land string
		if err := tx.QueryRow(ctx, `SELECT f.submitted_at, l.id FROM farmers f JOIN lands l ON l.farmer_id=f.id WHERE f.id=$1 LIMIT 1`, f.id).Scan(&submitted, &land); err != nil {
			return err
		}
		lat0, lng0 := f.base[0], f.base[1]
		for _, kind := range []string{"aadhaar_front", "aadhaar_back", "land_record", "water_source"} {
			sm := sample[kind]
			var landID *string
			if kind == "land_record" {
				landID = &land
			}
			if _, err := tx.Exec(ctx, `INSERT INTO files(id, farmer_id, land_id, kind, path, mime, size_bytes, sha256, lat, lng, accuracy_m, captured_at, sharpness, created_at)
				VALUES (gen_random_uuid(),$1,$2,$3,$4,'image/jpeg',$5,$6,$7,$8,6,$9,0.8,$9)`,
				f.id, landID, kind, sm.path, sm.size, sm.sum, lat0+float64(f.idx%8)*0.003, lng0+float64(f.idx/8)*0.003, submitted.Add(-2*time.Hour)); err != nil {
				return err
			}
		}
		// Boundary: an irregular five-sided field, about 0.6-1 ha, on a grid so none overlap.
		clat, clng := lat0+float64(f.idx%8)*0.003, lng0+float64(f.idx/8)*0.003
		s := 0.00035 + float64(i%5)*0.00004
		ring := []geo.LatLng{{Lat: clat - s, Lng: clng - s}, {Lat: clat - s*0.9, Lng: clng + s*1.1}, {Lat: clat + s*0.3, Lng: clng + s*1.25},
			{Lat: clat + s*1.05, Lng: clng + s*0.2}, {Lat: clat + s*0.6, Lng: clng - s*0.95}}
		pts := make([]PointIn, len(ring))
		for k, p := range ring {
			pts[k] = PointIn{Lat: p.Lat, Lng: p.Lng, Acc: 4, T: submitted.Add(time.Duration(k-6) * time.Minute).UnixMilli()}
		}
		ptsJSON, _ := json.Marshal(pts)
		area := geo.Area(ring)
		if _, err := tx.Exec(ctx, `INSERT INTO boundaries(id, farmer_id, land_id, geom, points, point_count, area_m2, client_area_m2, walked_m, accuracy_m, started_at, finished_at, created_at)
			VALUES (gen_random_uuid(),$1,$2,ST_GeogFromText($3),$4,$5,$6,$6,$7,4,$8,$9,$9)`,
			f.id, land, polygonWKT(ring), ptsJSON, len(ring), area, geo.Perimeter(ring), submitted.Add(-90*time.Minute), submitted.Add(-80*time.Minute)); err != nil {
			return err
		}

		// Reviews: about 40% fully approved, 20% with one document sent back, the rest waiting.
		reviewer, label := green, "Green Earth Hingoli, NGO"
		if rv, ok := reviewers[f.surveyor]; ok {
			reviewer, label = rv[0], rv[1]
		}
		at := submitted.Add(time.Duration(20+rng.Intn(40)) * time.Hour)
		if at.After(time.Now()) {
			continue
		}
		switch r := i % 10; {
		case r < 4:
			for _, d := range docKeys {
				if err := reviewDoc(ctx, tx, f.id, d, "approved", "", reviewer, at); err != nil {
					return err
				}
			}
		case r < 6:
			bad := docKeys[(i/10)%4]
			if f.surveyor != priya && f.surveyor != arjun {
				if tanujFixes >= 3 {
					continue
				}
				tanujFixes++
			}
			decision := "pending"
			if i%3 == 0 {
				decision = "rejected"
			}
			for _, d := range docKeys {
				dec, msg := "approved", ""
				if d == bad {
					dec, msg = decision, messages[d]
				}
				if err := reviewDoc(ctx, tx, f.id, d, dec, msg, reviewer, at); err != nil {
					return err
				}
				if dec != "approved" {
					if _, err := tx.Exec(ctx, `INSERT INTO action_items(surveyor_id, farmer_id, kind, note, doc, reviewer_name, decision, created_at, updated_at)
						VALUES ($1,$2,$3,$4,$5,$6,$7,$8,$8) ON CONFLICT DO NOTHING`, f.surveyor, f.id, "fix_"+d, msg, d, label, dec, at); err != nil {
						return err
					}
				}
			}
		}
	}
	return tx.Commit(ctx)
}

func reviewDoc(ctx context.Context, tx pgx.Tx, farmerID, doc, decision, msg, reviewer string, at time.Time) error {
	if _, err := tx.Exec(ctx, `INSERT INTO farmer_docs(farmer_id, doc, status, message, reviewer_id, updated_at) VALUES ($1,$2,$3,$4,$5,$6)
		ON CONFLICT (farmer_id, doc) DO UPDATE SET status=EXCLUDED.status, message=EXCLUDED.message, reviewer_id=EXCLUDED.reviewer_id, updated_at=EXCLUDED.updated_at`,
		farmerID, doc, decision, msg, reviewer, at); err != nil {
		return err
	}
	_, err := tx.Exec(ctx, `INSERT INTO doc_events(farmer_id, doc, event, message, user_id, created_at) VALUES ($1,$2,$3,$4,$5,$6)`, farmerID, doc, decision, msg, reviewer, at)
	return err
}

// sampleImage draws a simple placeholder for a document photo (no real data).
func sampleImage(kind string) []byte {
	w, h := 960, 640
	if kind == "land_record" {
		w, h = 720, 960
	}
	img := image.NewRGBA(image.Rect(0, 0, w, h))
	fill := func(x0, y0, x1, y1 int, c color.RGBA) {
		for y := max(0, y0); y < min(h, y1); y++ {
			for x := max(0, x0); x < min(w, x1); x++ {
				img.SetRGBA(x, y, c)
			}
		}
	}
	switch kind {
	case "aadhaar_front", "aadhaar_back":
		fill(0, 0, w, h, color.RGBA{58, 52, 44, 255})
		fill(60, 70, w-60, h-70, color.RGBA{246, 244, 236, 255})
		fill(60, 70, w-60, 150, color.RGBA{232, 120, 40, 255})
		fill(60, h-150, w-60, h-70, color.RGBA{30, 120, 60, 255})
		if kind == "aadhaar_front" {
			fill(100, 190, 290, 420, color.RGBA{190, 190, 196, 255})
		}
		for i := 0; i < 4; i++ {
			fill(330, 200+i*55, w-120-i*60, 228+i*55, color.RGBA{170, 170, 176, 255})
		}
		fill(260, h-230, w-260, h-185, color.RGBA{120, 120, 126, 255})
	case "land_record":
		fill(0, 0, w, h, color.RGBA{86, 74, 60, 255})
		fill(40, 40, w-40, h-40, color.RGBA{250, 248, 240, 255})
		fill(80, 80, w-80, 130, color.RGBA{150, 150, 150, 255})
		for i := 0; i < 14; i++ {
			fill(80, 170+i*50, w-80-(i%3)*90, 186+i*50, color.RGBA{185, 185, 190, 255})
		}
		for i := 0; i < 4; i++ {
			fill(80+i*140, 170, 82+i*140, h-90, color.RGBA{210, 210, 214, 255})
		}
	case "payment_proof": // a payment app screenshot
		w, h = 540, 1080
		img = image.NewRGBA(image.Rect(0, 0, w, h))
		fill(0, 0, w, h, color.RGBA{248, 248, 250, 255})
		fill(0, 0, w, 90, color.RGBA{60, 40, 120, 255})
		for y := 0; y < h; y++ {
			for x := 0; x < w; x++ {
				dx, dy := float64(x-w/2), float64(y-300)
				if dx*dx+dy*dy < 80*80 {
					img.SetRGBA(x, y, color.RGBA{30, 150, 80, 255})
				}
			}
		}
		fill(140, 430, w-140, 480, color.RGBA{40, 40, 46, 255})
		for i := 0; i < 5; i++ {
			fill(60, 560+i*70, w-60-(i%2)*120, 584+i*70, color.RGBA{190, 190, 198, 255})
		}
	case "sapling_delivery", "plant_pits", "plant_planted":
		fill(0, 0, w, h/3, color.RGBA{160, 200, 232, 255})
		fill(0, h/3, w, h, color.RGBA{150, 112, 76, 255})
		if kind == "sapling_delivery" {
			fill(180, h/3+40, w-180, h-120, color.RGBA{180, 60, 40, 255}) // trailer
		}
		for r := 0; r < 5; r++ {
			for c := 0; c < 9; c++ {
				cx, cy := 120+c*90, h/3+90+r*70
				col := color.RGBA{50, 130, 50, 255}
				rad := 22
				if kind == "plant_pits" {
					col, rad = color.RGBA{70, 50, 34, 255}, 18
				} else if kind == "sapling_delivery" && (c < 2 || c > 6 || r > 3) {
					continue
				}
				for y := cy - rad; y <= cy+rad; y++ {
					for x := cx - rad; x <= cx+rad; x++ {
						if (x-cx)*(x-cx)+(y-cy)*(y-cy) <= rad*rad && x >= 0 && y >= 0 && x < w && y < h {
							img.SetRGBA(x, y, col)
						}
					}
				}
			}
		}
	default: // water source: sky, field, well
		fill(0, 0, w, h*2/5, color.RGBA{150, 196, 230, 255})
		fill(0, h*2/5, w, h, color.RGBA{112, 140, 70, 255})
		for y := 0; y < h; y++ {
			for x := 0; x < w; x++ {
				dx, dy := float64(x-w/2), float64(y-h*3/5)*1.8
				if dx*dx+dy*dy < 150*150 {
					img.SetRGBA(x, y, color.RGBA{120, 112, 104, 255})
				}
				if dx*dx+dy*dy < 110*110 {
					img.SetRGBA(x, y, color.RGBA{40, 60, 70, 255})
				}
			}
		}
	}
	var buf bytes.Buffer
	jpeg.Encode(&buf, img, &jpeg.Options{Quality: 80})
	return buf.Bytes()
}
