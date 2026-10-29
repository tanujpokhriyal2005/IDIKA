package api

import (
	"embed"
	"io/fs"
	"net/http"
	"os"
	"path"
	"strings"
)

//go:embed web
var webFS embed.FS

func (s *Server) dashRoutes() {
	m := s.mux
	m.HandleFunc("POST /v1/dash/login", csrfHeader(s.handleDashLogin))
	m.HandleFunc("POST /v1/dash/logout", csrfHeader(s.handleDashLogout))
	m.HandleFunc("GET /v1/dash/me", s.dashWith(true, s.handleDashMe))
	m.HandleFunc("POST /v1/dash/password", s.dashWith(true, s.handleDashPassword))

	m.HandleFunc("GET /v1/dash/counts", s.dash(s.handleDashCounts))
	m.HandleFunc("GET /v1/dash/options", s.dash(s.handleDashOptions))
	m.HandleFunc("GET /v1/dash/places", s.dash(s.handleDashPlaces))
	m.HandleFunc("GET /v1/dash/overview", s.dash(s.handleDashOverview))
	m.HandleFunc("GET /v1/dash/insights", s.dash(s.handleDashInsights))

	m.HandleFunc("GET /v1/dash/farmers", s.dash(s.handleDashFarmers))
	m.HandleFunc("GET /v1/dash/farmers.csv", s.dash(s.handleDashFarmersCSV))
	m.HandleFunc("GET /v1/dash/farmers.kml", s.dash(s.handleDashFarmersKML))
	m.HandleFunc("GET /v1/dash/farmers/{id}", s.dash(s.handleDashFarmer))
	m.HandleFunc("GET /v1/dash/farmers/{id}/kml", s.dash(s.handleDashFarmerKML))
	m.HandleFunc("POST /v1/dash/farmers/{id}/review", s.dash(s.handleDashReview))
	m.HandleFunc("POST /v1/dash/farmers/{id}/payment-review", s.dash(s.handleDashPaymentReview))
	m.HandleFunc("POST /v1/dash/farmers/{id}/plantation-review", s.dash(s.handleDashPlantationReview))
	m.HandleFunc("POST /v1/dash/farmers/{id}/land-change-review", s.dash(s.handleDashLandChangeReview))
	m.HandleFunc("GET /v1/dash/files/{fid}", s.dash(s.handleDashFile))

	m.HandleFunc("GET /v1/dash/land-checks", s.dash(s.handleDashLandChecks))
	m.HandleFunc("POST /v1/dash/land-checks", s.dash(s.handleDashLandCheckCreate))
	m.HandleFunc("GET /v1/dash/land-checks/{id}/report", s.dash(s.handleDashLandCheckReport))
	m.HandleFunc("POST /v1/dash/land-checks/{id}/pause", s.dash(s.handleDashLandCheckAction("pause")))
	m.HandleFunc("POST /v1/dash/land-checks/{id}/resume", s.dash(s.handleDashLandCheckAction("resume")))
	m.HandleFunc("DELETE /v1/dash/land-checks/{id}", s.dash(s.handleDashLandCheckAction("delete")))

	m.HandleFunc("GET /v1/dash/ngos", s.dash(s.handleDashNGOs))
	m.HandleFunc("GET /v1/dash/surveyors", s.dash(s.handleDashSurveyors))
	m.HandleFunc("GET /v1/dash/state-leads", s.dash(s.handleDashStateLeads))
	m.HandleFunc("GET /v1/dash/username", s.dash(s.handleDashUsernameCheck))
	m.HandleFunc("POST /v1/dash/users", s.dash(s.handleDashIssue))
	m.HandleFunc("POST /v1/dash/users/{id}/reset-password", s.dash(s.handleDashResetPassword))
	m.HandleFunc("POST /v1/dash/users/{id}/active", s.dash(s.handleDashSetActive))

	m.HandleFunc("GET /dash", func(w http.ResponseWriter, r *http.Request) { http.Redirect(w, r, "/dash/", http.StatusFound) })
	m.HandleFunc("GET /dash/", s.handleDashWeb)
}

func csrfHeader(h http.HandlerFunc) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		if r.Header.Get("X-Fieldproof") != "1" {
			writeErr(w, 403, "csrf", "Request blocked.")
			return
		}
		h(w, r)
	}
}

const dashCSP = "default-src 'self'; script-src 'self' https://unpkg.com; style-src 'self' 'unsafe-inline' https://unpkg.com https://fonts.googleapis.com; " +
	"font-src 'self' https://fonts.gstatic.com; img-src 'self' data: blob: https://server.arcgisonline.com https://*.tile.openstreetmap.org; " +
	"connect-src 'self'; frame-ancestors 'none'; base-uri 'self'; form-action 'self'"

// handleDashWeb serves the dashboard app. Unknown paths get index.html so a
// reload on any page works.
func (s *Server) handleDashWeb(w http.ResponseWriter, r *http.Request) {
	sub, _ := fs.Sub(webFS, "web")
	if s.cfg.WebDir != "" {
		sub = os.DirFS(s.cfg.WebDir)
	}
	p := strings.TrimPrefix(path.Clean(r.URL.Path), "/dash")
	p = strings.TrimPrefix(p, "/")
	w.Header().Set("Content-Security-Policy", dashCSP)
	w.Header().Set("X-Content-Type-Options", "nosniff")
	w.Header().Set("Referrer-Policy", "same-origin")
	if p != "" && p != "index.html" {
		if st, err := fs.Stat(sub, p); err == nil && !st.IsDir() {
			w.Header().Set("Cache-Control", "no-cache")
			http.ServeFileFS(w, r, sub, p)
			return
		}
	}
	w.Header().Set("Cache-Control", "no-store")
	http.ServeFileFS(w, r, sub, "index.html")
}
