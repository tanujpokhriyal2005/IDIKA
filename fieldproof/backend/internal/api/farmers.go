package api

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"math"
	"net/http"
	"os"
	"path/filepath"
	"regexp"
	"strconv"
	"strings"
	"time"
	"unicode"

	"github.com/jackc/pgx/v5"
)

const acresPerHectare = 2.4710538146717

var uuidRe = regexp.MustCompile(`^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$`)

// FarmerCode builds the short farmer ID shown in the app, e.g. 4417 + "Sunanda" -> "4417sun".
func FarmerCode(number int, fullName string) string {
	var p []rune
	for _, r := range strings.ToLower(fullName) {
		if r >= 'a' && r <= 'z' {
			p = append(p, r)
			if len(p) == 3 {
				break
			}
		} else if len(p) > 0 && unicode.IsSpace(r) {
			break
		}
	}
	for len(p) < 3 {
		p = append(p, 'x')
	}
	return fmt.Sprintf("%d%s", number, string(p))
}

// MaxRegisterAcres is the land-record area rounded down to a 0.25 acre step.
func MaxRegisterAcres(areaHa float64) float64 {
	return math.Floor(areaHa*acresPerHectare*4+1e-6) / 4
}

type LandIn struct {
	ID             string  `json:"id"`
	SurveyNo       string  `json:"surveyNo"`
	AreaHa         float64 `json:"areaHa"`
	RegisterAcres  float64 `json:"registerAcres"`
	OwnerType      string  `json:"ownerType"`
	RecordsConsent bool    `json:"recordsConsent"`
}

type FarmerIn struct {
	Number           int        `json:"number"`
	FullName         string     `json:"fullName"`
	Mobile           string     `json:"mobile"`
	WhatsappSame     bool       `json:"whatsappSame"`
	Whatsapp         string     `json:"whatsapp"`
	OtherMobile      string     `json:"otherMobile"`
	VillageID        int        `json:"villageId"`
	TreeInterest     bool       `json:"treeInterest"`
	AadhaarNameMatch bool       `json:"aadhaarNameMatch"`
	AadhaarConsent   bool       `json:"aadhaarConsent"`
	Lands            []LandIn   `json:"lands"`
	CreatedAt        *time.Time `json:"createdAt"`
}

type LandOut struct {
	LandIn
	Idx int `json:"idx"`
}

type FileOut struct {
	ID         string     `json:"id"`
	Kind       string     `json:"kind"`
	LandID     *string    `json:"landId"`
	Mime       string     `json:"mime"`
	Lat        *float64   `json:"lat"`
	Lng        *float64   `json:"lng"`
	CapturedAt *time.Time `json:"capturedAt"`
}

type BoundaryOut struct {
	ID         string     `json:"id"`
	LandID     *string    `json:"landId"`
	Points     []PointIn  `json:"points"`
	AreaM2     float64    `json:"areaM2"`
	PointCount int        `json:"pointCount"`
	AccuracyM  *float64   `json:"accuracyM"`
	WalkedM    *float64   `json:"walkedM"`
	FinishedAt *time.Time `json:"finishedAt"`
}

type FarmerOut struct {
	ID               string          `json:"id"`
	Number           int             `json:"number"`
	Code             string          `json:"code"`
	FullName         string          `json:"fullName"`
	Mobile           string          `json:"mobile"`
	WhatsappSame     bool            `json:"whatsappSame"`
	Whatsapp         string          `json:"whatsapp"`
	OtherMobile      string          `json:"otherMobile"`
	Village          Village         `json:"village"`
	TreeInterest     bool            `json:"treeInterest"`
	AadhaarNameMatch bool            `json:"aadhaarNameMatch"`
	AadhaarConsent   bool            `json:"aadhaarConsent"`
	Status           string          `json:"status"`
	Lands            []LandOut       `json:"lands"`
	Boundaries       []BoundaryOut   `json:"boundaries"`
	Files            []FileOut       `json:"files"`
	FarmDetails      json.RawMessage `json:"farmDetails"`
	CreatedAt        time.Time       `json:"createdAt"`
	SubmittedAt      *time.Time      `json:"submittedAt"`
}

func validMobile(m string) bool { return NormalizePhone(m) == m && m != "" }

