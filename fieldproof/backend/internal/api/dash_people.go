package api

import (
	"context"
	"errors"
	"net/http"
	"strings"
	"time"

	"github.com/jackc/pgx/v5"
)

// ---------- NGOs ----------

type NGORow struct {
	ID            string     `json:"id"`
	Name          string     `json:"name"`
	Username      string     `json:"username"`
	State         string     `json:"state"`
	Districts     []string   `json:"districts"`
	Blocks        []string   `json:"blocks"`
	Surveyors     int        `json:"surveyors"`
	Farmers       int        `json:"farmers"`
	FirstTimeRate *float64   `json:"firstTimeRate"`
	ReportsTo     string     `json:"reportsTo"`
	Active        bool       `json:"active"`
	NeverSignedIn bool       `json:"neverSignedIn"`
	LastLoginAt   *time.Time `json:"lastLoginAt"`
}

// firstTimeRateSQL: share of reviewed documents whose first decision was Approve,
// for the surveyors matched by the condition on sv.
func firstTimeRateSQL(svCond string) string {
	return `(SELECT round(100.0 * avg((x.event = 'approved')::int), 0)::float8 FROM (
		SELECT DISTINCT ON (e.farmer_id, e.doc) e.event FROM doc_events e
		JOIN farmers f ON f.id = e.farmer_id JOIN surveyors sv ON sv.id = f.surveyor_id
		WHERE ` + svCond + ` AND e.event <> 'reuploaded' ORDER BY e.farmer_id, e.doc, e.created_at, e.id) x)`
}

func (s *Server) handleDashNGOs(w http.ResponseWriter, r *http.Request) {
	u := dashUserFrom(r)
	v := r.URL.Query()
	q := &qb{}
	scopeNGOs(u, q)
	if st := strings.TrimSpace(v.Get("state")); st != "" {
		q.and("n.state = ?", st)
	}
	if d := strings.TrimSpace(v.Get("district")); d != "" {
		q.and(`(n.district = ? OR EXISTS (SELECT 1 FROM surveyor_blocks b JOIN surveyors sv ON sv.id=b.surveyor_id WHERE sv.ngo_user_id=n.id AND b.district=?)
			OR EXISTS (SELECT 1 FROM farmers f JOIN surveyors sv ON sv.id=f.surveyor_id JOIN villages vv ON vv.id=f.village_id WHERE sv.ngo_user_id=n.id AND f.status='submitted' AND vv.district=?))`, d, d, d)
	}
	if b := strings.TrimSpace(v.Get("block")); b != "" {
		q.and(`(EXISTS (SELECT 1 FROM surveyor_blocks b JOIN surveyors sv ON sv.id=b.surveyor_id WHERE sv.ngo_user_id=n.id AND b.block=?)
			OR EXISTS (SELECT 1 FROM farmers f JOIN surveyors sv ON sv.id=f.surveyor_id JOIN villages vv ON vv.id=f.village_id WHERE sv.ngo_user_id=n.id AND f.status='submitted' AND vv.block=?))`, b, b)
	}
	if vs := v.Get("village"); vs != "" {
		var vid int
		if _, err := parseIntStrict(vs, &vid); err != nil {
			writeErr(w, 400, "bad_filter", "village must be a village id")
			return
		}
		q.and(`EXISTS (SELECT 1 FROM farmers f JOIN surveyors sv ON sv.id=f.surveyor_id WHERE sv.ngo_user_id=n.id AND f.status='submitted' AND f.village_id=?)`, vid)
	}
	if t := strings.TrimSpace(v.Get("q")); t != "" {
		pat := "%" + likeEscape(t) + "%"
		q.and("(n.full_name ILIKE ? OR n.username ILIKE ?)", pat, pat)
	}
	rows, err := s.db.Query(r.Context(), `
		SELECT n.id, n.full_name, n.username, coalesce(n.state,''),
			coalesce((SELECT array_agg(DISTINCT d ORDER BY d) FROM (
				SELECT b.district AS d FROM surveyor_blocks b JOIN surveyors sv ON sv.id=b.surveyor_id WHERE sv.ngo_user_id=n.id
				UNION SELECT n.district WHERE n.district <> '') dd), '{}'),
			coalesce((SELECT array_agg(DISTINCT b.block ORDER BY b.block) FROM surveyor_blocks b JOIN surveyors sv ON sv.id=b.surveyor_id WHERE sv.ngo_user_id=n.id), '{}'),
			(SELECT count(*) FROM surveyors sv WHERE sv.ngo_user_id=n.id),
			(SELECT count(*) FROM farmers f JOIN surveyors sv ON sv.id=f.surveyor_id WHERE sv.ngo_user_id=n.id AND f.status='submitted'),
			`+firstTimeRateSQL("sv.ngo_user_id = n.id")+`,
			coalesce((SELECT c.full_name FROM users c WHERE c.id=n.created_by), ''),
			n.active, n.last_login_at IS NULL, n.last_login_at
		FROM users n WHERE `+q.where()+` ORDER BY lower(n.full_name)`, q.args...)
	if err != nil {
		writeErr(w, 500, "db", err.Error())
		return
	}
	list, err := pgx.CollectRows(rows, func(row pgx.CollectableRow) (NGORow, error) {
		var x NGORow
		err := row.Scan(&x.ID, &x.Name, &x.Username, &x.State, &x.Districts, &x.Blocks, &x.Surveyors, &x.Farmers, &x.FirstTimeRate,
			&x.ReportsTo, &x.Active, &x.NeverSignedIn, &x.LastLoginAt)
		return x, err
	})
	if err != nil {
		writeErr(w, 500, "db", err.Error())
		return
	}
	writeJSON(w, 200, map[string]any{"items": list, "total": len(list)})
}

