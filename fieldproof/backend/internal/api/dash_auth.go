package api

import (
	"context"
	"crypto/pbkdf2"
	"crypto/rand"
	"crypto/sha256"
	"crypto/subtle"
	"encoding/base64"
	"errors"
	"fmt"
	"log"
	"math/big"
	"net/http"
	"regexp"
	"strconv"
	"strings"
	"time"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"
)

// Dashboard sign-in: unique username + password, issued by someone higher up.
// Sessions are an HttpOnly cookie; every request is scoped to the signed-in
// person's place in the hierarchy (see dash_scope.go).

const (
	dashCookie       = "fp_dash"
	dashSessionTTL   = 12 * time.Hour
	dashLongTTL      = 30 * 24 * time.Hour
	pbkdf2Iterations = 210_000
	maxFailedLogins  = 8
	lockoutFor       = 15 * time.Minute
)

var usernameRe = regexp.MustCompile(`^[a-z0-9][a-z0-9.]{1,30}[a-z0-9]$`)

// ValidUsername: 3-32 characters, small letters, numbers and dots; no dot at
// either end and no two dots in a row.
func ValidUsername(u string) bool { return usernameRe.MatchString(u) && !strings.Contains(u, "..") }

func HashPassword(pw string) string {
	salt := make([]byte, 16)
	if _, err := rand.Read(salt); err != nil {
		panic(err)
	}
	key, err := pbkdf2.Key(sha256.New, pw, salt, pbkdf2Iterations, 32)
	if err != nil {
		panic(err)
	}
	return fmt.Sprintf("pbkdf2-sha256$%d$%s$%s", pbkdf2Iterations,
		base64.RawStdEncoding.EncodeToString(salt), base64.RawStdEncoding.EncodeToString(key))
}

func CheckPassword(hash, pw string) bool {
	p := strings.Split(hash, "$")
	if len(p) != 4 || p[0] != "pbkdf2-sha256" {
		return false
	}
	iter, err := strconv.Atoi(p[1])
	if err != nil || iter < 1 {
		return false
	}
	salt, err1 := base64.RawStdEncoding.DecodeString(p[2])
	want, err2 := base64.RawStdEncoding.DecodeString(p[3])
	if err1 != nil || err2 != nil {
		return false
	}
	got, err := pbkdf2.Key(sha256.New, pw, salt, iter, len(want))
	return err == nil && subtle.ConstantTimeCompare(got, want) == 1
}

// TempPassword is shown once to whoever issues a login. No look-alike characters.
func TempPassword() string {
	const alpha = "abcdefghjkmnpqrstuvwxyzABCDEFGHJKMNPQRSTUVWXYZ23456789"
	b := make([]byte, 10)
	for i := range b {
		n, err := rand.Int(rand.Reader, big.NewInt(int64(len(alpha))))
		if err != nil {
			panic(err)
		}
		b[i] = alpha[n.Int64()]
	}
	return string(b[:5]) + "-" + string(b[5:])
}

func passwordProblem(pw, username string) string {
	switch {
	case len([]rune(pw)) < 8:
		return "Use at least 8 characters."
	case len(pw) > 128:
		return "Use at most 128 characters."
	case strings.EqualFold(pw, username):
		return "The password can't be the same as the username."
	}
	return ""
}

type DashUser struct {
	ID         string   `json:"id"`
	Username   string   `json:"username"`
	Role       string   `json:"role"`
	FullName   string   `json:"fullName"`
	Mobile     string   `json:"mobile"`
	State      *string  `json:"state"`
	District   string   `json:"district"`
	Blocks     []string `json:"blocks"`
	NgoID      *string  `json:"ngoId"`
	MustChange bool     `json:"mustChangePassword"`
}

func (u *DashUser) StateName() string {
	if u.State == nil {
		return ""
	}
	return *u.State
}

const dashUserKey ctxKey = 2

func dashUserFrom(r *http.Request) *DashUser { return r.Context().Value(dashUserKey).(*DashUser) }

const dashUserCols = `u.id, u.username, u.role, u.full_name, u.mobile, u.state, u.district, u.blocks, u.ngo_id, u.must_change_password`

func scanDashUser(row pgx.Row) (*DashUser, error) {
	var u DashUser
	err := row.Scan(&u.ID, &u.Username, &u.Role, &u.FullName, &u.Mobile, &u.State, &u.District, &u.Blocks, &u.NgoID, &u.MustChange)
	if u.Blocks == nil {
		u.Blocks = []string{}
	}
	return &u, err
}

