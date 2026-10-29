package api

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/jackc/pgx/v5"
)

// GPS -> district / block (taluka) / village, answered entirely from our own
// PostGIS copy of OpenStreetMap (deploy/import-osm.sh + build-places.sql):
// the district and block are the admin polygons that contain the point, the
// village is the nearest village of that block. No outside service is called.

type LocateResult struct {
	District string    `json:"district"`
	Block    string    `json:"block"`
	Village  *Village  `json:"village"`
	Distance float64   `json:"distanceM"`
	Blocks   []string  `json:"blocks"`
	Villages []Village `json:"villages"`
}

var errNoArea = errors.New("no area")

func (s *Server) adminAt(ctx context.Context, lat, lng float64) (district, block string, err error) {
	// The block polygon containing the point; its district from the district
	// polygon, or (if that polygon is missing there) from the villages of the block.
	err = s.db.QueryRow(ctx, `
		WITH p AS (SELECT ST_SetSRID(ST_MakePoint($2, $1), 4326) AS pt)
		SELECT COALESCE(
		         (SELECT d.name_clean FROM osm_admin d, p WHERE d.admin_level = '5' AND ST_Contains(d.geom, p.pt) ORDER BY ST_Area(d.geom) LIMIT 1),
		         (SELECT v.district FROM villages v WHERE v.block = b.name_clean GROUP BY v.district ORDER BY count(*) DESC LIMIT 1)),
		       b.name_clean
		FROM osm_admin b, p
		WHERE b.admin_level = '6' AND ST_Contains(b.geom, p.pt)
		ORDER BY ST_Area(b.geom) LIMIT 1`, lat, lng).Scan(&district, &block)
	if errors.Is(err, pgx.ErrNoRows) || (err == nil && district == "") {
		return "", "", errNoArea
	}
	return
}

func (s *Server) blocksOf(ctx context.Context, district string) ([]string, error) {
	rows, err := s.db.Query(ctx, `SELECT DISTINCT block FROM villages WHERE district=$1 ORDER BY block`, district)
	if err != nil {
		return nil, err
	}
	return pgx.CollectRows(rows, pgx.RowTo[string])
}

func (s *Server) villagesOf(ctx context.Context, district, block string) ([]Village, error) {
	rows, err := s.db.Query(ctx, `SELECT id, name, block, district FROM villages WHERE district=$1 AND block=$2 ORDER BY name`, district, block)
	if err != nil {
		return nil, err
	}
	return pgx.CollectRows(rows, func(row pgx.CollectableRow) (Village, error) {
		var v Village
		err := row.Scan(&v.ID, &v.Name, &v.Block, &v.District)
		return v, err
	})
}

// GET /v1/places/locate?lat=..&lng=..
func (s *Server) handleLocate(w http.ResponseWriter, r *http.Request) {
	lat, e1 := strconv.ParseFloat(r.URL.Query().Get("lat"), 64)
	lng, e2 := strconv.ParseFloat(r.URL.Query().Get("lng"), 64)
	if e1 != nil || e2 != nil || lat < -90 || lat > 90 || lng < -180 || lng > 180 {
		writeErr(w, 400, "bad_point", "lat and lng are needed.")
		return
	}
	ctx := r.Context()
	district, block, err := s.adminAt(ctx, lat, lng)
	if err != nil {
		// Outside the imported states (or no import yet): ask OpenStreetMap's address lookup.
		res, nerr := s.locateByNominatim(ctx, lat, lng)
		if nerr != nil {
			writeErr(w, 404, "no_area", "Couldn't find the district or block here. Choose block and village.")
			return
		}
		writeJSON(w, 200, res)
		return
	}
	res := LocateResult{District: district, Block: block}
	var v Village
	err = s.db.QueryRow(ctx, `
		SELECT id, name, block, district,
		       ST_Distance(ST_SetSRID(ST_MakePoint(lng, lat), 4326)::geography, ST_SetSRID(ST_MakePoint($4, $3), 4326)::geography)
		FROM villages WHERE district=$1 AND block=$2 AND lat IS NOT NULL
		ORDER BY ST_SetSRID(ST_MakePoint(lng, lat), 4326) <-> ST_SetSRID(ST_MakePoint($4, $3), 4326) LIMIT 1`,
		district, block, lat, lng).Scan(&v.ID, &v.Name, &v.Block, &v.District, &res.Distance)
	if err == nil && res.Distance < 8000 {
		res.Village = &v
	}
	if res.Blocks, err = s.blocksOf(ctx, district); err != nil {
		writeErr(w, 500, "db", err.Error())
		return
	}
	if res.Villages, err = s.villagesOf(ctx, district, block); err != nil {
		writeErr(w, 500, "db", err.Error())
		return
	}
	writeJSON(w, 200, res)
}

// GET /v1/places/villages?district=..&block=..
func (s *Server) handlePlaceVillages(w http.ResponseWriter, r *http.Request) {
	district, block := r.URL.Query().Get("district"), r.URL.Query().Get("block")
	if district == "" || block == "" {
		writeErr(w, 400, "bad_request", "district and block are needed.")
		return
	}
	vs, err := s.villagesOf(r.Context(), district, block)
	if err != nil {
		writeErr(w, 500, "db", err.Error())
		return
	}
	writeJSON(w, 200, vs)
}

