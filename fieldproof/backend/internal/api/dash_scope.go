package api

import (
	"fmt"
	"net/url"
	"strconv"
	"strings"
)

// Who sees what (mirrors the Roles and access page):
//   admin      – everything in India
//   state lead – NGOs, surveyors and farmers of their own state
//   NGO        – their own surveyors and the farmers those surveyors onboarded
// Every dashboard query is built with these helpers so a filter can only
// narrow what the signed-in person may see, never widen it.

// qb collects SQL conditions with ? placeholders and numbers them $1, $2, ...
type qb struct {
	conds []string
	args  []any
}

func (q *qb) and(cond string, vals ...any) {
	var b strings.Builder
	vi := 0
	for _, r := range cond {
		if r == '?' && vi < len(vals) {
			q.args = append(q.args, vals[vi])
			vi++
			fmt.Fprintf(&b, "$%d", len(q.args))
			continue
		}
		b.WriteRune(r)
	}
	q.conds = append(q.conds, b.String())
}

// arg adds a value and returns its placeholder, for use outside WHERE.
func (q *qb) arg(v any) string {
	q.args = append(q.args, v)
	return fmt.Sprintf("$%d", len(q.args))
}

func (q *qb) where() string {
	if len(q.conds) == 0 {
		return "TRUE"
	}
	return strings.Join(q.conds, " AND ")
}

// scopeSurveyors limits rows of surveyors aliased sv.
func scopeSurveyors(u *DashUser, q *qb) {
	switch u.Role {
	case "admin":
	case "state_lead":
		q.and("sv.state = ?", u.StateName())
	case "ngo":
		q.and("sv.ngo_user_id = ?", u.ID)
	default:
		q.and("FALSE")
	}
}

// scopeFarmers limits farmers f (with villages v and surveyors sv joined) to
// what the signed-in person may see. A state lead sees the farmers whose village
// is in their state, whoever onboarded them.
func scopeFarmers(u *DashUser, q *qb) {
	switch u.Role {
	case "admin":
	case "state_lead":
		q.and(farmerState+" = ?", u.StateName())
	case "ngo":
		q.and("sv.ngo_user_id = ?", u.ID)
	default:
		q.and("FALSE")
	}
}

// scopeNGOs limits rows of users aliased n (role ngo).
func scopeNGOs(u *DashUser, q *qb) {
	q.and("n.role = 'ngo'")
	switch u.Role {
	case "admin":
	case "state_lead":
		q.and("n.state = ?", u.StateName())
	case "ngo":
		q.and("n.id = ?", u.ID)
	default:
		q.and("FALSE")
	}
}

var docKeys = []string{"aadhaar", "land_record", "water_source", "boundary"}

var docNames = map[string]string{
	"aadhaar": "Aadhaar", "land_record": "Land record", "water_source": "Water source photo", "boundary": "Farm boundary",
}

var docStatuses = map[string]bool{"missing": true, "new": true, "approved": true, "rejected": true, "pending": true}

// FarmerFilter is what the Farmers page can filter on. Empty means any.
type FarmerFilter struct {
	State, District, Block string
	VillageID              int
	NgoID, SurveyorID      string
	Docs                   map[string]string // doc key -> status
	Review                 string            // waiting | sent_back | approved
	Payment                string            // locked | none | waiting | approved | sent_back
	Plantation             string            // locked | in_progress | short | to_review | sent_back | done
	Q                      string
	Sort                   string
	Page, Size             int
}

// query params use short names: aadhaar, land, water, boundary
var docParam = map[string]string{"aadhaar": "aadhaar", "land": "land_record", "water": "water_source", "boundary": "boundary"}

func parseFarmerFilter(v url.Values) (FarmerFilter, string) {
	f := FarmerFilter{
		State:    strings.TrimSpace(v.Get("state")),
		District: strings.TrimSpace(v.Get("district")),
		Block:    strings.TrimSpace(v.Get("block")),
		Q:        strings.TrimSpace(v.Get("q")),
		Docs:     map[string]string{},
		Page:     1,
		Size:     25,
		Sort:     "newest",
	}
	if s := v.Get("village"); s != "" {
		n, err := strconv.Atoi(s)
		if err != nil || n <= 0 {
			return f, "village must be a village id"
		}
		f.VillageID = n
	}
	if s := v.Get("ngo"); s != "" {
		if !uuidRe.MatchString(s) {
			return f, "ngo must be an id"
		}
		f.NgoID = s
	}
	if s := v.Get("surveyor"); s != "" {
		if !uuidRe.MatchString(s) {
			return f, "surveyor must be an id"
		}
		f.SurveyorID = s
	}
	for p, doc := range docParam {
		if s := v.Get(p); s != "" && s != "any" {
			if !docStatuses[s] {
				return f, fmt.Sprintf("%s must be one of missing, new, approved, rejected, pending", p)
			}
			f.Docs[doc] = s
		}
	}
	switch r := v.Get("review"); r {
	case "", "waiting", "sent_back", "approved":
		f.Review = r
	default:
		return f, "review must be waiting, sent_back or approved"
	}
	switch p := v.Get("payment"); p {
	case "", "any":
	case "locked", "none", "waiting", "approved", "sent_back":
		f.Payment = p
	default:
		return f, "payment must be locked, none, waiting, approved or sent_back"
	}
	switch p := v.Get("plantation"); p {
	case "", "any":
	case "locked", "in_progress", "short", "land_change", "to_review", "sent_back", "done":
		f.Plantation = p
	default:
		return f, "plantation must be locked, in_progress, short, land_change, to_review, sent_back or done"
	}
	switch s := v.Get("sort"); s {
	case "", "newest":
	case "oldest", "name":
		f.Sort = s
	default:
		return f, "sort must be newest, oldest or name"
	}
	if s := v.Get("page"); s != "" {
		n, err := strconv.Atoi(s)
		if err != nil || n < 1 {
			return f, "page must be 1 or more"
		}
		f.Page = n
	}
	if s := v.Get("size"); s != "" {
		n, err := strconv.Atoi(s)
		if err != nil || n < 1 {
			return f, "size must be 1 or more"
		}
		f.Size = min(n, 100)
	}
	if len([]rune(f.Q)) > 100 {
		return f, "search is too long"
	}
	return f, ""
}