func (s *Server) ownsFarmer(ctx context.Context, surveyorID, farmerID string) (bool, error) {
	var ok bool
	err := s.db.QueryRow(ctx, `SELECT EXISTS(SELECT 1 FROM farmers WHERE id=$1 AND surveyor_id=$2)`, farmerID, surveyorID).Scan(&ok)
	return ok, err
}

func (s *Server) handlePutFarmer(w http.ResponseWriter, r *http.Request) {
	id := r.PathValue("id")
	if !uuidRe.MatchString(id) {
		writeErr(w, 400, "bad_id", "Farmer id must be a UUID.")
		return
	}
	var in FarmerIn
	if !readJSON(w, r, &in) {
		return
	}
	sv := surveyorFrom(r)
	ctx := r.Context()

	in.FullName = strings.Join(strings.Fields(in.FullName), " ")
	switch {
	case len([]rune(in.FullName)) < 3:
		writeErr(w, 400, "bad_name", "Write the full name as on the Aadhaar card.")
		return
	case !validMobile(in.Mobile):
		writeErr(w, 400, "bad_mobile", "Mobile number must be 10 digits.")
		return
	case !in.WhatsappSame && in.Whatsapp != "" && !validMobile(in.Whatsapp):
		writeErr(w, 400, "bad_whatsapp", "WhatsApp number must be 10 digits.")
		return
	case in.OtherMobile != "" && !validMobile(in.OtherMobile):
		writeErr(w, 400, "bad_other", "Other number must be 10 digits.")
		return
	}
	if in.WhatsappSame {
		in.Whatsapp = in.Mobile
	}
	var villageOK bool
	if err := s.db.QueryRow(ctx, `
		SELECT EXISTS(SELECT 1 FROM villages v WHERE v.id=$1)`, in.VillageID).Scan(&villageOK); err != nil {
		writeErr(w, 500, "db", err.Error())
		return
	}
	if !villageOK {
		writeErr(w, 400, "bad_village", "Choose the village.")
		return
	}
	for i, l := range in.Lands {
		if !uuidRe.MatchString(l.ID) || strings.TrimSpace(l.SurveyNo) == "" || l.AreaHa <= 0 {
			writeErr(w, 400, "bad_land", fmt.Sprintf("Land %d: survey number and area are needed.", i+1))
			return
		}
		if l.RegisterAcres <= 0 || math.Abs(l.RegisterAcres*4-math.Round(l.RegisterAcres*4)) > 1e-6 {
			writeErr(w, 400, "bad_land", fmt.Sprintf("Land %d: register in steps of 0.25 acre.", i+1))
			return
		}
		if l.RegisterAcres > MaxRegisterAcres(l.AreaHa)+1e-6 {
			writeErr(w, 400, "bad_land", fmt.Sprintf("Land %d: can't register more than the area on the land record.", i+1))
			return
		}
		if l.OwnerType != "farmer" && l.OwnerType != "family" {
			writeErr(w, 400, "bad_land", "Owner must be farmer or family.")
			return
		}
	}

	tx, err := s.db.Begin(ctx)
	if err != nil {
		writeErr(w, 500, "db", err.Error())
		return
	}
	defer tx.Rollback(ctx)

	var existingOwner string
	var existingNumber int
	err = tx.QueryRow(ctx, `SELECT surveyor_id, number FROM farmers WHERE id=$1`, id).Scan(&existingOwner, &existingNumber)
	isNew := errors.Is(err, pgx.ErrNoRows)
	if err != nil && !isNew {
		writeErr(w, 500, "db", err.Error())
		return
	}
	if !isNew && existingOwner != sv.ID {
		writeErr(w, 403, "forbidden", "This farmer belongs to another surveyor.")
		return
	}
	number := existingNumber
	if isNew {
		number = in.Number
		var reservedBy string
		err := tx.QueryRow(ctx, `SELECT surveyor_id FROM farmer_numbers WHERE number=$1`, number).Scan(&reservedBy)
		var taken bool
		tx.QueryRow(ctx, `SELECT EXISTS(SELECT 1 FROM farmers WHERE number=$1)`, number).Scan(&taken)
		if err != nil || reservedBy != sv.ID || taken {
			// Phone had no valid reserved number: allocate one now.
			if err := tx.QueryRow(ctx, `INSERT INTO farmer_numbers(number, surveyor_id) VALUES (nextval('farmer_number_seq'), $1) RETURNING number`, sv.ID).Scan(&number); err != nil {
				writeErr(w, 500, "db", err.Error())
				return
			}
		}
	}
	code := FarmerCode(number, in.FullName)
	_, err = tx.Exec(ctx, `
		INSERT INTO farmers(id, number, code, surveyor_id, full_name, mobile, whatsapp_same, whatsapp, other_mobile,
			village_id, tree_interest, aadhaar_name_match, aadhaar_consent, client_created_at)
		VALUES ($1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$11,$12,$13,$14)
		ON CONFLICT (id) DO UPDATE SET code=EXCLUDED.code, full_name=EXCLUDED.full_name, mobile=EXCLUDED.mobile,
			whatsapp_same=EXCLUDED.whatsapp_same, whatsapp=EXCLUDED.whatsapp, other_mobile=EXCLUDED.other_mobile,
			village_id=EXCLUDED.village_id, tree_interest=EXCLUDED.tree_interest,
			aadhaar_name_match=EXCLUDED.aadhaar_name_match, aadhaar_consent=EXCLUDED.aadhaar_consent, updated_at=now()`,
		id, number, code, sv.ID, in.FullName, in.Mobile, in.WhatsappSame, in.Whatsapp, in.OtherMobile,
		in.VillageID, in.TreeInterest, in.AadhaarNameMatch, in.AadhaarConsent, in.CreatedAt)
	if err != nil {
		writeErr(w, 500, "db", err.Error())
		return
	}
	keep := make([]string, 0, len(in.Lands))
	for i, l := range in.Lands {
		keep = append(keep, l.ID)
		_, err := tx.Exec(ctx, `
			INSERT INTO lands(id, farmer_id, idx, survey_no, area_ha, register_acres, owner_type, records_consent)
			VALUES ($1,$2,$3,$4,$5,$6,$7,$8)
			ON CONFLICT (id) DO UPDATE SET idx=EXCLUDED.idx, survey_no=EXCLUDED.survey_no, area_ha=EXCLUDED.area_ha,
				register_acres=EXCLUDED.register_acres, owner_type=EXCLUDED.owner_type, records_consent=EXCLUDED.records_consent`,
			l.ID, id, i, strings.TrimSpace(l.SurveyNo), l.AreaHa, l.RegisterAcres, l.OwnerType, l.RecordsConsent)
		if err != nil {
			writeErr(w, 500, "db", err.Error())
			return
		}
	}
	if _, err := tx.Exec(ctx, `DELETE FROM lands WHERE farmer_id=$1 AND NOT (id = ANY($2::uuid[]))`, id, keep); err != nil {
		writeErr(w, 500, "db", err.Error())
		return
	}
	if isNew {
		if _, err := tx.Exec(ctx, `INSERT INTO action_items(surveyor_id, farmer_id, kind) VALUES ($1,$2,'walk_boundary') ON CONFLICT DO NOTHING`, sv.ID, id); err != nil {
			writeErr(w, 500, "db", err.Error())
			return
		}
	}
	if err := tx.Commit(ctx); err != nil {
		writeErr(w, 500, "db", err.Error())
		return
	}
	f, err := s.loadFarmer(ctx, id)
	if err != nil {
		writeErr(w, 500, "db", err.Error())
		return
	}
	status := 200
	if isNew {
		status = 201
	}
	writeJSON(w, status, f)
}