// ---------- surveyors ----------

type SurveyorRow struct {
	ID         string     `json:"id"`
	UserID     *string    `json:"userId"`
	Name       string     `json:"name"`
	Username   string     `json:"username"`
	Phone      string     `json:"phone"`
	NGO        string     `json:"ngo"`
	NgoID      string     `json:"ngoId"`
	State      string     `json:"state"`
	District   string     `json:"district"`
	Blocks     []string   `json:"blocks"`
	Farmers    int        `json:"farmers"`
	SentBack   int        `json:"sentBack"`
	LastSeenAt *time.Time `json:"lastSeenAt"`
	Login      string     `json:"login"` // active | never | off
}

func (s *Server) handleDashSurveyors(w http.ResponseWriter, r *http.Request) {
	u := dashUserFrom(r)
	v := r.URL.Query()
	q := &qb{}
	scopeSurveyors(u, q)
	if st := strings.TrimSpace(v.Get("state")); st != "" {
		q.and("sv.state = ?", st)
	}
	if ngo := v.Get("ngo"); ngo != "" {
		if !uuidRe.MatchString(ngo) {
			writeErr(w, 400, "bad_filter", "ngo must be an id")
			return
		}
		q.and("sv.ngo_user_id = ?", ngo)
	}
	if d := strings.TrimSpace(v.Get("district")); d != "" {
		q.and("(sv.district = ? OR EXISTS (SELECT 1 FROM surveyor_blocks b WHERE b.surveyor_id=sv.id AND b.district=?))", d, d)
	}
	if b := strings.TrimSpace(v.Get("block")); b != "" {
		q.and("EXISTS (SELECT 1 FROM surveyor_blocks b WHERE b.surveyor_id=sv.id AND b.block=?)", b)
	}
	if t := strings.TrimSpace(v.Get("q")); t != "" {
		pat := "%" + likeEscape(t) + "%"
		if ph := NormalizePhone(t); ph != "" {
			q.and("(sv.name ILIKE ? OR coalesce(u.username,'') ILIKE ? OR sv.phone = ?)", pat, pat, ph)
		} else {
			q.and("(sv.name ILIKE ? OR coalesce(u.username,'') ILIKE ? OR sv.phone LIKE ?)", pat, pat, pat)
		}
	}
	const sentBack = `(SELECT count(*) FROM action_items a WHERE a.surveyor_id=sv.id AND a.status='open' AND a.kind LIKE 'fix\_%')`
	const base = ` FROM surveyors sv LEFT JOIN users u ON u.surveyor_id=sv.id LEFT JOIN users n ON n.id=sv.ngo_user_id `
	ctx := r.Context()
	var all, fix, stale, never int
	if err := s.db.QueryRow(ctx, `SELECT count(*),
			count(*) FILTER (WHERE `+sentBack+` > 0),
			count(*) FILTER (WHERE sv.last_seen_at < now() - interval '7 days'),
			count(*) FILTER (WHERE sv.last_seen_at IS NULL)`+base+`WHERE `+q.where(), q.args...).Scan(&all, &fix, &stale, &never); err != nil {
		writeErr(w, 500, "db", err.Error())
		return
	}
	switch v.Get("quick") {
	case "", "all":
	case "fix":
		q.and(sentBack + " > 0")
	case "stale":
		q.and("sv.last_seen_at < now() - interval '7 days'")
	case "never":
		q.and("sv.last_seen_at IS NULL")
	default:
		writeErr(w, 400, "bad_filter", "quick must be fix, stale or never")
		return
	}
	rows, err := s.db.Query(ctx, `
		SELECT sv.id, u.id::text, sv.name, coalesce(u.username,''), sv.phone, coalesce(n.full_name,''), coalesce(n.id::text,''),
			coalesce(sv.state,''), sv.district,
			coalesce((SELECT array_agg(b.block ORDER BY b.block) FROM surveyor_blocks b WHERE b.surveyor_id=sv.id), '{}'),
			(SELECT count(*) FROM farmers f WHERE f.surveyor_id=sv.id AND f.status='submitted'),
			`+sentBack+`, sv.last_seen_at,
			CASE WHEN u.id IS NOT NULL AND NOT u.active THEN 'off' WHEN sv.last_seen_at IS NULL THEN 'never' ELSE 'active' END
		`+base+`WHERE `+q.where()+` ORDER BY lower(sv.name)`, q.args...)
	if err != nil {
		writeErr(w, 500, "db", err.Error())
		return
	}
	list, err := pgx.CollectRows(rows, func(row pgx.CollectableRow) (SurveyorRow, error) {
		var x SurveyorRow
		err := row.Scan(&x.ID, &x.UserID, &x.Name, &x.Username, &x.Phone, &x.NGO, &x.NgoID, &x.State, &x.District, &x.Blocks,
			&x.Farmers, &x.SentBack, &x.LastSeenAt, &x.Login)
		return x, err
	})
	if err != nil {
		writeErr(w, 500, "db", err.Error())
		return
	}
	writeJSON(w, 200, map[string]any{"items": list, "total": len(list),
		"counts": map[string]int{"all": all, "fix": fix, "stale": stale, "never": never}})
}

