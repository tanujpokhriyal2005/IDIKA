package api

import (
	"encoding/csv"
	"fmt"
	"net/http"
	"strconv"
	"strings"
	"time"

	"github.com/jackc/pgx/v5"
)

func parseIntStrict(s string, out *int) (int, error) {
	n, err := strconv.Atoi(strings.TrimSpace(s))
	if err != nil || n <= 0 {
		return 0, fmt.Errorf("not a positive number")
	}
	*out = n
	return n, nil
}

type Option struct {
	ID    string `json:"id"`
	Name  string `json:"name"`
	Count int    `json:"count,omitempty"`
	Extra string `json:"extra,omitempty"`
}

// handleDashOptions: values for the filter dropdowns, already limited to what
// the signed-in person may see. Each level narrows the next: state → district
// → block → village, and NGO → surveyor.
func (s *Server) handleDashOptions(w http.ResponseWriter, r *http.Request) {
	u := dashUserFrom(r)
	v := r.URL.Query()
	state, district, block, ngo := strings.TrimSpace(v.Get("state")), strings.TrimSpace(v.Get("district")), strings.TrimSpace(v.Get("block")), v.Get("ngo")
	if ngo != "" && !uuidRe.MatchString(ngo) {
		writeErr(w, 400, "bad_filter", "ngo must be an id")
		return
	}
	ctx := r.Context()
	out := map[string]any{}

	// states
	var states []string
	if u.Role == "admin" {
		rows, err := s.db.Query(ctx, `SELECT DISTINCT st FROM (SELECT state AS st FROM users WHERE state IS NOT NULL UNION SELECT state FROM surveyors WHERE state IS NOT NULL UNION SELECT v.state FROM villages v JOIN farmers f ON f.village_id = v.id WHERE v.state IS NOT NULL) x ORDER BY st`)
		if err != nil {
			writeErr(w, 500, "db", err.Error())
			return
		}
		states, _ = pgx.CollectRows(rows, pgx.RowTo[string])
	} else {
		states = []string{u.StateName()}
	}
	out["states"] = states

	sq := &qb{}
	scopeSurveyors(u, sq)
	if state != "" {
		sq.and("sv.state = ?", state)
	}
	// districts and blocks: where in-scope surveyors work or have onboarded farmers
	places := `SELECT b.district, b.block FROM surveyor_blocks b JOIN surveyors sv ON sv.id=b.surveyor_id WHERE ` + sq.where() + `
		UNION SELECT vv.district, vv.block FROM farmers f JOIN surveyors sv ON sv.id=f.surveyor_id JOIN villages vv ON vv.id=f.village_id
		WHERE f.status='submitted' AND ` + sq.where()
	rows, err := s.db.Query(ctx, `SELECT DISTINCT district FROM (`+places+`) p ORDER BY district`, sq.args...)
	if err != nil {
		writeErr(w, 500, "db", err.Error())
		return
	}
	districts, _ := pgx.CollectRows(rows, pgx.RowTo[string])
	out["districts"] = districts
	out["blocks"] = []string{}
	out["villages"] = []Option{}
	if district != "" {
		args := append(append([]any{}, sq.args...), district)
		rows, err := s.db.Query(ctx, fmt.Sprintf(`SELECT DISTINCT block FROM (%s) p WHERE district=$%d ORDER BY block`, places, len(args)), args...)
		if err != nil {
			writeErr(w, 500, "db", err.Error())
			return
		}
		blocks, _ := pgx.CollectRows(rows, pgx.RowTo[string])
		out["blocks"] = blocks
		if block != "" {
			vq := &qb{conds: append([]string{}, sq.conds...), args: append([]any{}, sq.args...)}
			vq.and("f.status = 'submitted'")
			vq.and("vv.district = ?", district)
			vq.and("vv.block = ?", block)
			rows, err := s.db.Query(ctx, `SELECT vv.id::text, vv.name, count(*) FROM farmers f JOIN surveyors sv ON sv.id=f.surveyor_id JOIN villages vv ON vv.id=f.village_id
				WHERE `+vq.where()+` GROUP BY vv.id, vv.name ORDER BY vv.name`, vq.args...)
			if err != nil {
				writeErr(w, 500, "db", err.Error())
				return
			}
			villages, _ := pgx.CollectRows(rows, func(row pgx.CollectableRow) (Option, error) {
				var o Option
				err := row.Scan(&o.ID, &o.Name, &o.Count)
				return o, err
			})
			out["villages"] = villages
		}
	}

	nq := &qb{}
	scopeNGOs(u, nq)
	if state != "" {
		nq.and("n.state = ?", state)
	}
	rows, err = s.db.Query(ctx, `SELECT n.id::text, n.full_name, coalesce(n.state,'') FROM users n WHERE `+nq.where()+` ORDER BY lower(n.full_name)`, nq.args...)
	if err != nil {
		writeErr(w, 500, "db", err.Error())
		return
	}
	ngos, _ := pgx.CollectRows(rows, func(row pgx.CollectableRow) (Option, error) {
		var o Option
		err := row.Scan(&o.ID, &o.Name, &o.Extra)
		return o, err
	})
	out["ngos"] = ngos

	svq := &qb{conds: append([]string{}, sq.conds...), args: append([]any{}, sq.args...)}
	if ngo != "" {
		svq.and("sv.ngo_user_id = ?", ngo)
	}
	rows, err = s.db.Query(ctx, `SELECT sv.id::text, sv.name FROM surveyors sv WHERE `+svq.where()+` ORDER BY lower(sv.name)`, svq.args...)
	if err != nil {
		writeErr(w, 500, "db", err.Error())
		return
	}
	svs, _ := pgx.CollectRows(rows, func(row pgx.CollectableRow) (Option, error) {
		var o Option
		err := row.Scan(&o.ID, &o.Name)
		return o, err
	})
	out["surveyors"] = svs
	writeJSON(w, 200, out)
}