// likeEscape makes user text safe inside an ILIKE pattern.
func likeEscape(s string) string {
	r := strings.NewReplacer(`\`, `\\`, `%`, `\%`, `_`, `\_`)
	return r.Replace(s)
}

const farmerFrom = `
	FROM farmers f
	JOIN surveyors sv ON sv.id = f.surveyor_id
	JOIN villages v ON v.id = f.village_id
	JOIN farmer_doc_status ds ON ds.farmer_id = f.id
	JOIN farmer_plan_status ps ON ps.farmer_id = f.id
	LEFT JOIN users ngo ON ngo.id = sv.ngo_user_id`

// farmerWhere: onboarding finished, in scope, and matching every filter.
func farmerWhere(u *DashUser, f FarmerFilter) *qb {
	q := &qb{}
	q.and("f.status = 'submitted'")
	scopeFarmers(u, q)
	if f.State != "" {
		q.and(farmerState+" = ?", f.State)
	}
	if f.District != "" {
		q.and("v.district = ?", f.District)
	}
	if f.Block != "" {
		q.and("v.block = ?", f.Block)
	}
	if f.VillageID != 0 {
		q.and("v.id = ?", f.VillageID)
	}
	if f.NgoID != "" {
		q.and("sv.ngo_user_id = ?", f.NgoID)
	}
	if f.SurveyorID != "" {
		q.and("sv.id = ?", f.SurveyorID)
	}
	for _, d := range docKeys {
		if st, ok := f.Docs[d]; ok {
			q.and("ds."+d+" = ?", st) // d comes from a fixed list
		}
	}
	switch f.Review {
	case "waiting":
		q.and("'new' IN (ds.aadhaar, ds.land_record, ds.water_source, ds.boundary)")
	case "sent_back":
		q.and("(ds.aadhaar IN ('rejected','pending') OR ds.land_record IN ('rejected','pending') OR ds.water_source IN ('rejected','pending') OR ds.boundary IN ('rejected','pending'))")
	case "approved":
		q.and("ds.aadhaar = 'approved' AND ds.land_record = 'approved' AND ds.water_source = 'approved' AND ds.boundary = 'approved'")
	}
	switch f.Payment {
	case "sent_back":
		q.and("ps.payment IN ('rejected','pending')")
	case "":
	default:
		q.and("ps.payment = ?", f.Payment)
	}
	switch f.Plantation {
	case "locked", "short", "to_review", "done":
		q.and("ps.plantation = ?", f.Plantation)
	case "sent_back":
		q.and("ps.plantation IN ('rejected','pending')")
	case "land_change":
		q.and("ps.plantation IN ('land_review','land_fix')")
	case "in_progress":
		q.and("ps.plantation IN ('pre_walk','delivery','short','otp','planting','post_walk')")
	}
	if f.Q != "" {
		pat := "%" + likeEscape(f.Q) + "%"
		digits := NormalizePhone(f.Q)
		if digits != "" {
			q.and("(f.full_name ILIKE ? OR f.code ILIKE ? OR v.name ILIKE ? OR f.mobile = ?)", pat, pat, pat, digits)
		} else {
			q.and("(f.full_name ILIKE ? OR f.code ILIKE ? OR v.name ILIKE ?)", pat, pat, pat)
		}
	}
	return q
}

func farmerOrder(sort string) string {
	switch sort {
	case "oldest":
		return "f.submitted_at ASC NULLS FIRST, f.number ASC"
	case "name":
		return "lower(f.full_name) ASC, f.number ASC"
	}
	return "f.submitted_at DESC NULLS LAST, f.number DESC"
}

var roleNames = map[string]string{"admin": "Admin", "state_lead": "State lead", "ngo": "NGO", "surveyor": "Surveyor"}

// canIssue: which roles a person may create logins for.
func canIssue(issuer, role string) bool {
	switch issuer {
	case "admin":
		return role == "state_lead" || role == "ngo" || role == "surveyor"
	case "state_lead":
		return role == "ngo" || role == "surveyor"
	case "ngo":
		return role == "surveyor"
	}
	return false
}

var IndianStates = []string{
	"Andhra Pradesh", "Arunachal Pradesh", "Assam", "Bihar", "Chhattisgarh", "Goa", "Gujarat", "Haryana",
	"Himachal Pradesh", "Jharkhand", "Karnataka", "Kerala", "Madhya Pradesh", "Maharashtra", "Manipur",
	"Meghalaya", "Mizoram", "Nagaland", "Odisha", "Punjab", "Rajasthan", "Sikkim", "Tamil Nadu", "Telangana",
	"Tripura", "Uttar Pradesh", "Uttarakhand", "West Bengal",
	"Andaman and Nicobar Islands", "Chandigarh", "Dadra and Nagar Haveli and Daman and Diu", "Delhi",
	"Jammu and Kashmir", "Ladakh", "Lakshadweep", "Puducherry",
}

func validState(s string) bool {
	for _, x := range IndianStates {
		if x == s {
			return true
		}
	}
	return false
}