// ---------- state leads (admin) ----------

type StateLeadRow struct {
	ID            string     `json:"id"`
	Name          string     `json:"name"`
	Username      string     `json:"username"`
	State         string     `json:"state"`
	NGOs          int        `json:"ngos"`
	Surveyors     int        `json:"surveyors"`
	Farmers       int        `json:"farmers"`
	Active        bool       `json:"active"`
	NeverSignedIn bool       `json:"neverSignedIn"`
	LastLoginAt   *time.Time `json:"lastLoginAt"`
}

func (s *Server) handleDashStateLeads(w http.ResponseWriter, r *http.Request) {
	if dashUserFrom(r).Role != "admin" {
		writeErr(w, 403, "forbidden", "Only an admin can see state leads.")
		return
	}
	rows, err := s.db.Query(r.Context(), `
		SELECT u.id, u.full_name, u.username, u.state,
			(SELECT count(*) FROM users n WHERE n.role='ngo' AND n.state=u.state),
			(SELECT count(*) FROM surveyors sv WHERE sv.state=u.state),
			(SELECT count(*) FROM farmers f JOIN surveyors sv ON sv.id=f.surveyor_id JOIN villages v ON v.id=f.village_id WHERE `+farmerState+`=u.state AND f.status='submitted'),
			u.active, u.last_login_at IS NULL, u.last_login_at
		FROM users u WHERE u.role='state_lead' ORDER BY u.state, lower(u.full_name)`)
	if err != nil {
		writeErr(w, 500, "db", err.Error())
		return
	}
	list, err := pgx.CollectRows(rows, func(row pgx.CollectableRow) (StateLeadRow, error) {
		var x StateLeadRow
		err := row.Scan(&x.ID, &x.Name, &x.Username, &x.State, &x.NGOs, &x.Surveyors, &x.Farmers, &x.Active, &x.NeverSignedIn, &x.LastLoginAt)
		return x, err
	})
	if err != nil {
		writeErr(w, 500, "db", err.Error())
		return
	}
	writeJSON(w, 200, map[string]any{"items": list, "total": len(list)})
}