// handleDashPlaces: districts and blocks known to Fieldproof (for issuing a surveyor login).
func (s *Server) handleDashPlaces(w http.ResponseWriter, r *http.Request) {
	ctx := r.Context()
	district := strings.TrimSpace(r.URL.Query().Get("district"))
	if district == "" {
		rows, err := s.db.Query(ctx, `SELECT DISTINCT district FROM villages ORDER BY district`)
		if err != nil {
			writeErr(w, 500, "db", err.Error())
			return
		}
		d, _ := pgx.CollectRows(rows, pgx.RowTo[string])
		writeJSON(w, 200, map[string]any{"districts": d, "states": IndianStates})
		return
	}
	b, err := s.blocksOf(ctx, district)
	if err != nil {
		writeErr(w, 500, "db", err.Error())
		return
	}
	writeJSON(w, 200, map[string]any{"blocks": b})
}

// ---------- overview ----------

type BreakdownRow struct {
	ID        string `json:"id"`
	Name      string `json:"name"`
	Lead      string `json:"lead"`
	NGOs      int    `json:"ngos"`
	Surveyors int    `json:"surveyors"`
	Farmers   int    `json:"farmers"`
	Waiting   int    `json:"waiting"`
	SentBack  int    `json:"sentBack"`
	Approved  int    `json:"approved"`
}

const (
	condWaiting  = `'new' IN (ds.aadhaar, ds.land_record, ds.water_source, ds.boundary)`
	condSentBack = `(ds.aadhaar IN ('rejected','pending') OR ds.land_record IN ('rejected','pending') OR ds.water_source IN ('rejected','pending') OR ds.boundary IN ('rejected','pending'))`
	condApproved = `(ds.aadhaar = 'approved' AND ds.land_record = 'approved' AND ds.water_source = 'approved' AND ds.boundary = 'approved')`
)

