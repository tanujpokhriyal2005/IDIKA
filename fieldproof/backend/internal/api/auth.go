package api

import (
	"crypto/rand"
	"errors"
	"fmt"
	"log"
	"math/big"
	"net/http"
	"time"

	"github.com/jackc/pgx/v5"
)

const (
	otpTTL        = 5 * time.Minute
	otpResend     = 30 * time.Second
	otpMaxAttempt = 5
	sessionTTL    = 90 * 24 * time.Hour
)

// NormalizePhone turns "+91 98765 43210" into "9876543210". Returns "" if invalid.
func NormalizePhone(in string) string {
	d := make([]byte, 0, len(in))
	for i := 0; i < len(in); i++ {
		if in[i] >= '0' && in[i] <= '9' {
			d = append(d, in[i])
		}
	}
	if len(d) == 12 && d[0] == '9' && d[1] == '1' {
		d = d[2:]
	}
	if len(d) == 11 && d[0] == '0' {
		d = d[1:]
	}
	if len(d) != 10 || d[0] < '6' {
		return ""
	}
	return string(d)
}

func (s *Server) handleOTPRequest(w http.ResponseWriter, r *http.Request) {
	var in struct {
		Phone string `json:"phone"`
	}
	if !readJSON(w, r, &in) {
		return
	}
	phone := NormalizePhone(in.Phone)
	if phone == "" {
		writeErr(w, 400, "bad_phone", "Enter a 10-digit mobile number.")
		return
	}
	ctx := r.Context()
	var exists bool
	if err := s.db.QueryRow(ctx, `SELECT EXISTS(SELECT 1 FROM surveyors s WHERE s.phone=$1
		AND NOT EXISTS (SELECT 1 FROM users u WHERE u.surveyor_id = s.id AND NOT u.active))`, phone).Scan(&exists); err != nil {
		writeErr(w, 500, "db", err.Error())
		return
	}
	if !exists {
		writeErr(w, 404, "not_registered", "This number is not registered. Ask your program manager to add you.")
		return
	}
	var sentAt time.Time
	err := s.db.QueryRow(ctx, `SELECT sent_at FROM otp_codes WHERE phone=$1`, phone).Scan(&sentAt)
	if err == nil && time.Since(sentAt) < otpResend {
		wait := int((otpResend - time.Since(sentAt)).Seconds()) + 1
		writeErr(w, 429, "wait", fmt.Sprintf("Wait %d seconds before asking for a new code.", wait))
		return
	}
	n, _ := rand.Int(rand.Reader, big.NewInt(10000))
	code := fmt.Sprintf("%04d", n.Int64())
	_, err = s.db.Exec(ctx, `
		INSERT INTO otp_codes(phone, code_hash, expires_at, attempts, sent_at)
		VALUES ($1, $2, $3, 0, now())
		ON CONFLICT (phone) DO UPDATE SET code_hash=EXCLUDED.code_hash, expires_at=EXCLUDED.expires_at, attempts=0, sent_at=now()`,
		phone, sha(phone+":"+code), time.Now().Add(otpTTL))
	if err != nil {
		writeErr(w, 500, "db", err.Error())
		return
	}
	// No SMS gateway yet: the code is logged, and returned to the app in dev mode
	// so it can fill the boxes the way SMS auto-read would.
	log.Printf("OTP for %s: %s", phone, code)
	resp := map[string]any{"resendAfter": int(otpResend.Seconds()), "expiresIn": int(otpTTL.Seconds())}
	if s.cfg.DevMode {
		resp["devCode"] = code
	}
	writeJSON(w, 200, resp)
}

func (s *Server) handleOTPVerify(w http.ResponseWriter, r *http.Request) {
	var in struct {
		Phone string `json:"phone"`
		Code  string `json:"code"`
	}
	if !readJSON(w, r, &in) {
		return
	}
	phone := NormalizePhone(in.Phone)
	ctx := r.Context()
	var hash string
	var exp time.Time
	var attempts int
	err := s.db.QueryRow(ctx, `SELECT code_hash, expires_at, attempts FROM otp_codes WHERE phone=$1`, phone).Scan(&hash, &exp, &attempts)
	if errors.Is(err, pgx.ErrNoRows) {
		writeErr(w, 400, "no_code", "Ask for a new code.")
		return
	} else if err != nil {
		writeErr(w, 500, "db", err.Error())
		return
	}
	if time.Now().After(exp) {
		writeErr(w, 400, "expired", "The code has expired. Ask for a new one.")
		return
	}
	if attempts >= otpMaxAttempt {
		writeErr(w, 400, "too_many", "Too many wrong tries. Ask for a new code.")
		return
	}
	if sha(phone+":"+in.Code) != hash {
		s.db.Exec(ctx, `UPDATE otp_codes SET attempts = attempts + 1 WHERE phone=$1`, phone)
		writeErr(w, 400, "wrong_code", "That code is not right. Check the SMS and try again.")
		return
	}
	s.db.Exec(ctx, `DELETE FROM otp_codes WHERE phone=$1`, phone)
	var sid string
	if err := s.db.QueryRow(ctx, `SELECT id FROM surveyors WHERE phone=$1`, phone).Scan(&sid); err != nil {
		writeErr(w, 500, "db", err.Error())
		return
	}
	token := randomHex(32)
	if _, err := s.db.Exec(ctx, `INSERT INTO sessions(token_hash, surveyor_id, expires_at) VALUES ($1,$2,$3)`,
		sha(token), sid, time.Now().Add(sessionTTL)); err != nil {
		writeErr(w, 500, "db", err.Error())
		return
	}
	sv, err := s.loadSurveyor(r, sid)
	if err != nil {
		writeErr(w, 500, "db", err.Error())
		return
	}
	writeJSON(w, 200, map[string]any{"token": token, "surveyor": sv})
}