// ---------- issuing logins ----------

type IssueIn struct {
	Role     string   `json:"role"`
	Username string   `json:"username"`
	FullName string   `json:"fullName"`
	Mobile   string   `json:"mobile"`
	State    string   `json:"state"`
	NgoID    string   `json:"ngoId"`
	District string   `json:"district"`
	Blocks   []string `json:"blocks"`
}

func (s *Server) usernameTaken(ctx context.Context, username string) (bool, error) {
	var taken bool
	err := s.db.QueryRow(ctx, `SELECT EXISTS(SELECT 1 FROM users WHERE lower(username)=$1)`, strings.ToLower(username)).Scan(&taken)
	return taken, err
}

func (s *Server) handleDashUsernameCheck(w http.ResponseWriter, r *http.Request) {
	name := strings.ToLower(strings.TrimSpace(r.URL.Query().Get("u")))
	if !ValidUsername(name) {
		writeJSON(w, 200, map[string]any{"username": name, "valid": false, "available": false,
			"message": "3 to 32 characters: small letters, numbers and dots. No dot at the start or end."})
		return
	}
	taken, err := s.usernameTaken(r.Context(), name)
	if err != nil {
		writeErr(w, 500, "db", err.Error())
		return
	}
	msg := "Available"
	if taken {
		msg = "Already taken"
	}
	writeJSON(w, 200, map[string]any{"username": name, "valid": true, "available": !taken, "message": msg})
}

func cleanBlocks(in []string) []string {
	seen := map[string]bool{}
	out := []string{}
	for _, b := range in {
		b = strings.Join(strings.Fields(b), " ")
		if b == "" || seen[strings.ToLower(b)] {
			continue
		}
		seen[strings.ToLower(b)] = true
		out = append(out, b)
	}
	return out
}