func (s *Server) handleDashOverview(w http.ResponseWriter, r *http.Request) {
	u := dashUserFrom(r)
	ctx := r.Context()
	state := strings.TrimSpace(r.URL.Query().Get("state"))
	sq := &qb{}
	scopeSurveyors(u, sq)
	if state != "" {
		sq.and("sv.state = ?", state)
	}
	nq := &qb{}
	scopeNGOs(u, nq)
	if state != "" {
		nq.and("n.state = ?", state)
	}
	tiles := map[string]any{}
	var surveyors, activeWeek, ngos, stateLeads int
	if err := s.db.QueryRow(ctx, `SELECT count(*), count(*) FILTER (WHERE sv.last_seen_at > now() - interval '7 days') FROM surveyors sv WHERE `+sq.where(), sq.args...).
		Scan(&surveyors, &activeWeek); err != nil {
		writeErr(w, 500, "db", err.Error())
		return
	}
	s.db.QueryRow(ctx, `SELECT count(*) FROM users n WHERE `+nq.where(), nq.args...).Scan(&ngos)
	if u.Role == "admin" {
		if state != "" {
			s.db.QueryRow(ctx, `SELECT count(*) FROM users WHERE role='state_lead' AND state=$1`, state).Scan(&stateLeads)
		} else {
			s.db.QueryRow(ctx, `SELECT count(*) FROM users WHERE role='state_lead'`).Scan(&stateLeads)
		}
		var mapped int
		s.db.QueryRow(ctx, `SELECT count(DISTINCT state) FROM users WHERE role='state_lead'`).Scan(&mapped)
		tiles["stateLeads"] = stateLeads
		tiles["statesMapped"] = mapped
	}
	fq := farmerWhere(u, FarmerFilter{State: state, Docs: map[string]string{}})
	var farmers, waiting, sentBack, approved int
	var oldest *time.Time
	if err := s.db.QueryRow(ctx, `SELECT count(*), count(*) FILTER (WHERE `+condWaiting+`), count(*) FILTER (WHERE `+condSentBack+`),
			count(*) FILTER (WHERE `+condApproved+`), min(f.submitted_at) FILTER (WHERE `+condWaiting+`) `+farmerFrom+` WHERE `+fq.where(), fq.args...).
		Scan(&farmers, &waiting, &sentBack, &approved, &oldest); err != nil {
		writeErr(w, 500, "db", err.Error())
		return
	}
	tiles["ngos"] = ngos
	tiles["surveyors"] = surveyors
	tiles["activeThisWeek"] = activeWeek
	tiles["farmers"] = farmers
	tiles["waiting"] = waiting
	tiles["sentBack"] = sentBack
	tiles["approved"] = approved
	if oldest != nil {
		tiles["oldestWaitingDays"] = int(time.Since(*oldest).Hours() / 24)
	}

	// Waiting for review: oldest first, so nothing sits forgotten.
	wq := farmerWhere(u, FarmerFilter{State: state, Docs: map[string]string{}, Review: "waiting"})
	rows, err := s.db.Query(ctx, `
		SELECT f.id, f.code, f.full_name, v.name, v.block, v.district, coalesce(v.state, sv.state, ''), sv.name, coalesce(ngo.full_name,''),
			(SELECT coalesce(sum(register_acres),0)::float8 FROM lands l WHERE l.farmer_id=f.id),
			ds.aadhaar, ds.land_record, ds.water_source, ds.boundary, f.submitted_at
		`+farmerFrom+` WHERE `+wq.where()+` ORDER BY f.submitted_at ASC NULLS LAST LIMIT 8`, wq.args...)
	if err != nil {
		writeErr(w, 500, "db", err.Error())
		return
	}
	waitList, err := pgx.CollectRows(rows, func(row pgx.CollectableRow) (DashFarmerRow, error) {
		var x DashFarmerRow
		var a, l, wa, b string
		err := row.Scan(&x.ID, &x.Code, &x.FullName, &x.Village, &x.Block, &x.District, &x.State, &x.Surveyor, &x.NGO, &x.Acres, &a, &l, &wa, &b, &x.SubmittedAt)
		x.Docs = map[string]string{"aadhaar": a, "land_record": l, "water_source": wa, "boundary": b}
		return x, err
	})
	if err != nil {
		writeErr(w, 500, "db", err.Error())
		return
	}
	breakdown, by, err := s.breakdown(r, u, state)
	if err != nil {
		writeErr(w, 500, "db", err.Error())
		return
	}
	writeJSON(w, 200, map[string]any{"tiles": tiles, "waiting": waitList, "breakdown": breakdown, "breakdownBy": by})
}