func (s *Server) loadFarmer(ctx context.Context, id string) (*FarmerOut, error) {
	var f FarmerOut
	var details []byte
	err := s.db.QueryRow(ctx, `
		SELECT f.id, f.number, f.code, f.full_name, f.mobile, f.whatsapp_same, f.whatsapp, f.other_mobile,
			v.id, v.name, v.block, v.district, f.tree_interest, f.aadhaar_name_match, f.aadhaar_consent,
			f.status, f.farm_details, f.created_at, f.submitted_at
		FROM farmers f JOIN villages v ON v.id=f.village_id WHERE f.id=$1`, id).Scan(
		&f.ID, &f.Number, &f.Code, &f.FullName, &f.Mobile, &f.WhatsappSame, &f.Whatsapp, &f.OtherMobile,
		&f.Village.ID, &f.Village.Name, &f.Village.Block, &f.Village.District, &f.TreeInterest,
		&f.AadhaarNameMatch, &f.AadhaarConsent, &f.Status, &details, &f.CreatedAt, &f.SubmittedAt)
	if err != nil {
		return nil, err
	}
	if details != nil {
		f.FarmDetails = details
	}
	rows, _ := s.db.Query(ctx, `SELECT id, idx, survey_no, area_ha::float8, register_acres::float8, owner_type, records_consent FROM lands WHERE farmer_id=$1 ORDER BY idx`, id)
	f.Lands, err = pgx.CollectRows(rows, func(row pgx.CollectableRow) (LandOut, error) {
		var l LandOut
		err := row.Scan(&l.ID, &l.Idx, &l.SurveyNo, &l.AreaHa, &l.RegisterAcres, &l.OwnerType, &l.RecordsConsent)
		return l, err
	})
	if err != nil {
		return nil, err
	}
	rows, _ = s.db.Query(ctx, `SELECT id, kind, land_id::text, mime, lat, lng, captured_at FROM files WHERE farmer_id=$1 ORDER BY created_at`, id)
	f.Files, err = pgx.CollectRows(rows, func(row pgx.CollectableRow) (FileOut, error) {
		var x FileOut
		err := row.Scan(&x.ID, &x.Kind, &x.LandID, &x.Mime, &x.Lat, &x.Lng, &x.CapturedAt)
		return x, err
	})
	if err != nil {
		return nil, err
	}
	f.Boundaries, err = s.activeBoundaries(ctx, id)
	return &f, err
}