func (s *Server) handleDashIssue(w http.ResponseWriter, r *http.Request) {
	u := dashUserFrom(r)
	var in IssueIn
	if !readJSON(w, r, &in) {
		return
	}
	ctx := r.Context()
	in.Role = strings.TrimSpace(in.Role)
	if !canIssue(u.Role, in.Role) {
		writeErr(w, 403, "forbidden", "You can't issue a "+strings.ToLower(roleNames[in.Role])+" login. Only roles below yours.")
		return
	}
	in.Username = strings.ToLower(strings.TrimSpace(in.Username))
	in.FullName = strings.Join(strings.Fields(in.FullName), " ")
	in.State = strings.TrimSpace(in.State)
	in.District = strings.Join(strings.Fields(in.District), " ")
	in.Blocks = cleanBlocks(in.Blocks)
	fail := func(field, msg string) {
		writeJSON(w, 400, map[string]any{"error": "bad_input", "field": field, "message": msg})
	}
	if !ValidUsername(in.Username) {
		fail("username", "Username: 3 to 32 characters, small letters, numbers and dots. No dot at the start or end.")
		return
	}
	if n := len([]rune(in.FullName)); n < 2 || n > 80 {
		fail("fullName", "Write the full name (2 to 80 characters).")
		return
	}
	mobile := ""
	if strings.TrimSpace(in.Mobile) != "" {
		mobile = NormalizePhone(in.Mobile)
		if mobile == "" {
			fail("mobile", "Mobile number must be 10 digits, starting with 6, 7, 8 or 9.")
			return
		}
	}
	if len([]rune(in.District)) > 60 || len(in.Blocks) > 30 {
		fail("blocks", "Too many blocks or the name is too long.")
		return
	}

	var state string
	var ngoID *string
	switch in.Role {
	case "state_lead":
		if !validState(in.State) {
			fail("state", "Choose the state this state lead looks after.")
			return
		}
		state = in.State
	case "ngo":
		if u.Role == "admin" {
			if !validState(in.State) {
				fail("state", "Choose the state this NGO works in.")
				return
			}
			state = in.State
		} else {
			state = u.StateName() // a state lead adds NGOs in their own state
		}
	case "surveyor":
		if mobile == "" {
			fail("mobile", "Surveyors need a mobile number: they sign in on the phone with an OTP sent to it.")
			return
		}
		if in.District == "" {
			fail("district", "Choose the district the surveyor works in.")
			return
		}
		if len(in.Blocks) == 0 {
			fail("blocks", "Add at least one block the surveyor works in.")
			return
		}
		ngo := in.NgoID
		if u.Role == "ngo" {
			ngo = u.ID
		}
		if !uuidRe.MatchString(ngo) {
			fail("ngoId", "Choose the NGO this surveyor works under.")
			return
		}
		nq := &qb{}
		nq.and("n.id = ?", ngo)
		nq.and("n.active")
		scopeNGOs(u, nq)
		var ngoState string
		if err := s.db.QueryRow(ctx, `SELECT n.state FROM users n WHERE `+nq.where(), nq.args...).Scan(&ngoState); errors.Is(err, pgx.ErrNoRows) {
			fail("ngoId", "That NGO isn't one you can add surveyors to.")
			return
		} else if err != nil {
			writeErr(w, 500, "db", err.Error())
			return
		}
		state = ngoState
		ngoID = &ngo
	}

	tx, err := s.db.Begin(ctx)
	if err != nil {
		writeErr(w, 500, "db", err.Error())
		return
	}
	defer tx.Rollback(ctx)
	var taken bool
	tx.QueryRow(ctx, `SELECT EXISTS(SELECT 1 FROM users WHERE lower(username)=$1)`, in.Username).Scan(&taken)
	if taken {
		writeJSON(w, 409, map[string]any{"error": "username_taken", "field": "username", "message": "That username is already taken. Try another."})
		return
	}
	var surveyorID *string
	if in.Role == "surveyor" {
		var existing string
		var linked bool
		err := tx.QueryRow(ctx, `SELECT s.id, EXISTS(SELECT 1 FROM users x WHERE x.surveyor_id=s.id) FROM surveyors s WHERE s.phone=$1`, mobile).Scan(&existing, &linked)
		switch {
		case errors.Is(err, pgx.ErrNoRows):
			if err := tx.QueryRow(ctx, `INSERT INTO surveyors(phone, name, district, home_block, ngo_user_id, state) VALUES ($1,$2,$3,$4,$5,$6) RETURNING id`,
				mobile, in.FullName, in.District, in.Blocks[0], ngoID, state).Scan(&existing); err != nil {
				writeErr(w, 500, "db", err.Error())
				return
			}
		case err != nil:
			writeErr(w, 500, "db", err.Error())
			return
		case linked:
			writeJSON(w, 409, map[string]any{"error": "mobile_taken", "field": "mobile", "message": "This mobile number already has a surveyor login."})
			return
		default:
			// A surveyor added before logins existed: give them a place in the hierarchy.
			if _, err := tx.Exec(ctx, `UPDATE surveyors SET name=$2, district=$3, home_block=$4, ngo_user_id=$5, state=$6 WHERE id=$1`,
				existing, in.FullName, in.District, in.Blocks[0], ngoID, state); err != nil {
				writeErr(w, 500, "db", err.Error())
				return
			}
			tx.Exec(ctx, `DELETE FROM surveyor_blocks WHERE surveyor_id=$1`, existing)
		}
		for _, b := range in.Blocks {
			if _, err := tx.Exec(ctx, `INSERT INTO surveyor_blocks(surveyor_id, district, block) VALUES ($1,$2,$3) ON CONFLICT DO NOTHING`, existing, in.District, b); err != nil {
				writeErr(w, 500, "db", err.Error())
				return
			}
		}
		surveyorID = &existing
	}
	temp := TempPassword()
	var newID string
	var statePtr *string
	if state != "" {
		statePtr = &state
	}
	err = tx.QueryRow(ctx, `
		INSERT INTO users(username, password_hash, role, full_name, mobile, state, district, blocks, ngo_id, surveyor_id, created_by, must_change_password)
		VALUES ($1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$11,true) RETURNING id`,
		in.Username, HashPassword(temp), in.Role, in.FullName, mobile, statePtr, in.District, in.Blocks, ngoID, surveyorID, u.ID).Scan(&newID)
	if err != nil {
		if strings.Contains(err.Error(), "users_username_uniq") {
			writeJSON(w, 409, map[string]any{"error": "username_taken", "field": "username", "message": "That username is already taken. Try another."})
			return
		}
		writeErr(w, 500, "db", err.Error())
		return
	}
	if err := tx.Commit(ctx); err != nil {
		writeErr(w, 500, "db", err.Error())
		return
	}
	resp := map[string]any{"id": newID, "username": in.Username, "role": in.Role, "fullName": in.FullName, "state": state}
	if in.Role == "surveyor" {
		resp["signIn"] = "otp"
		resp["mobile"] = mobile
	} else {
		resp["tempPassword"] = temp
	}
	writeJSON(w, 201, resp)
}

