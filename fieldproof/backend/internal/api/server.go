package api

import (
	"context"
	"crypto/rand"
	"crypto/sha256"
	"embed"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io/fs"
	"log"
	"net/http"
	"sort"
	"strings"
	"time"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"
)

type Config struct {
	DevMode       bool   // return OTP codes in API responses (no SMS gateway yet)
	UploadDir     string // where photos and PDFs are stored
	AdminUser     string
	AdminPassword string
	WebDir        string // serve the dashboard from this folder instead of the embedded copy (local development)
}

type Server struct {
	db  *pgxpool.Pool
	cfg Config
	mux *http.ServeMux
}

func New(db *pgxpool.Pool, cfg Config) *Server {
	s := &Server{db: db, cfg: cfg, mux: http.NewServeMux()}
	s.routes()
	return s
}

func (s *Server) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	start := time.Now()
	rw := &statusWriter{ResponseWriter: w, status: 200}
	defer func() {
		if rec := recover(); rec != nil {
			log.Printf("panic %s %s: %v", r.Method, r.URL.Path, rec)
			writeErr(rw, 500, "internal", "Something went wrong on the server.")
		}
		log.Printf("%s %s %d %s", r.Method, r.URL.Path, rw.status, time.Since(start).Round(time.Millisecond))
	}()
	s.mux.ServeHTTP(rw, r)
}

type statusWriter struct {
	http.ResponseWriter
	status int
}

func (w *statusWriter) WriteHeader(code int) { w.status = code; w.ResponseWriter.WriteHeader(code) }

func (s *Server) routes() {
	m := s.mux
	m.HandleFunc("GET /healthz", func(w http.ResponseWriter, r *http.Request) {
		if err := s.db.Ping(r.Context()); err != nil {
			writeErr(w, 503, "db_down", err.Error())
			return
		}
		writeJSON(w, 200, map[string]any{"ok": true, "time": time.Now().UTC()})
	})

	m.HandleFunc("POST /v1/auth/otp/request", s.handleOTPRequest)
	m.HandleFunc("POST /v1/auth/otp/verify", s.handleOTPVerify)

	m.HandleFunc("GET /v1/me", s.auth(s.handleMe))
	m.HandleFunc("PUT /v1/me/language", s.auth(s.handleSetLanguage))
	m.HandleFunc("POST /v1/auth/logout", s.auth(s.handleLogout))
	m.HandleFunc("GET /v1/villages", s.auth(s.handleVillages))
	m.HandleFunc("POST /v1/farmer-numbers", s.auth(s.handleReserveNumbers))
	m.HandleFunc("GET /v1/places/locate", s.auth(s.handleLocate))
	m.HandleFunc("GET /v1/places/villages", s.auth(s.handlePlaceVillages))

	m.HandleFunc("GET /v1/farmers", s.auth(s.handleListFarmers))
	m.HandleFunc("GET /v1/farmers/{id}", s.auth(s.handleGetFarmer))
	m.HandleFunc("PUT /v1/farmers/{id}", s.auth(s.handlePutFarmer))
	m.HandleFunc("POST /v1/farmers/{id}/files", s.auth(s.handleUploadFile))
	m.HandleFunc("PUT /v1/farmers/{id}/farm-details", s.auth(s.handleFarmDetails))
	m.HandleFunc("GET /v1/farmers/{id}/kml", s.auth(s.handleFarmerKML))
	m.HandleFunc("GET /v1/farmers/{id}/plantation", s.auth(s.handlePlanState))
	m.HandleFunc("PUT /v1/farmers/{id}/payment", s.auth(s.handlePutPayment))
	m.HandleFunc("POST /v1/farmers/{id}/delivery", s.auth(s.handleDelivery))
	m.HandleFunc("POST /v1/farmers/{id}/planting-otp/request", s.auth(s.handlePlantOTPRequest))
	m.HandleFunc("POST /v1/farmers/{id}/planting-otp/verify", s.auth(s.handlePlantOTPVerify))
	m.HandleFunc("POST /v1/farmers/{id}/planted", s.auth(s.handlePlanted))

	m.HandleFunc("POST /v1/boundaries/check", s.auth(s.handleBoundaryCheck))
	m.HandleFunc("PUT /v1/farmers/{id}/boundaries/{bid}", s.auth(s.handlePutBoundary))
	m.HandleFunc("GET /v1/boundaries/{bid}/kml", s.auth(s.handleBoundaryKML))

	m.HandleFunc("GET /v1/action-items", s.auth(s.handleActionItems))
	m.HandleFunc("POST /v1/action-items/{id}/complete", s.auth(s.handleCompleteAction))

	s.dashRoutes()

	m.HandleFunc("GET /admin", s.admin(s.handleAdminHome))
	m.HandleFunc("GET /admin/farmers/{id}", s.admin(s.handleAdminFarmer))
	m.HandleFunc("GET /admin/farmers/{id}/kml", s.admin(s.handleAdminFarmerKML))
	m.HandleFunc("GET /admin/boundaries/{bid}/kml", s.admin(s.handleAdminBoundaryKML))
	m.HandleFunc("GET /admin/files/{fid}", s.admin(s.handleAdminFile))
	m.HandleFunc("POST /admin/surveyors", s.admin(s.handleAdminAddSurveyor))
	m.HandleFunc("GET /", func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path == "/" {
			http.Redirect(w, r, "/dash/", http.StatusFound)
			return
		}
		writeErr(w, 404, "not_found", "Not found.")
	})
}