func (s *Server) activeBoundaries(ctx context.Context, farmerID string) ([]BoundaryOut, error) {
	rows, _ := s.db.Query(ctx, `
		SELECT id, land_id::text, points, area_m2, point_count, accuracy_m, walked_m, finished_at
		FROM boundaries WHERE farmer_id=$1 AND status='active' AND purpose='onboarding' ORDER BY created_at`, farmerID)
	return pgx.CollectRows(rows, func(row pgx.CollectableRow) (BoundaryOut, error) {
		var b BoundaryOut
		var pts []byte
		err := row.Scan(&b.ID, &b.LandID, &pts, &b.AreaM2, &b.PointCount, &b.AccuracyM, &b.WalkedM, &b.FinishedAt)
		if err == nil {
			err = json.Unmarshal(pts, &b.Points)
		}
		return b, err
	})
}

func (s *Server) handleGetFarmer(w http.ResponseWriter, r *http.Request) {
	id := r.PathValue("id")
	if !uuidRe.MatchString(id) {
		writeErr(w, 404, "not_found", "Farmer not found.")
		return
	}
	ok, err := s.ownsFarmer(r.Context(), surveyorFrom(r).ID, id)
	if err != nil || !ok {
		writeErr(w, 404, "not_found", "Farmer not found.")
		return
	}
	f, err := s.loadFarmer(r.Context(), id)
	if err != nil {
		writeErr(w, 500, "db", err.Error())
		return
	}
	writeJSON(w, 200, f)
}

type FarmerSummary struct {
	ID         string    `json:"id"`
	Code       string    `json:"code"`
	FullName   string    `json:"fullName"`
	Mobile     string    `json:"mobile"`
	Village    string    `json:"village"`
	Status     string    `json:"status"`
	Lands      int       `json:"lands"`
	Acres      float64   `json:"acres"`
	Boundaries int       `json:"boundaries"`
	AreaM2     float64   `json:"areaM2"`
	CreatedAt  time.Time `json:"createdAt"`
}