// breakdown: admin sees states, a state lead sees NGOs, an NGO sees surveyors.
func (s *Server) breakdown(r *http.Request, u *DashUser, state string) ([]BreakdownRow, string, error) {
	ctx := r.Context()
	fq := farmerWhere(u, FarmerFilter{State: state, Docs: map[string]string{}})
	counts := `count(*), count(*) FILTER (WHERE ` + condWaiting + `), count(*) FILTER (WHERE ` + condSentBack + `), count(*) FILTER (WHERE ` + condApproved + `)`
	var sql, by string
	switch u.Role {
	case "admin":
		by = "state"
		sql = `WITH fc(k, c1, c2, c3, c4) AS (SELECT coalesce(v.state, sv.state, ''), ` + counts + farmerFrom + ` WHERE ` + fq.where() + ` GROUP BY 1),
			keys AS (SELECT state AS k FROM users WHERE role='state_lead' UNION SELECT k FROM fc)
			SELECT keys.k, CASE WHEN keys.k='' THEN 'No state yet' ELSE keys.k END,
				coalesce((SELECT string_agg(full_name, ', ' ORDER BY full_name) FROM users WHERE role='state_lead' AND state=keys.k), ''),
				(SELECT count(*) FROM users WHERE role='ngo' AND state=keys.k),
				(SELECT count(*) FROM surveyors WHERE coalesce(state,'')=keys.k),
				coalesce(fc.c1,0), coalesce(fc.c2,0), coalesce(fc.c3,0), coalesce(fc.c4,0)
			FROM keys LEFT JOIN fc ON fc.k=keys.k`
		if state != "" {
			sql += ` WHERE keys.k = ` + fq.arg(state)
		}
		sql += ` ORDER BY 6 DESC, 2`
	case "state_lead":
		by = "ngo"
		sql = `WITH fc(k, c1, c2, c3, c4) AS (SELECT sv.ngo_user_id, ` + counts + farmerFrom + ` WHERE ` + fq.where() + ` GROUP BY 1)
			SELECT n.id::text, n.full_name, '', 0, (SELECT count(*) FROM surveyors x WHERE x.ngo_user_id=n.id),
				coalesce(fc.c1,0), coalesce(fc.c2,0), coalesce(fc.c3,0), coalesce(fc.c4,0)
			FROM users n LEFT JOIN fc ON fc.k=n.id
			WHERE n.role='ngo' AND n.state=` + fq.arg(u.StateName()) + ` ORDER BY 6 DESC, 2`
	default:
		by = "surveyor"
		sql = `WITH fc(k, c1, c2, c3, c4) AS (SELECT sv.id, ` + counts + farmerFrom + ` WHERE ` + fq.where() + ` GROUP BY 1)
			SELECT x.id::text, x.name, '', 0, 0, coalesce(fc.c1,0), coalesce(fc.c2,0), coalesce(fc.c3,0), coalesce(fc.c4,0)
			FROM surveyors x LEFT JOIN fc ON fc.k=x.id
			WHERE x.ngo_user_id=` + fq.arg(u.ID) + ` ORDER BY 6 DESC, 2`
	}
	rows, err := s.db.Query(ctx, sql, fq.args...)
	if err != nil {
		return nil, by, err
	}
	list, err := pgx.CollectRows(rows, func(row pgx.CollectableRow) (BreakdownRow, error) {
		var b BreakdownRow
		err := row.Scan(&b.ID, &b.Name, &b.Lead, &b.NGOs, &b.Surveyors, &b.Farmers, &b.Waiting, &b.SentBack, &b.Approved)
		return b, err
	})
	return list, by, err
}

func (s *Server) handleDashCounts(w http.ResponseWriter, r *http.Request) {
	q := farmerWhere(dashUserFrom(r), FarmerFilter{Docs: map[string]string{}})
	var all, waiting, pay, short, plant, land int
	if err := s.db.QueryRow(r.Context(), `SELECT count(*), count(*) FILTER (WHERE `+condWaiting+`), count(*) FILTER (WHERE ps.payment='waiting'),
			count(*) FILTER (WHERE ps.plantation='short'), count(*) FILTER (WHERE ps.plantation='to_review'), count(*) FILTER (WHERE ps.plantation='land_review') `+farmerFrom+` WHERE `+q.where(), q.args...).
		Scan(&all, &waiting, &pay, &short, &plant, &land); err != nil {
		writeErr(w, 500, "db", err.Error())
		return
	}
	writeJSON(w, 200, map[string]any{"farmers": all, "waiting": waiting, "payments": pay, "short": short, "plantations": plant, "landChanges": land})
}

