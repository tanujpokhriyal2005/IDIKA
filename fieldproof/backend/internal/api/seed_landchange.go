package api

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"log"
	"os"
	"path/filepath"
	"time"

	"fieldproof/backend/internal/geo"

	"github.com/jackc/pgx/v5/pgxpool"
)

// SeedLandChange gives the demo surveyor (Tanuj) one farmer with a reported
// land change: the walk before planting is about 650 m north of the
// onboarding land. Runs once.
func SeedLandChange(ctx context.Context, db *pgxpool.Pool, uploadDir string) error {
	var n int
	if err := db.QueryRow(ctx, `SELECT count(*) FROM plantations pl JOIN farmers f ON f.id=pl.farmer_id JOIN surveyors s ON s.id=f.surveyor_id
		WHERE s.phone='9876543210' AND (pl.land_change_status IS NOT NULL OR pl.pre_overlap IS NOT NULL)`).Scan(&n); err != nil || n > 0 {
		return err
	}
	// A farmer about to walk before planting, or waiting for saplings; the
	// walk shape is the onboarding one, moved north.
	var fid, surveyor string
	var raw []byte
	err := db.QueryRow(ctx, `SELECT f.id, f.surveyor_id, b.points FROM farmer_plan_status ps JOIN farmers f ON f.id=ps.farmer_id
		JOIN surveyors s ON s.id=f.surveyor_id
		JOIN LATERAL (SELECT points FROM boundaries WHERE farmer_id=f.id AND status='active' AND purpose='onboarding' ORDER BY created_at LIMIT 1) b ON true
		WHERE ps.plantation IN ('delivery','pre_walk') AND s.phone='9876543210' ORDER BY ps.plantation='delivery' DESC, f.number LIMIT 1`).Scan(&fid, &surveyor, &raw)
	if err != nil {
		return nil // no demo farmer at those steps
	}
	var pts []PointIn
	if json.Unmarshal(raw, &pts) != nil || len(pts) < 3 {
		return nil
	}
	ring := make([]geo.LatLng, len(pts))
	for i := range pts {
		pts[i].Lat += 0.006
		pts[i].T = time.Now().Add(time.Duration(i-len(pts)) * time.Minute).UnixMilli()
		ring[i] = geo.LatLng{Lat: pts[i].Lat, Lng: pts[i].Lng}
	}
	pj, _ := json.Marshal(pts)
	img := sampleImage("land_record")
	dir := filepath.Join(uploadDir, "demo")
	os.MkdirAll(dir, 0o755)
	path := filepath.Join(dir, "land_change_record.jpg")
	if err := os.WriteFile(path, img, 0o644); err != nil {
		return err
	}
	sum := sha256.Sum256(img)
	log.Print("seeding a demo land change")
	tx, err := db.Begin(ctx)
	if err != nil {
		return err
	}
	defer tx.Rollback(ctx)
	if _, err := tx.Exec(ctx, `UPDATE boundaries SET status='replaced' WHERE farmer_id=$1 AND purpose='pre_plant' AND status='active'`, fid); err != nil {
		return err
	}
	var bid string
	if err := tx.QueryRow(ctx, `INSERT INTO boundaries(id, farmer_id, geom, points, point_count, area_m2, client_area_m2, walked_m, accuracy_m, started_at, finished_at, purpose)
		VALUES (gen_random_uuid(),$1,ST_GeogFromText($2),$3,$4,$5,$5,$6,4,now() - interval '20 minutes',now() - interval '5 minutes','pre_plant') RETURNING id`,
		fid, polygonWKT(ring), pj, len(ring), geo.Area(ring), geo.Perimeter(ring)).Scan(&bid); err != nil {
		return err
	}
	share, dist, has, err := landFit(ctx, tx, fid, bid)
	if err != nil {
		return err
	}
	if _, err := tx.Exec(ctx, `INSERT INTO files(id, farmer_id, kind, path, mime, size_bytes, sha256, captured_at, created_at)
		VALUES (gen_random_uuid(),$1,'land_change_record',$2,'image/jpeg',$3,$4,now(),now())`, fid, path, len(img), hex.EncodeToString(sum[:])); err != nil {
		return err
	}
	if _, err := tx.Exec(ctx, `INSERT INTO plantations(farmer_id, pre_boundary_id, pre_overlap, pre_distance_m, land_change_status, land_change_reason,
			land_change_survey_no, land_change_area_ha, land_change_owner, land_change_at)
		VALUES ($1,$2,$3,$4,'waiting','changed_mind','52/1',0.7,'farmer',now())
		ON CONFLICT (farmer_id) DO UPDATE SET pre_boundary_id=EXCLUDED.pre_boundary_id, pre_overlap=EXCLUDED.pre_overlap, pre_distance_m=EXCLUDED.pre_distance_m,
			land_change_status='waiting', land_change_reason='changed_mind', land_change_survey_no='52/1', land_change_area_ha=0.7, land_change_owner='farmer',
			land_change_at=now(), updated_at=now()`, fid, bid, nullIf(has, share), nullIf(has, dist)); err != nil {
		return err
	}
	if _, err := tx.Exec(ctx, `INSERT INTO plan_events(farmer_id, event, detail, surveyor_id) VALUES ($1,'land_change_reported',$2,$3)`,
		fid, "Gat 52/1 instead of the onboarding land · Farmer changed his mind · "+distText(dist)+" apart", surveyor); err != nil {
		return err
	}
	return tx.Commit(ctx)
}