func (s *Server) handleListFarmers(w http.ResponseWriter, r *http.Request) {
	rows, err := s.db.Query(r.Context(), `
		SELECT f.id, f.code, f.full_name, f.mobile, v.name, f.status,
			(SELECT count(*) FROM lands l WHERE l.farmer_id=f.id),
			(SELECT coalesce(sum(register_acres),0)::float8 FROM lands l WHERE l.farmer_id=f.id),
			(SELECT count(*) FROM boundaries b WHERE b.farmer_id=f.id AND b.status='active' AND b.purpose='onboarding'),
			(SELECT coalesce(sum(area_m2),0) FROM boundaries b WHERE b.farmer_id=f.id AND b.status='active' AND b.purpose='onboarding'),
			f.created_at
		FROM farmers f JOIN villages v ON v.id=f.village_id
		WHERE f.surveyor_id=$1 ORDER BY f.created_at DESC`, surveyorFrom(r).ID)
	if err != nil {
		writeErr(w, 500, "db", err.Error())
		return
	}
	list, err := pgx.CollectRows(rows, func(row pgx.CollectableRow) (FarmerSummary, error) {
		var f FarmerSummary
		err := row.Scan(&f.ID, &f.Code, &f.FullName, &f.Mobile, &f.Village, &f.Status, &f.Lands, &f.Acres, &f.Boundaries, &f.AreaM2, &f.CreatedAt)
		return f, err
	})
	if err != nil {
		writeErr(w, 500, "db", err.Error())
		return
	}
	writeJSON(w, 200, list)
}

var fileKinds = map[string]bool{
	"aadhaar_front": true, "aadhaar_back": true, "land_record": true, "land_record_pdf": true,
	"water_source": true, "agreement": true, "plantation_proof": true,
	"payment_proof": true, "sapling_delivery": true, "plant_pits": true, "plant_planted": true, "land_change_record": true,
}

func optFloat(v string) *float64 {
	if v == "" {
		return nil
	}
	f, err := strconv.ParseFloat(v, 64)
	if err != nil || math.IsNaN(f) {
		return nil
	}
	return &f
}

func (s *Server) handleUploadFile(w http.ResponseWriter, r *http.Request) {
	farmerID := r.PathValue("id")
	ctx := r.Context()
	if ok, err := s.ownsFarmer(ctx, surveyorFrom(r).ID, farmerID); err != nil || !ok {
		writeErr(w, 404, "not_found", "Farmer not found. Sync the farmer first.")
		return
	}
	r.Body = http.MaxBytesReader(w, r.Body, 25<<20)
	if err := r.ParseMultipartForm(4 << 20); err != nil {
		writeErr(w, 400, "bad_upload", err.Error())
		return
	}
	id := r.FormValue("id")
	kind := r.FormValue("kind")
	if !uuidRe.MatchString(id) || !fileKinds[kind] {
		writeErr(w, 400, "bad_upload", "id must be a UUID and kind must be known.")
		return
	}
	var exists bool
	s.db.QueryRow(ctx, `SELECT EXISTS(SELECT 1 FROM files WHERE id=$1)`, id).Scan(&exists)
	if exists {
		writeJSON(w, 200, map[string]any{"id": id, "duplicate": true})
		return
	}
	file, hdr, err := r.FormFile("file")
	if err != nil {
		writeErr(w, 400, "bad_upload", "file is missing")
		return
	}
	defer file.Close()
	mime := hdr.Header.Get("Content-Type")
	ext := ".jpg"
	switch {
	case strings.Contains(mime, "pdf"):
		ext = ".pdf"
	case strings.Contains(mime, "png"):
		ext = ".png"
	}
	dir := filepath.Join(s.cfg.UploadDir, farmerID)
	if err := os.MkdirAll(dir, 0o755); err != nil {
		writeErr(w, 500, "storage", err.Error())
		return
	}
	path := filepath.Join(dir, id+ext)
	out, err := os.Create(path)
	if err != nil {
		writeErr(w, 500, "storage", err.Error())
		return
	}
	h := sha256.New()
	n, err := io.Copy(io.MultiWriter(out, h), file)
	out.Close()
	if err != nil {
		os.Remove(path)
		writeErr(w, 500, "storage", err.Error())
		return
	}
	var landID *string
	if l := r.FormValue("landId"); uuidRe.MatchString(l) {
		landID = &l
	}
	var captured *time.Time
	if t, err := time.Parse(time.RFC3339, r.FormValue("capturedAt")); err == nil {
		captured = &t
	}
	_, err = s.db.Exec(ctx, `
		INSERT INTO files(id, farmer_id, land_id, kind, path, mime, size_bytes, sha256, lat, lng, accuracy_m, captured_at, sharpness)
		VALUES ($1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$11,$12,$13) ON CONFLICT (id) DO NOTHING`,
		id, farmerID, landID, kind, path, mime, n, hex.EncodeToString(h.Sum(nil)),
		optFloat(r.FormValue("lat")), optFloat(r.FormValue("lng")), optFloat(r.FormValue("accuracy")),
		captured, optFloat(r.FormValue("sharpness")))
	if err != nil {
		writeErr(w, 500, "db", err.Error())
		return
	}
	s.markReuploaded(ctx, farmerID, docForFileKind(kind), surveyorFrom(r).ID)
	s.markPlantPhoto(ctx, farmerID, kind, surveyorFrom(r).ID)
	writeJSON(w, 201, map[string]any{"id": id, "bytes": n})
}