// ---------- insights ----------

type Bucket struct {
	Label     string `json:"label"`
	Start     string `json:"start"`
	Onboarded int    `json:"onboarded"`
	Approved  int    `json:"approved"`
}

func (s *Server) handleDashInsights(w http.ResponseWriter, r *http.Request) {
	u := dashUserFrom(r)
	ctx := r.Context()
	v := r.URL.Query()
	state := strings.TrimSpace(v.Get("state"))
	days := 90
	switch v.Get("range") {
	case "", "90":
	case "30":
		days = 30
	case "365":
		days = 365
	case "all":
		days = 0
	default:
		writeErr(w, 400, "bad_filter", "range must be 30, 90, 365 or all")
		return
	}
	now := time.Now()
	var from, prevFrom time.Time
	if days > 0 {
		from = now.AddDate(0, 0, -days)
		prevFrom = from.AddDate(0, 0, -days)
	}
	fq := farmerWhere(u, FarmerFilter{State: state, Docs: map[string]string{}})
	base := farmerFrom + ` WHERE ` + fq.where()
	args := fq.args
	n := len(args)
	argsWith := func(extra ...any) []any { return append(append([]any{}, args...), extra...) }
	p := func(i int) string { return fmt.Sprintf("$%d", n+i) }
	out := map[string]any{"range": v.Get("range")}

	// KPIs
	var onb, onbPrev int
	if days > 0 {
		s.db.QueryRow(ctx, `SELECT count(*) FILTER (WHERE f.submitted_at >= `+p(1)+`), count(*) FILTER (WHERE f.submitted_at >= `+p(2)+` AND f.submitted_at < `+p(1)+`) `+base,
			argsWith(from, prevFrom)...).Scan(&onb, &onbPrev)
	} else {
		s.db.QueryRow(ctx, `SELECT count(*) `+base, args...).Scan(&onb)
	}
	firstRate := func(start, end *time.Time) *float64 {
		var rate *float64
		cond, a := "TRUE", argsWith()
		if start != nil {
			a = argsWith(*start, *end)
			cond = "x.created_at >= " + p(1) + " AND x.created_at < " + p(2)
		}
		s.db.QueryRow(ctx, `SELECT round(100.0 * avg((x.event='approved')::int), 0)::float8 FROM (
			SELECT DISTINCT ON (e.farmer_id, e.doc) e.event, e.created_at FROM doc_events e
			WHERE e.event <> 'reuploaded' AND e.farmer_id IN (SELECT f.id `+base+`)
			ORDER BY e.farmer_id, e.doc, e.created_at, e.id) x WHERE `+cond, a...).Scan(&rate)
		return rate
	}
	var rate, ratePrev *float64
	if days > 0 {
		rate, ratePrev = firstRate(&from, &now), firstRate(&prevFrom, &from)
	} else {
		rate = firstRate(nil, nil)
	}
	var avgDays *float64
	{
		cond, a := "TRUE", argsWith()
		if days > 0 {
			cond, a = "fr.first_at >= "+p(1), argsWith(from)
		}
		s.db.QueryRow(ctx, `SELECT round(avg(extract(epoch FROM fr.first_at - fr.submitted_at) / 86400.0)::numeric, 1)::float8 FROM (
			SELECT f.submitted_at, (SELECT min(e.created_at) FROM doc_events e WHERE e.farmer_id=f.id AND e.event <> 'reuploaded') AS first_at `+base+`) fr
			WHERE fr.first_at IS NOT NULL AND fr.submitted_at IS NOT NULL AND `+cond, a...).Scan(&avgDays)
	}
	var areaM2 float64
	var mappedFarmers int
	s.db.QueryRow(ctx, `SELECT coalesce(sum(b.area_m2),0), count(DISTINCT b.farmer_id) FROM boundaries b WHERE b.status='active' AND b.purpose='onboarding' AND b.farmer_id IN (SELECT f.id `+base+`)`, args...).
		Scan(&areaM2, &mappedFarmers)
	kpi := map[string]any{"onboarded": onb, "onboardedPrev": onbPrev, "firstTimeRate": rate, "firstTimeRatePrev": ratePrev,
		"avgDaysToFirstReview": avgDays, "landMappedHa": areaM2 / 10000, "mappedFarmers": mappedFarmers}
	out["kpis"] = kpi

	// Series: weekly up to 90 days, monthly beyond.
	unit, count := "week", 13
	switch days {
	case 30:
		count = 5
	case 365:
		unit, count = "month", 12
	case 0:
		unit = "month"
		var first *time.Time
		s.db.QueryRow(ctx, `SELECT min(f.submitted_at) `+base, args...).Scan(&first)
		count = 12
		if first != nil {
			m := (now.Year()-first.Year())*12 + int(now.Month()) - int(first.Month()) + 1
			count = max(1, min(m, 36))
		}
	}
	rows, err := s.db.Query(ctx, fmt.Sprintf(`
		WITH b AS (SELECT generate_series(date_trunc('%[1]s', now()) - (%[2]d - 1) * interval '1 %[1]s', date_trunc('%[1]s', now()), interval '1 %[1]s') AS start),
		fa AS (SELECT f.id, f.submitted_at,
			CASE WHEN `+condApproved+` THEN (SELECT max(d.updated_at) FROM farmer_docs d WHERE d.farmer_id=f.id) END AS approved_at `+base+`)
		SELECT b.start, (SELECT count(*) FROM fa WHERE date_trunc('%[1]s', fa.submitted_at) = b.start),
			(SELECT count(*) FROM fa WHERE date_trunc('%[1]s', fa.approved_at) = b.start)
		FROM b ORDER BY b.start`, unit, count), args...)
	if err != nil {
		writeErr(w, 500, "db", err.Error())
		return
	}
	series, err := pgx.CollectRows(rows, func(row pgx.CollectableRow) (Bucket, error) {
		var b Bucket
		var start time.Time
		err := row.Scan(&start, &b.Onboarded, &b.Approved)
		b.Start = start.Format("2006-01-02")
		if unit == "month" {
			b.Label = start.Format("Jan 06")
		} else {
			b.Label = start.Format("2 Jan")
		}
		return b, err
	})
	if err != nil {
		writeErr(w, 500, "db", err.Error())
		return
	}
	out["series"] = series
	out["seriesUnit"] = unit

	// Funnel (all time): onboarded → documents approved → agreement → planted.
	var fOnb, fDocs, fAgree, fPlant int
	s.db.QueryRow(ctx, `SELECT count(*), count(*) FILTER (WHERE `+condApproved+`),
		count(*) FILTER (WHERE EXISTS (SELECT 1 FROM files x WHERE x.farmer_id=f.id AND x.kind='agreement')),
		count(*) FILTER (WHERE EXISTS (SELECT 1 FROM files x WHERE x.farmer_id=f.id AND x.kind='plantation_proof')) `+base, args...).
		Scan(&fOnb, &fDocs, &fAgree, &fPlant)
	out["funnel"] = []map[string]any{
		{"key": "onboarded", "label": "Onboarded", "value": fOnb},
		{"key": "documents", "label": "Documents approved", "value": fDocs},
		{"key": "agreement", "label": "Agreement signed", "value": fAgree},
		{"key": "planted", "label": "Planted", "value": fPlant},
	}

	// Review results by document: current status of every onboarded farmer's document.
	var docs []map[string]any
	for _, d := range docKeys {
		var ap, pe, re, ne, mi int
		s.db.QueryRow(ctx, `SELECT count(*) FILTER (WHERE ds.`+d+`='approved'), count(*) FILTER (WHERE ds.`+d+`='pending'),
			count(*) FILTER (WHERE ds.`+d+`='rejected'), count(*) FILTER (WHERE ds.`+d+`='new'), count(*) FILTER (WHERE ds.`+d+`='missing') `+base, args...).
			Scan(&ap, &pe, &re, &ne, &mi)
		var first *float64
		s.db.QueryRow(ctx, `SELECT round(100.0 * avg((x.event='approved')::int), 0)::float8 FROM (
			SELECT DISTINCT ON (e.farmer_id) e.event FROM doc_events e WHERE e.doc=`+p(1)+` AND e.event <> 'reuploaded'
			AND e.farmer_id IN (SELECT f.id `+base+`) ORDER BY e.farmer_id, e.created_at, e.id) x`, argsWith(d)...).Scan(&first)
		var reason string
		var reasonCount int
		s.db.QueryRow(ctx, `SELECT min(e.message), count(*) FROM doc_events e WHERE e.doc=`+p(1)+` AND e.event IN ('rejected','pending') AND e.message <> ''
			AND e.farmer_id IN (SELECT f.id `+base+`) GROUP BY lower(e.message) ORDER BY count(*) DESC, max(e.created_at) DESC LIMIT 1`, argsWith(d)...).Scan(&reason, &reasonCount)
		docs = append(docs, map[string]any{"doc": d, "label": docNames[d], "approved": ap, "pending": pe, "rejected": re, "new": ne, "missing": mi,
			"firstTimeRate": first, "topReason": reason, "topReasonCount": reasonCount})
	}
	out["docs"] = docs

	// Waiting for review, by how long.
	var a0, a1, a2, a3 int
	s.db.QueryRow(ctx, `SELECT count(*) FILTER (WHERE age < 1), count(*) FILTER (WHERE age >= 1 AND age < 2),
		count(*) FILTER (WHERE age >= 2 AND age < 3), count(*) FILTER (WHERE age >= 3) FROM (
		SELECT extract(epoch FROM now() - CASE
			WHEN (ds.aadhaar='new' AND NOT EXISTS (SELECT 1 FROM farmer_docs d WHERE d.farmer_id=f.id AND d.doc='aadhaar'))
			  OR (ds.land_record='new' AND NOT EXISTS (SELECT 1 FROM farmer_docs d WHERE d.farmer_id=f.id AND d.doc='land_record'))
			  OR (ds.water_source='new' AND NOT EXISTS (SELECT 1 FROM farmer_docs d WHERE d.farmer_id=f.id AND d.doc='water_source'))
			  OR (ds.boundary='new' AND NOT EXISTS (SELECT 1 FROM farmer_docs d WHERE d.farmer_id=f.id AND d.doc='boundary'))
			THEN coalesce(f.submitted_at, f.created_at)
			ELSE (SELECT min(d.updated_at) FROM farmer_docs d WHERE d.farmer_id=f.id AND d.status='new') END) / 86400.0 AS age
		`+base+` AND `+condWaiting+`) q`, args...).Scan(&a0, &a1, &a2, &a3)
	out["queue"] = []map[string]any{{"label": "Today", "value": a0}, {"label": "1 day", "value": a1}, {"label": "2 days", "value": a2}, {"label": "3+ days", "value": a3}}

	breakdown, by, err := s.breakdown(r, u, state)
	if err != nil {
		writeErr(w, 500, "db", err.Error())
		return
	}
	out["breakdown"] = breakdown
	out["breakdownBy"] = by

	sq := &qb{}
	scopeSurveyors(u, sq)
	if state != "" {
		sq.and("sv.state = ?", state)
	}
	var svTotal, svActive, svNever int
	s.db.QueryRow(ctx, `SELECT count(*), count(*) FILTER (WHERE sv.last_seen_at > now() - interval '7 days'), count(*) FILTER (WHERE sv.last_seen_at IS NULL)
		FROM surveyors sv WHERE `+sq.where(), sq.args...).Scan(&svTotal, &svActive, &svNever)
	out["surveyors"] = map[string]int{"total": svTotal, "active": svActive, "notSynced": svTotal - svActive, "never": svNever}

	// Team table: NGOs for admins and state leads, surveyors for an NGO.
	var team []map[string]any
	if u.Role == "ngo" {
		rows, err = s.db.Query(ctx, `SELECT sv.name, (SELECT count(*) FROM farmers f WHERE f.surveyor_id=sv.id AND f.status='submitted'), `+
			firstTimeRateSQL("sv.id = s0.id")+` FROM surveyors sv JOIN surveyors s0 ON s0.id=sv.id WHERE `+sq.where()+` ORDER BY 2 DESC, 1`, sq.args...)
	} else {
		nq := &qb{}
		scopeNGOs(u, nq)
		if state != "" {
			nq.and("n.state = ?", state)
		}
		rows, err = s.db.Query(ctx, `SELECT n.full_name || '|' || coalesce(n.state,'') || '|' || (SELECT count(*) FROM surveyors sv WHERE sv.ngo_user_id=n.id),
			(SELECT count(*) FROM farmers f JOIN surveyors sv ON sv.id=f.surveyor_id WHERE sv.ngo_user_id=n.id AND f.status='submitted'), `+
			firstTimeRateSQL("sv.ngo_user_id = n.id")+` FROM users n WHERE `+nq.where()+` ORDER BY 2 DESC, 1`, nq.args...)
	}
	if err != nil {
		writeErr(w, 500, "db", err.Error())
		return
	}
	for rows.Next() {
		var name string
		var farmers int
		var rt *float64
		if err := rows.Scan(&name, &farmers, &rt); err != nil {
			rows.Close()
			writeErr(w, 500, "db", err.Error())
			return
		}
		row := map[string]any{"name": name, "farmers": farmers, "firstTimeRate": rt}
		if parts := strings.Split(name, "|"); len(parts) == 3 {
			row["name"], row["state"] = parts[0], parts[1]
			row["surveyors"], _ = strconv.Atoi(parts[2])
		}
		team = append(team, row)
	}
	rows.Close()
	out["team"] = team
	out["teamBy"] = map[bool]string{true: "surveyor", false: "ngo"}[u.Role == "ngo"]
	writeJSON(w, 200, out)
}