// ---------- helpers ----------

type apiError struct {
	Error   string `json:"error"`
	Message string `json:"message"`
}

func writeJSON(w http.ResponseWriter, status int, v any) {
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(v)
}

func writeErr(w http.ResponseWriter, status int, code, msg string) {
	writeJSON(w, status, apiError{code, msg})
}

func readJSON(w http.ResponseWriter, r *http.Request, v any) bool {
	r.Body = http.MaxBytesReader(w, r.Body, 8<<20)
	dec := json.NewDecoder(r.Body)
	if err := dec.Decode(v); err != nil {
		writeErr(w, 400, "bad_json", "Could not read request: "+err.Error())
		return false
	}
	return true
}

func randomHex(n int) string {
	b := make([]byte, n)
	if _, err := rand.Read(b); err != nil {
		panic(err)
	}
	return hex.EncodeToString(b)
}

func sha(s string) string {
	h := sha256.Sum256([]byte(s))
	return hex.EncodeToString(h[:])
}

type ctxKey int

const surveyorKey ctxKey = 1

type Surveyor struct {
	ID       string   `json:"id"`
	Phone    string   `json:"phone"`
	Name     string   `json:"name"`
	District string   `json:"district"`
	Block    string   `json:"block"`
	Language string   `json:"language"`
	Blocks   []string `json:"blocks"`
}

func surveyorFrom(r *http.Request) *Surveyor { return r.Context().Value(surveyorKey).(*Surveyor) }

func (s *Server) auth(h http.HandlerFunc) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		tok := strings.TrimPrefix(r.Header.Get("Authorization"), "Bearer ")
		if tok == "" || tok == r.Header.Get("Authorization") {
			writeErr(w, 401, "unauthorized", "Please log in again.")
			return
		}
		var sv Surveyor
		err := s.db.QueryRow(r.Context(), `
			SELECT s.id, s.phone, s.name, s.district, s.home_block, s.language
			FROM sessions x JOIN surveyors s ON s.id = x.surveyor_id
			WHERE x.token_hash = $1 AND x.expires_at > now()
			AND NOT EXISTS (SELECT 1 FROM users u WHERE u.surveyor_id = s.id AND NOT u.active)`, sha(tok)).
			Scan(&sv.ID, &sv.Phone, &sv.Name, &sv.District, &sv.Block, &sv.Language)
		if errors.Is(err, pgx.ErrNoRows) {
			writeErr(w, 401, "unauthorized", "Please log in again.")
			return
		} else if err != nil {
			writeErr(w, 500, "db", err.Error())
			return
		}
		s.db.Exec(r.Context(), `UPDATE surveyors SET last_seen_at=now() WHERE id=$1 AND (last_seen_at IS NULL OR last_seen_at < now() - interval '1 minute')`, sv.ID)
		h(w, r.WithContext(context.WithValue(r.Context(), surveyorKey, &sv)))
	}
}

// ---------- migrations ----------

//go:embed migrations/*.sql
var migrationFS embed.FS

func Migrate(ctx context.Context, db *pgxpool.Pool) error {
	if _, err := db.Exec(ctx, `CREATE TABLE IF NOT EXISTS schema_migrations (name text PRIMARY KEY, applied_at timestamptz NOT NULL DEFAULT now())`); err != nil {
		return err
	}
	names, err := fs.Glob(migrationFS, "migrations/*.sql")
	if err != nil {
		return err
	}
	sort.Strings(names)
	for _, n := range names {
		var exists bool
		if err := db.QueryRow(ctx, `SELECT EXISTS(SELECT 1 FROM schema_migrations WHERE name=$1)`, n).Scan(&exists); err != nil {
			return err
		}
		if exists {
			continue
		}
		sqlText, _ := migrationFS.ReadFile(n)
		tx, err := db.Begin(ctx)
		if err != nil {
			return err
		}
		if _, err := tx.Exec(ctx, string(sqlText)); err != nil {
			tx.Rollback(ctx)
			return fmt.Errorf("%s: %w", n, err)
		}
		if _, err := tx.Exec(ctx, `INSERT INTO schema_migrations(name) VALUES ($1)`, n); err != nil {
			tx.Rollback(ctx)
			return err
		}
		if err := tx.Commit(ctx); err != nil {
			return err
		}
		log.Printf("migration applied: %s", n)
	}
	return nil
}