type FarmDetails struct {
	WaterSources  []string            `json:"waterSources"`
	WaterPhotoID  string              `json:"waterPhotoId"`
	TimberSpecies []string            `json:"timberSpecies"`
	FruitSpecies  []string            `json:"fruitSpecies"`
	TimberPercent int                 `json:"timberPercent"`
	FruitPercent  int                 `json:"fruitPercent"`
	LandUse       []string            `json:"landUse"`
	Kharif        map[string][]string `json:"kharif"`
	KharifSame    bool                `json:"kharifSame"`
	Rabi          map[string][]string `json:"rabi"`
	RabiSame      bool                `json:"rabiSame"`
}

func (s *Server) handleFarmDetails(w http.ResponseWriter, r *http.Request) {
	farmerID := r.PathValue("id")
	sv := surveyorFrom(r)
	ctx := r.Context()
	if ok, err := s.ownsFarmer(ctx, sv.ID, farmerID); err != nil || !ok {
		writeErr(w, 404, "not_found", "Farmer not found.")
		return
	}
	var d FarmDetails
	if !readJSON(w, r, &d) {
		return
	}
	switch {
	case len(d.WaterSources) == 0:
		writeErr(w, 400, "bad_details", "Choose the water source.")
		return
	case d.TimberPercent < 0 || d.FruitPercent < 0 || d.TimberPercent+d.FruitPercent != 100:
		writeErr(w, 400, "bad_details", "Timber and fruit must add up to 100%.")
		return
	case len(d.LandUse) == 0:
		writeErr(w, 400, "bad_details", "Choose how the land is used today.")
		return
	}
	var n int
	s.db.QueryRow(ctx, `SELECT count(*) FROM boundaries WHERE farmer_id=$1 AND status='active' AND purpose='onboarding'`, farmerID).Scan(&n)
	if n == 0 {
		writeErr(w, 409, "no_boundary", "Walk the farm boundary before submitting farm details.")
		return
	}
	raw, _ := json.Marshal(d)
	if _, err := s.db.Exec(ctx, `UPDATE farmers SET farm_details=$1, status='submitted', submitted_at=now(), updated_at=now() WHERE id=$2`, raw, farmerID); err != nil {
		writeErr(w, 500, "db", err.Error())
		return
	}
	s.db.Exec(ctx, `UPDATE action_items SET status='done', done_at=now() WHERE farmer_id=$1 AND kind='walk_boundary' AND status='open'`, farmerID)
	s.fillStatesSoon() // the farmer now shows on the dashboard: make sure the state is known
	writeJSON(w, 200, map[string]any{"status": "submitted"})
}

type ActionItem struct {
	ID           string    `json:"id"`
	Kind         string    `json:"kind"`
	FarmerID     string    `json:"farmerId"`
	FarmerName   string    `json:"farmerName"`
	FarmerCode   string    `json:"farmerCode"`
	Village      string    `json:"village"`
	Note         string    `json:"note"`
	Lands        int       `json:"lands"`
	Doc          string    `json:"doc"`
	ReviewerName string    `json:"reviewerName"`
	Decision     string    `json:"decision"`
	Attention    bool      `json:"attention"` // sent back by a reviewer: red dot on the phone
	UpdatedAt    time.Time `json:"updatedAt"`
	// Review status of all four documents of this farmer (review tasks only).
	Docs map[string]string `json:"docs,omitempty"`
}

