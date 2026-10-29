package api

import (
	"context"
	"log"
	"strings"
	"time"

	"github.com/jackc/pgx/v5/pgxpool"
)

// A farmer belongs to the state of their village; a surveyor to the state
// where they work. Both are filled here when missing (surveyors made from the
// old admin page, villages imported or found before states were kept), so
// state leads see everything of their state. Safe to run often: it only
// touches rows without a state.

// farmerState: SQL for a farmer's state, with farmers f, villages v, surveyors sv.
const farmerState = "coalesce(v.state, sv.state)"

func FillStates(ctx context.Context, db *pgxpool.Pool) error {
	steps := []string{
		// 1. Villages inside a state boundary of the imported map data.
		`DO $$ BEGIN
			IF to_regclass('osm_admin') IS NOT NULL THEN
				UPDATE villages v SET state = a.name
				FROM osm_admin a
				WHERE v.state IS NULL AND v.lat IS NOT NULL AND a.admin_level = '4'
					AND a.name = ANY($STATES$)
					AND ST_GeometryType(a.geom) IN ('ST_Polygon', 'ST_MultiPolygon')
					AND ST_Contains(a.geom, ST_SetSRID(ST_MakePoint(v.lng, v.lat), 4326));
			END IF;
		END $$`,
		// 2. Surveyors without a state: where most of their farmers are, else where their district is.
		`UPDATE surveyors s SET state = x.state FROM (
			SELECT f.surveyor_id, mode() WITHIN GROUP (ORDER BY v.state) AS state
			FROM farmers f JOIN villages v ON v.id = f.village_id WHERE v.state IS NOT NULL GROUP BY f.surveyor_id
		) x WHERE s.state IS NULL AND s.id = x.surveyor_id`,
		`UPDATE surveyors s SET state = x.state FROM (
			SELECT district, mode() WITHIN GROUP (ORDER BY state) AS state FROM villages WHERE state IS NOT NULL GROUP BY district
		) x WHERE s.state IS NULL AND lower(s.district) = lower(x.district)`,
		// 3. Hand-made villages (no map position, or the demo set) take the state of the surveyors working there.
		`UPDATE villages v SET state = x.state FROM (
			SELECT f.village_id, mode() WITHIN GROUP (ORDER BY s.state) AS state
			FROM farmers f JOIN surveyors s ON s.id = f.surveyor_id WHERE s.state IS NOT NULL GROUP BY f.village_id
		) x WHERE v.state IS NULL AND (v.lat IS NULL OR v.source = 'seed') AND v.id = x.village_id`,
		// 4. Any village left in a district whose other villages all agree on one state.
		`UPDATE villages v SET state = x.state FROM (
			SELECT district, min(state) AS state FROM villages WHERE state IS NOT NULL GROUP BY district HAVING count(DISTINCT state) = 1
		) x WHERE v.state IS NULL AND (v.lat IS NULL OR v.source = 'seed') AND v.district = x.district`,
	}
	list := "ARRAY['" + strings.Join(escapeQuotes(IndianStates), "','") + "']"
	for _, q := range steps {
		if _, err := db.Exec(ctx, strings.ReplaceAll(q, "$STATES$", list)); err != nil {
			return err
		}
	}
	return nil
}

func escapeQuotes(xs []string) []string {
	out := make([]string, len(xs))
	for i, x := range xs {
		out[i] = strings.ReplaceAll(x, "'", "''")
	}
	return out
}

// FillStatesByLookup asks OpenStreetMap's address lookup for the state of
// villages found by GPS outside the imported map data (one request a second),
// then fills the rest again. Runs in the background.
func FillStatesByLookup(ctx context.Context, db *pgxpool.Pool) {
	rows, err := db.Query(ctx, `SELECT id, lat, lng FROM villages WHERE state IS NULL AND lat IS NOT NULL AND source <> 'osm' LIMIT 200`)
	if err != nil {
		return
	}
	type pt struct {
		id       int
		lat, lng float64
	}
	var pts []pt
	for rows.Next() {
		var p pt
		if rows.Scan(&p.id, &p.lat, &p.lng) == nil {
			pts = append(pts, p)
		}
	}
	rows.Close()
	n := 0
	for _, p := range pts {
		a, err := reverseLookup(ctx, p.lat, p.lng)
		if err != nil || a.State == "" {
			continue
		}
		if _, err := db.Exec(ctx, `UPDATE villages SET state=$2 WHERE id=$1 AND state IS NULL`, p.id, canonicalState(a.State)); err == nil {
			n++
		}
	}
	if n > 0 {
		log.Printf("states: %d villages placed by address lookup", n)
		FillStates(ctx, db)
	}
}

// canonicalState matches "NCT of Delhi" or "maharashtra" to the dashboard's state names.
func canonicalState(s string) string {
	norm := func(x string) string {
		x = strings.ToLower(x)
		for _, w := range []string{"nct of ", "national capital territory of ", "union territory of ", "state of "} {
			x = strings.TrimPrefix(x, w)
		}
		return strings.Map(func(r rune) rune {
			if r >= 'a' && r <= 'z' {
				return r
			}
			return -1
		}, x)
	}
	k := norm(s)
	for _, x := range IndianStates {
		if norm(x) == k {
			return x
		}
	}
	return strings.TrimSpace(s)
}

func (s *Server) fillStatesSoon() {
	go func() {
		ctx, cancel := context.WithTimeout(context.Background(), 2*time.Minute)
		defer cancel()
		if err := FillStates(ctx, s.db); err != nil {
			log.Printf("states: %v", err)
		}
	}()
}