// manages: may u reset or turn off the login of user id?
func (s *Server) manages(ctx context.Context, u *DashUser, id string) (role string, surveyorID *string, err error) {
	if !uuidRe.MatchString(id) || id == u.ID {
		return "", nil, pgx.ErrNoRows
	}
	var state *string
	var ngoID *string
	err = s.db.QueryRow(ctx, `SELECT role, state, ngo_id, surveyor_id FROM users WHERE id=$1`, id).Scan(&role, &state, &ngoID, &surveyorID)
	if err != nil {
		return "", nil, err
	}
	ok := false
	switch u.Role {
	case "admin":
		ok = role != "admin"
	case "state_lead":
		ok = (role == "ngo" || role == "surveyor") && state != nil && *state == u.StateName()
	case "ngo":
		ok = role == "surveyor" && ngoID != nil && *ngoID == u.ID
	}
	if !ok {
		return "", nil, pgx.ErrNoRows
	}
	return role, surveyorID, nil
}

func (s *Server) handleDashResetPassword(w http.ResponseWriter, r *http.Request) {
	u := dashUserFrom(r)
	ctx := r.Context()
	role, _, err := s.manages(ctx, u, r.PathValue("id"))
	if errors.Is(err, pgx.ErrNoRows) {
		writeErr(w, 404, "not_found", "Login not found.")
		return
	} else if err != nil {
		writeErr(w, 500, "db", err.Error())
		return
	}
	if role == "surveyor" {
		writeErr(w, 400, "otp_only", "Surveyors sign in with an OTP on their phone; there is no password to reset.")
		return
	}
	temp := TempPassword()
	if _, err := s.db.Exec(ctx, `UPDATE users SET password_hash=$1, must_change_password=true, failed_logins=0, locked_until=NULL WHERE id=$2`, HashPassword(temp), r.PathValue("id")); err != nil {
		writeErr(w, 500, "db", err.Error())
		return
	}
	s.db.Exec(ctx, `DELETE FROM dash_sessions WHERE user_id=$1`, r.PathValue("id"))
	writeJSON(w, 200, map[string]any{"tempPassword": temp})
}

func (s *Server) handleDashSetActive(w http.ResponseWriter, r *http.Request) {
	u := dashUserFrom(r)
	ctx := r.Context()
	var in struct {
		Active *bool `json:"active"`
	}
	if !readJSON(w, r, &in) {
		return
	}
	if in.Active == nil {
		writeErr(w, 400, "bad_input", "active must be true or false")
		return
	}
	id := r.PathValue("id")
	_, surveyorID, err := s.manages(ctx, u, id)
	if errors.Is(err, pgx.ErrNoRows) {
		writeErr(w, 404, "not_found", "Login not found.")
		return
	} else if err != nil {
		writeErr(w, 500, "db", err.Error())
		return
	}
	if _, err := s.db.Exec(ctx, `UPDATE users SET active=$1 WHERE id=$2`, *in.Active, id); err != nil {
		writeErr(w, 500, "db", err.Error())
		return
	}
	if !*in.Active {
		s.db.Exec(ctx, `DELETE FROM dash_sessions WHERE user_id=$1`, id)
		if surveyorID != nil {
			s.db.Exec(ctx, `DELETE FROM sessions WHERE surveyor_id=$1`, *surveyorID)
		}
	}
	writeJSON(w, 200, map[string]any{"active": *in.Active})
}