func (s *Server) handleActionItems(w http.ResponseWriter, r *http.Request) {
	rows, err := s.db.Query(r.Context(), `
		SELECT a.id, a.kind, f.id, f.full_name, f.code, v.name, a.note,
			(SELECT count(*) FROM lands l WHERE l.farmer_id=f.id),
			coalesce(a.doc,''), a.reviewer_name, a.decision, a.updated_at,
			ds.aadhaar, ds.land_record, ds.water_source, ds.boundary
		FROM action_items a JOIN farmers f ON f.id=a.farmer_id JOIN villages v ON v.id=f.village_id
		JOIN farmer_doc_status ds ON ds.farmer_id=f.id
		WHERE a.surveyor_id=$1 AND a.status='open'
		ORDER BY (a.kind LIKE 'fix\_%') DESC,
			array_position(ARRAY['fix_aadhaar','fix_land_record','fix_water_source','fix_boundary','walk_boundary','retake_aadhaar','collect_agreement','plantation_proof'], a.kind),
			a.updated_at DESC`, surveyorFrom(r).ID)
	if err != nil {
		writeErr(w, 500, "db", err.Error())
		return
	}
	items, err := pgx.CollectRows(rows, func(row pgx.CollectableRow) (ActionItem, error) {
		var a ActionItem
		var da, dl, dw, db string
		err := row.Scan(&a.ID, &a.Kind, &a.FarmerID, &a.FarmerName, &a.FarmerCode, &a.Village, &a.Note, &a.Lands,
			&a.Doc, &a.ReviewerName, &a.Decision, &a.UpdatedAt, &da, &dl, &dw, &db)
		a.Attention = strings.HasPrefix(a.Kind, "fix_")
		if a.Attention {
			a.Docs = map[string]string{"aadhaar": da, "land_record": dl, "water_source": dw, "boundary": db}
		}
		return a, err
	})
	if err != nil {
		writeErr(w, 500, "db", err.Error())
		return
	}
	plan, err := s.planActionItems(r.Context(), surveyorFrom(r).ID)
	if err != nil {
		writeErr(w, 500, "db", err.Error())
		return
	}
	// Sent-back steps first, then the rest; plan items after the onboarding fixes.
	var fixes, rest []ActionItem
	for _, it := range plan {
		if it.Attention {
			fixes = append(fixes, it)
		} else {
			rest = append(rest, it)
		}
	}
	n := 0
	for n < len(items) && items[n].Attention {
		n++
	}
	out := append([]ActionItem{}, items[:n]...)
	out = append(out, fixes...)
	out = append(out, items[n:]...)
	out = append(out, rest...)
	writeJSON(w, 200, out)
}

func (s *Server) handleCompleteAction(w http.ResponseWriter, r *http.Request) {
	id := r.PathValue("id")
	if !uuidRe.MatchString(id) {
		writeErr(w, 400, "bad_id", "Action item id must be a UUID.")
		return
	}
	ctx := r.Context()
	var kind, farmerID, status string
	err := s.db.QueryRow(ctx, `SELECT kind, farmer_id, status FROM action_items WHERE id=$1 AND surveyor_id=$2`, id, surveyorFrom(r).ID).
		Scan(&kind, &farmerID, &status)
	if errors.Is(err, pgx.ErrNoRows) {
		writeErr(w, 404, "not_found", "Action item not found.")
		return
	} else if err != nil {
		writeErr(w, 500, "db", err.Error())
		return
	}
	if status != "open" {
		writeJSON(w, 200, map[string]any{"updated": 0})
		return
	}
	if strings.HasPrefix(kind, "fix_") {
		ok, err := s.fixHasNewEvidence(ctx, id, farmerID, kind)
		if err != nil {
			writeErr(w, 500, "db", err.Error())
			return
		}
		if !ok {
			writeErr(w, 409, "nothing_new", "Send the new photo or boundary first, then mark this done.")
			return
		}
	}
	tag, err := s.db.Exec(ctx, `UPDATE action_items SET status='done', done_at=now() WHERE id=$1 AND status='open'`, id)
	if err != nil {
		writeErr(w, 500, "db", err.Error())
		return
	}
	writeJSON(w, 200, map[string]any{"updated": tag.RowsAffected()})
}