// dash wraps dashboard handlers: valid session, active login, and (unless
// allowPending) a password the person has set themselves.
func (s *Server) dash(h http.HandlerFunc) http.HandlerFunc { return s.dashWith(false, h) }

func (s *Server) dashWith(allowPending bool, h http.HandlerFunc) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		c, err := r.Cookie(dashCookie)
		if err != nil || c.Value == "" {
			writeErr(w, 401, "unauthorized", "Please sign in.")
			return
		}
		if r.Method != http.MethodGet && r.Method != http.MethodHead && r.Header.Get("X-Fieldproof") != "1" {
			// Cross-site forms can't set custom headers.
			writeErr(w, 403, "csrf", "Request blocked.")
			return
		}
		u, err := scanDashUser(s.db.QueryRow(r.Context(), `
			SELECT `+dashUserCols+` FROM dash_sessions x JOIN users u ON u.id = x.user_id
			WHERE x.token_hash = $1 AND x.expires_at > now() AND u.active AND u.role <> 'surveyor'`, sha(c.Value)))
		if errors.Is(err, pgx.ErrNoRows) {
			clearDashCookie(w, r)
			writeErr(w, 401, "unauthorized", "Your session has ended. Please sign in again.")
			return
		} else if err != nil {
			writeErr(w, 500, "db", err.Error())
			return
		}
		if u.MustChange && !allowPending {
			writeErr(w, 403, "password_change_required", "Set your own password first.")
			return
		}
		h(w, r.WithContext(context.WithValue(r.Context(), dashUserKey, u)))
	}
}

func secureRequest(r *http.Request) bool {
	return r.TLS != nil || r.Header.Get("X-Forwarded-Proto") == "https"
}

func clearDashCookie(w http.ResponseWriter, r *http.Request) {
	http.SetCookie(w, &http.Cookie{Name: dashCookie, Value: "", Path: "/", MaxAge: -1, HttpOnly: true, Secure: secureRequest(r), SameSite: http.SameSiteLaxMode})
}

func (s *Server) handleDashLogin(w http.ResponseWriter, r *http.Request) {
	var in struct {
		Username string `json:"username"`
		Password string `json:"password"`
		Remember bool   `json:"remember"`
	}
	if !readJSON(w, r, &in) {
		return
	}
	username := strings.ToLower(strings.TrimSpace(in.Username))
	if username == "" || in.Password == "" {
		writeErr(w, 400, "missing", "Enter your username and password.")
		return
	}
	ctx := r.Context()
	var id, hash, role string
	var active bool
	var locked *time.Time
	err := s.db.QueryRow(ctx, `SELECT id, password_hash, role, active, locked_until FROM users WHERE lower(username)=$1`, username).
		Scan(&id, &hash, &role, &active, &locked)
	if errors.Is(err, pgx.ErrNoRows) {
		CheckPassword(HashPassword("x"), in.Password) // same time as a real check
		writeErr(w, 401, "bad_login", "Username or password is not right.")
		return
	} else if err != nil {
		writeErr(w, 500, "db", err.Error())
		return
	}
	if locked != nil && locked.After(time.Now()) {
		writeErr(w, 429, "locked", fmt.Sprintf("Too many wrong tries. Try again in %d minutes.", int(time.Until(*locked).Minutes())+1))
		return
	}
	if !CheckPassword(hash, in.Password) {
		s.db.Exec(ctx, `UPDATE users SET
			locked_until = CASE WHEN failed_logins + 1 >= $2 THEN now() + $3::interval ELSE NULL END,
			failed_logins = CASE WHEN failed_logins + 1 >= $2 THEN 0 ELSE failed_logins + 1 END
			WHERE id=$1`, id, maxFailedLogins, fmt.Sprintf("%d seconds", int(lockoutFor.Seconds())))
		writeErr(w, 401, "bad_login", "Username or password is not right.")
		return
	}
	if !active {
		writeErr(w, 403, "inactive", "This login has been turned off. Ask whoever issued it.")
		return
	}
	if role == "surveyor" {
		writeErr(w, 403, "surveyor", "Surveyors sign in on the Fieldproof phone app with their mobile number.")
		return
	}
	ttl := dashSessionTTL
	if in.Remember {
		ttl = dashLongTTL
	}
	token := randomHex(32)
	if _, err := s.db.Exec(ctx, `INSERT INTO dash_sessions(token_hash, user_id, expires_at) VALUES ($1,$2,$3)`, sha(token), id, time.Now().Add(ttl)); err != nil {
		writeErr(w, 500, "db", err.Error())
		return
	}
	s.db.Exec(ctx, `UPDATE users SET failed_logins=0, locked_until=NULL, last_login_at=now() WHERE id=$1`, id)
	s.db.Exec(ctx, `DELETE FROM dash_sessions WHERE expires_at < now()`)
	ck := &http.Cookie{Name: dashCookie, Value: token, Path: "/", HttpOnly: true, Secure: secureRequest(r), SameSite: http.SameSiteLaxMode}
	if in.Remember {
		ck.MaxAge = int(ttl.Seconds())
	}
	http.SetCookie(w, ck)
	u, err := scanDashUser(s.db.QueryRow(ctx, `SELECT `+dashUserCols+` FROM users u WHERE u.id=$1`, id))
	if err != nil {
		writeErr(w, 500, "db", err.Error())
		return
	}
	writeJSON(w, 200, u)
}