func (s *Server) loadSurveyor(r *http.Request, id string) (*Surveyor, error) {
	var sv Surveyor
	err := s.db.QueryRow(r.Context(), `SELECT id, phone, name, district, home_block, language FROM surveyors WHERE id=$1`, id).
		Scan(&sv.ID, &sv.Phone, &sv.Name, &sv.District, &sv.Block, &sv.Language)
	if err != nil {
		return nil, err
	}
	rows, err := s.db.Query(r.Context(), `SELECT block FROM surveyor_blocks WHERE surveyor_id=$1 ORDER BY block`, id)
	if err != nil {
		return nil, err
	}
	sv.Blocks, err = pgx.CollectRows(rows, pgx.RowTo[string])
	return &sv, err
}

func (s *Server) handleMe(w http.ResponseWriter, r *http.Request) {
	sv, err := s.loadSurveyor(r, surveyorFrom(r).ID)
	if err != nil {
		writeErr(w, 500, "db", err.Error())
		return
	}
	writeJSON(w, 200, sv)
}

var languages = map[string]bool{"en": true, "hi": true, "mr": true}

func (s *Server) handleSetLanguage(w http.ResponseWriter, r *http.Request) {
	var in struct {
		Language string `json:"language"`
	}
	if !readJSON(w, r, &in) {
		return
	}
	if !languages[in.Language] {
		writeErr(w, 400, "bad_language", "Unsupported language.")
		return
	}
	if _, err := s.db.Exec(r.Context(), `UPDATE surveyors SET language=$1 WHERE id=$2`, in.Language, surveyorFrom(r).ID); err != nil {
		writeErr(w, 500, "db", err.Error())
		return
	}
	writeJSON(w, 200, map[string]any{"language": in.Language})
}

func (s *Server) handleLogout(w http.ResponseWriter, r *http.Request) {
	s.db.Exec(r.Context(), `DELETE FROM sessions WHERE surveyor_id=$1 AND token_hash=$2`,
		surveyorFrom(r).ID, sha(r.Header.Get("Authorization")[len("Bearer "):]))
	writeJSON(w, 200, map[string]any{"ok": true})
}

type Village struct {
	ID       int    `json:"id"`
	Name     string `json:"name"`
	Block    string `json:"block"`
	District string `json:"district"`
}

func (s *Server) handleVillages(w http.ResponseWriter, r *http.Request) {
	rows, err := s.db.Query(r.Context(), `
		SELECT v.id, v.name, v.block, v.district FROM villages v
		JOIN surveyor_blocks b ON b.district = v.district AND b.block = v.block
		WHERE b.surveyor_id = $1 ORDER BY v.block, v.name`, surveyorFrom(r).ID)
	if err != nil {
		writeErr(w, 500, "db", err.Error())
		return
	}
	vs, err := pgx.CollectRows(rows, func(row pgx.CollectableRow) (Village, error) {
		var v Village
		err := row.Scan(&v.ID, &v.Name, &v.Block, &v.District)
		return v, err
	})
	if err != nil {
		writeErr(w, 500, "db", err.Error())
		return
	}
	writeJSON(w, 200, vs)
}

func (s *Server) handleReserveNumbers(w http.ResponseWriter, r *http.Request) {
	var in struct {
		Count int `json:"count"`
	}
	if !readJSON(w, r, &in) {
		return
	}
	if in.Count <= 0 || in.Count > 50 {
		in.Count = 20
	}
	rows, err := s.db.Query(r.Context(), `
		INSERT INTO farmer_numbers(number, surveyor_id)
		SELECT nextval('farmer_number_seq'), $1 FROM generate_series(1, $2)
		RETURNING number`, surveyorFrom(r).ID, in.Count)
	if err != nil {
		writeErr(w, 500, "db", err.Error())
		return
	}
	nums, err := pgx.CollectRows(rows, pgx.RowTo[int32])
	if err != nil {
		writeErr(w, 500, "db", err.Error())
		return
	}
	writeJSON(w, 200, map[string]any{"numbers": nums})
}