// ---------- fallback outside the imported states: Nominatim reverse geocoding ----------

const placesUA = "Fieldproof/1.2 (farmer registration app)"

var (
	nomMu    sync.Mutex
	nomLast  time.Time
	nomCache = map[string]nominatimAddr{}
	nomHTTP  = &http.Client{Timeout: 12 * time.Second}
)

type nominatimAddr struct {
	State, District, Block, Village string
}

// reverseLookup asks OpenStreetMap Nominatim what is at a point (max 1 request/second, cached per ~11 m cell).
func reverseLookup(ctx context.Context, lat, lng float64) (nominatimAddr, error) {
	key := fmt.Sprintf("%.4f,%.4f", lat, lng)
	nomMu.Lock()
	defer nomMu.Unlock()
	if a, ok := nomCache[key]; ok {
		return a, nil
	}
	if d := time.Until(nomLast.Add(1100 * time.Millisecond)); d > 0 {
		time.Sleep(d)
	}
	nomLast = time.Now()
	u := fmt.Sprintf("https://nominatim.openstreetmap.org/reverse?format=jsonv2&zoom=14&addressdetails=1&accept-language=en&lat=%f&lon=%f", lat, lng)
	req, _ := http.NewRequestWithContext(ctx, "GET", u, nil)
	req.Header.Set("User-Agent", placesUA)
	resp, err := nomHTTP.Do(req)
	if err != nil {
		return nominatimAddr{}, err
	}
	defer resp.Body.Close()
	var r struct {
		Address map[string]string `json:"address"`
	}
	if resp.StatusCode != 200 || json.NewDecoder(resp.Body).Decode(&r) != nil {
		return nominatimAddr{}, fmt.Errorf("nominatim http %d", resp.StatusCode)
	}
	a := r.Address
	pick := func(keys ...string) string {
		for _, k := range keys {
			if v := strings.TrimSpace(a[k]); v != "" {
				return v
			}
		}
		return ""
	}
	out := nominatimAddr{
		State:    pick("state", "union_territory"),
		District: pick("state_district", "county"),
		Block:    trimAdmin(pick("county", "city_district", "municipality", "subdistrict")),
		Village:  pick("village", "hamlet", "town", "suburb", "neighbourhood", "city"),
	}
	if out.Block == "" || out.Block == out.District {
		out.Block = trimAdmin(pick("city_district", "municipality", "city", "state_district"))
	}
	if out.District == "" {
		return out, errNoArea
	}
	nomCache[key] = out
	return out, nil
}

func trimAdmin(n string) string {
	for _, suf := range []string{" Taluka", " Tehsil", " Tahsil", " Taluk", " Mandal", " Block", " Subdivision", " Subdistrict", " Sub-District", " District"} {
		n = strings.TrimSuffix(n, suf)
	}
	return n
}

func (s *Server) locateByNominatim(ctx context.Context, lat, lng float64) (*LocateResult, error) {
	a, err := reverseLookup(ctx, lat, lng)
	if err != nil {
		return nil, err
	}
	// Use the spelling already in the database when it is the same place ("Pune District" -> "Pune").
	var known string
	if s.db.QueryRow(ctx, `SELECT district FROM villages WHERE regexp_replace(lower(district), '[^a-z0-9]|h', '', 'g') = regexp_replace(lower($1), '[^a-z0-9]|h', '', 'g') LIMIT 1`, trimAdmin(a.District)).Scan(&known) == nil {
		a.District = known
	} else {
		a.District = trimAdmin(a.District)
	}
	if s.db.QueryRow(ctx, `SELECT block FROM villages WHERE district=$1 AND regexp_replace(lower(block), '[^a-z0-9]|h', '', 'g') = regexp_replace(lower($2), '[^a-z0-9]|h', '', 'g') LIMIT 1`, a.District, a.Block).Scan(&known) == nil {
		a.Block = known
	}
	res := &LocateResult{District: a.District, Block: a.Block}
	if a.Village != "" {
		var v Village
		var state *string
		if a.State != "" {
			st := canonicalState(a.State)
			state = &st
		}
		err := s.db.QueryRow(ctx, `INSERT INTO villages(district, block, name, lat, lng, source, state) VALUES ($1,$2,$3,$4,$5,'gps',$6)
			ON CONFLICT (district, block, name) DO UPDATE SET lat=COALESCE(villages.lat, EXCLUDED.lat), lng=COALESCE(villages.lng, EXCLUDED.lng),
				state=COALESCE(villages.state, EXCLUDED.state)
			RETURNING id, name, block, district`, a.District, a.Block, a.Village, lat, lng, state).Scan(&v.ID, &v.Name, &v.Block, &v.District)
		if err == nil {
			res.Village = &v
		}
	}
	if res.Blocks, err = s.blocksOf(ctx, a.District); err != nil {
		return nil, err
	}
	found := false
	for _, b := range res.Blocks {
		found = found || b == a.Block
	}
	if !found {
		res.Blocks = append(res.Blocks, a.Block)
	}
	if res.Villages, err = s.villagesOf(ctx, a.District, a.Block); err != nil {
		return nil, err
	}
	return res, nil
}