// handleDashFarmersCSV: the filtered farmer list as a spreadsheet.
func (s *Server) handleDashFarmersCSV(w http.ResponseWriter, r *http.Request) {
	f, bad := parseFarmerFilter(r.URL.Query())
	if bad != "" {
		writeErr(w, 400, "bad_filter", bad)
		return
	}
	q := farmerWhere(dashUserFrom(r), f)
	rows, err := s.db.Query(r.Context(), `
		SELECT f.code, f.full_name, f.mobile, v.name, v.block, v.district, coalesce(v.state, sv.state, ''), sv.name, coalesce(ngo.full_name,''),
			(SELECT coalesce(sum(register_acres),0)::float8 FROM lands l WHERE l.farmer_id=f.id),
			(SELECT coalesce(sum(area_m2),0) FROM boundaries b WHERE b.farmer_id=f.id AND b.status='active' AND b.purpose='onboarding'),
			ds.aadhaar, ds.land_record, ds.water_source, ds.boundary, ps.payment, ps.plantation, f.submitted_at
		`+farmerFrom+` WHERE `+q.where()+` ORDER BY `+farmerOrder(f.Sort)+` LIMIT 50000`, q.args...)
	if err != nil {
		writeErr(w, 500, "db", err.Error())
		return
	}
	defer rows.Close()
	w.Header().Set("Content-Type", "text/csv; charset=utf-8")
	w.Header().Set("Content-Disposition", fmt.Sprintf(`attachment; filename="fieldproof-farmers-%s.csv"`, time.Now().Format("2006-01-02")))
	w.Write([]byte("\xEF\xBB\xBF")) // so Excel reads Marathi and Hindi names correctly
	cw := csv.NewWriter(w)
	cw.Write([]string{"Farmer ID", "Name", "Mobile", "Village", "Block", "District", "State", "Surveyor", "NGO",
		"Registered acres", "Walked acres", "Aadhaar", "Land record", "Water source", "Boundary", "Payment", "Plantation", "Onboarded"})
	for rows.Next() {
		var code, name, mobile, village, block, district, state, sv, ngo, a, l, wa, b, pay, plant string
		var acres, area float64
		var at *time.Time
		if err := rows.Scan(&code, &name, &mobile, &village, &block, &district, &state, &sv, &ngo, &acres, &area, &a, &l, &wa, &b, &pay, &plant, &at); err != nil {
			return
		}
		on := ""
		if at != nil {
			on = at.Format("2006-01-02")
		}
		cw.Write([]string{code, csvSafe(name), mobile, csvSafe(village), csvSafe(block), csvSafe(district), state, csvSafe(sv), csvSafe(ngo),
			fmt.Sprintf("%.2f", acres), fmt.Sprintf("%.3f", area/4046.8564224), a, l, wa, b, pay, plant, on})
	}
	cw.Flush()
}

// csvSafe stops spreadsheet apps from treating text as a formula.
func csvSafe(s string) string {
	if s != "" && strings.ContainsRune("=+-@\t\r", rune(s[0])) {
		return "'" + s
	}
	return s
}