func (s *Server) handleDashLogout(w http.ResponseWriter, r *http.Request) {
	if c, err := r.Cookie(dashCookie); err == nil {
		s.db.Exec(r.Context(), `DELETE FROM dash_sessions WHERE token_hash=$1`, sha(c.Value))
	}
	clearDashCookie(w, r)
	writeJSON(w, 200, map[string]any{"ok": true})
}

func (s *Server) handleDashMe(w http.ResponseWriter, r *http.Request) {
	writeJSON(w, 200, dashUserFrom(r))
}

func (s *Server) handleDashPassword(w http.ResponseWriter, r *http.Request) {
	var in struct {
		Current string `json:"current"`
		New     string `json:"new"`
	}
	if !readJSON(w, r, &in) {
		return
	}
	u := dashUserFrom(r)
	ctx := r.Context()
	var hash string
	if err := s.db.QueryRow(ctx, `SELECT password_hash FROM users WHERE id=$1`, u.ID).Scan(&hash); err != nil {
		writeErr(w, 500, "db", err.Error())
		return
	}
	if !CheckPassword(hash, in.Current) {
		writeErr(w, 400, "bad_current", "Your current password is not right.")
		return
	}
	if p := passwordProblem(in.New, u.Username); p != "" {
		writeErr(w, 400, "weak_password", p)
		return
	}
	if in.New == in.Current {
		writeErr(w, 400, "same_password", "Choose a password different from the one you were given.")
		return
	}
	if _, err := s.db.Exec(ctx, `UPDATE users SET password_hash=$1, must_change_password=false WHERE id=$2`, HashPassword(in.New), u.ID); err != nil {
		writeErr(w, 500, "db", err.Error())
		return
	}
	// Sign out other browsers; keep this one.
	c, _ := r.Cookie(dashCookie)
	s.db.Exec(ctx, `DELETE FROM dash_sessions WHERE user_id=$1 AND token_hash<>$2`, u.ID, sha(c.Value))
	u.MustChange = false
	writeJSON(w, 200, u)
}

// EnsureAdmin creates the first admin login from ADMIN_USER / ADMIN_PASSWORD
// when there is none yet. Nothing changes once an admin exists.
func EnsureAdmin(ctx context.Context, db *pgxpool.Pool, username, password string) error {
	var n int
	if err := db.QueryRow(ctx, `SELECT count(*) FROM users WHERE role='admin'`).Scan(&n); err != nil || n > 0 {
		return err
	}
	username = strings.ToLower(strings.TrimSpace(username))
	if !ValidUsername(username) {
		return fmt.Errorf("ADMIN_USER %q is not a valid username", username)
	}
	if len(password) < 8 {
		log.Printf("warning: ADMIN_PASSWORD is shorter than 8 characters")
	}
	_, err := db.Exec(ctx, `INSERT INTO users(username, password_hash, role, full_name, must_change_password)
		VALUES ($1,$2,'admin','Fieldproof admin',false) ON CONFLICT DO NOTHING`, username, HashPassword(password))
	if err == nil {
		log.Printf("dashboard admin login created: %s", username)
	}
	return err
}
