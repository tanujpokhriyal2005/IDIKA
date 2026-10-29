package api

import (
	"crypto/subtle"
	"encoding/json"
	"html/template"
	"net/http"
	"os"
	"strings"

	"github.com/jackc/pgx/v5"
)

// The admin pages let a program manager see each walked boundary on a
// satellite map, download its KML, and add surveyors.

func (s *Server) admin(h http.HandlerFunc) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		u, p, ok := r.BasicAuth()
		if !ok || subtle.ConstantTimeCompare([]byte(u), []byte(s.cfg.AdminUser)) != 1 ||
			subtle.ConstantTimeCompare([]byte(p), []byte(s.cfg.AdminPassword)) != 1 {
			w.Header().Set("WWW-Authenticate", `Basic realm="Fieldproof admin"`)
			http.Error(w, "Unauthorized", http.StatusUnauthorized)
			return
		}
		h(w, r)
	}
}

const adminCSS = `body{font-family:Inter,system-ui,sans-serif;margin:0;background:#f2f2f7;color:#1c1c1e}
header{background:#1f7a3a;color:#fff;padding:14px 24px;font-weight:700;font-size:18px}
main{padding:20px 24px;max-width:1200px;margin:auto}table{border-collapse:collapse;width:100%;background:#fff;border-radius:12px;overflow:hidden}
td,th{padding:10px 12px;border-bottom:1px solid #e5e5ea;text-align:left;font-size:14px}th{background:#fafafa;color:#6c6c70;font-weight:600}
a{color:#1f7a3a}.card{background:#fff;border-radius:12px;padding:16px;margin:12px 0}.pill{display:inline-block;padding:2px 8px;border-radius:99px;background:#e8f3ec;color:#1f7a3a;font-size:12px}
#map{height:520px;border-radius:12px}input,select{padding:8px;border:1px solid #d1d1d6;border-radius:8px;margin-right:6px}button{padding:8px 14px;border:0;border-radius:8px;background:#1f7a3a;color:#fff;font-weight:600}
img.thumb{height:120px;border-radius:8px;margin:4px}`

var adminHomeTpl = template.Must(template.New("home").Parse(`<!doctype html><html><head><meta charset="utf-8"><title>Fieldproof admin</title>
<style>` + adminCSS + `</style></head><body><header>Fieldproof · admin</header><main>
<div class="card"><b>Add surveyor</b>
<form method="post" action="/admin/surveyors" style="margin-top:10px">
<input name="name" placeholder="Name" required> <input name="phone" placeholder="10-digit mobile" required>
<select name="district"><option>Hingoli</option></select>
<select name="block"><option>Kalamnuri</option><option>Basmat</option><option>Hingoli</option><option>Aundha Nagnath</option><option>Sengaon</option></select>
<button>Add</button></form>
<p style="color:#6c6c70;font-size:13px">Surveyors: {{range .Surveyors}}{{.Name}} ({{.Phone}}) · {{end}}</p></div>
<table><tr><th>Farmer ID</th><th>Name</th><th>Village</th><th>Status</th><th>Lands</th><th>Boundaries</th><th>Walked area</th><th>KML</th></tr>
{{range .Farmers}}<tr><td><a href="/admin/farmers/{{.ID}}">{{.Code}}</a></td><td>{{.FullName}}</td><td>{{.Village}}</td>
<td><span class="pill">{{.Status}}</span></td><td>{{.Lands}}</td><td>{{.Boundaries}}</td>
<td>{{if .Boundaries}}{{printf "%.2f" .Acres}} ac{{end}}</td><td>{{if .Boundaries}}<a href="/admin/farmers/{{.ID}}/kml">Download</a>{{end}}</td></tr>{{end}}
</table></main></body></html>`))

type adminRow struct {
	ID, Code, FullName, Village, Status string
	Lands, Boundaries                  int
	Acres                              float64
}

func (s *Server) handleAdminHome(w http.ResponseWriter, r *http.Request) {
	ctx := r.Context()
	rows, err := s.db.Query(ctx, `
		SELECT f.id, f.code, f.full_name, v.name, f.status,
			(SELECT count(*) FROM lands l WHERE l.farmer_id=f.id),
			(SELECT count(*) FROM boundaries b WHERE b.farmer_id=f.id AND b.status='active' AND b.purpose='onboarding'),
			(SELECT coalesce(sum(area_m2),0)/4046.8564224 FROM boundaries b WHERE b.farmer_id=f.id AND b.status='active' AND b.purpose='onboarding')
		FROM farmers f JOIN villages v ON v.id=f.village_id
		ORDER BY (SELECT count(*) FROM boundaries b WHERE b.farmer_id=f.id) DESC, f.updated_at DESC`)
	if err != nil {
		http.Error(w, err.Error(), 500)
		return
	}
	farmers, err := pgx.CollectRows(rows, func(row pgx.CollectableRow) (adminRow, error) {
		var a adminRow
		err := row.Scan(&a.ID, &a.Code, &a.FullName, &a.Village, &a.Status, &a.Lands, &a.Boundaries, &a.Acres)
		return a, err
	})
	if err != nil {
		http.Error(w, err.Error(), 500)
		return
	}
	rows, _ = s.db.Query(ctx, `SELECT name, phone FROM surveyors ORDER BY name`)
	svs, _ := pgx.CollectRows(rows, func(row pgx.CollectableRow) (struct{ Name, Phone string }, error) {
		var x struct{ Name, Phone string }
		err := row.Scan(&x.Name, &x.Phone)
		return x, err
	})
	w.Header().Set("Content-Type", "text/html; charset=utf-8")
	adminHomeTpl.Execute(w, map[string]any{"Farmers": farmers, "Surveyors": svs})
}

var adminFarmerTpl = template.Must(template.New("farmer").Funcs(template.FuncMap{
	"acres": func(m2 float64) float64 { return m2 / 4046.8564224 },
	"deref": func(p *float64) float64 { return *p },
}).Parse(`<!doctype html><html><head><meta charset="utf-8"><title>{{.F.Code}} · Fieldproof</title>
<link rel="stylesheet" href="https://unpkg.com/leaflet@1.9.4/dist/leaflet.css">
<script src="https://unpkg.com/leaflet@1.9.4/dist/leaflet.js"></script>
<style>` + adminCSS + `</style></head><body><header><a href="/admin" style="color:#fff;text-decoration:none">Fieldproof</a> · {{.F.FullName}} ({{.F.Code}})</header><main>
<div class="card">{{.F.Village.Name}}, {{.F.Village.Block}}, {{.F.Village.District}} · mobile {{.F.Mobile}} · status <span class="pill">{{.F.Status}}</span>
{{range .F.Lands}}<div>Survey/Gat {{.SurveyNo}} · {{printf "%.2f" .AreaHa}} ha · registering {{printf "%.2f" .RegisterAcres}} ac · owner {{.OwnerType}}</div>{{end}}</div>
{{if .F.Boundaries}}<div id="map"></div>
<table style="margin-top:12px"><tr><th>Boundary</th><th>Area</th><th>Points</th><th>GPS accuracy</th><th>Walked</th><th>KML</th></tr>
{{range .F.Boundaries}}<tr><td>{{.ID}}</td><td>{{printf "%.2f" .AreaM2}} m² · {{printf "%.3f" (acres .AreaM2)}} ac</td><td>{{.PointCount}}</td>
<td>{{if .AccuracyM}}±{{printf "%.1f" (deref .AccuracyM)}} m{{end}}</td><td>{{if .WalkedM}}{{printf "%.0f" (deref .WalkedM)}} m{{end}}</td>
<td><a href="/admin/boundaries/{{.ID}}/kml">Download</a></td></tr>{{end}}</table>
<p><a href="/admin/farmers/{{.F.ID}}/kml">Download all parcels as one KML</a></p>
{{else}}<div class="card">No boundary walked yet.</div>{{end}}
<div class="card"><b>Photos and documents</b><div>{{range .F.Files}}{{if eq .Mime "application/pdf"}}<a href="/admin/files/{{.ID}}">{{.Kind}} (PDF)</a>{{else}}<a href="/admin/files/{{.ID}}" title="{{.Kind}}"><img class="thumb" src="/admin/files/{{.ID}}"></a>{{end}}{{end}}</div></div>
</main>
{{if .F.Boundaries}}<script>
const parcels = {{.Rings}};
const map = L.map('map');
L.tileLayer('https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}', {maxZoom: 20, maxNativeZoom: 19, attribution: 'Imagery © Esri'}).addTo(map);
const all = [];
parcels.forEach(r => {
  const ll = r.map(p => [p.lat, p.lng]);
  all.push(...ll);
  L.polygon(ll, {color: '#ffffff', weight: 2, fillColor: '#2e7d32', fillOpacity: 0.35}).addTo(map);
  ll.forEach(p => L.circleMarker(p, {radius: 2, color: '#e8501c', weight: 1}).addTo(map));
});
map.fitBounds(all, {padding: [30, 30]});
</script>{{end}}
</body></html>`))

func (s *Server) handleAdminFarmer(w http.ResponseWriter, r *http.Request) {
	id := r.PathValue("id")
	if !uuidRe.MatchString(id) {
		http.NotFound(w, r)
		return
	}
	f, err := s.loadFarmer(r.Context(), id)
	if err != nil {
		http.NotFound(w, r)
		return
	}
	rings := make([][]PointIn, len(f.Boundaries))
	for i, b := range f.Boundaries {
		rings[i] = b.Points
	}
	ringsJSON, _ := json.Marshal(rings)
	w.Header().Set("Content-Type", "text/html; charset=utf-8")
	if err := adminFarmerTpl.Execute(w, map[string]any{"F": f, "Rings": template.JS(ringsJSON)}); err != nil {
		http.Error(w, err.Error(), 500)
	}
}

func (s *Server) handleAdminFarmerKML(w http.ResponseWriter, r *http.Request) {
	name, doc, err := s.farmerKML(r.Context(), r.PathValue("id"), "")
	if err != nil {
		http.Error(w, "No boundary walked yet.", 404)
		return
	}
	writeKML(w, name, doc)
}

func (s *Server) handleAdminBoundaryKML(w http.ResponseWriter, r *http.Request) {
	var farmerID string
	if err := s.db.QueryRow(r.Context(), `SELECT farmer_id FROM boundaries WHERE id=$1`, r.PathValue("bid")).Scan(&farmerID); err != nil {
		http.NotFound(w, r)
		return
	}
	name, doc, err := s.farmerKML(r.Context(), farmerID, r.PathValue("bid"))
	if err != nil {
		http.Error(w, "Boundary was replaced by a newer walk.", 404)
		return
	}
	writeKML(w, name, doc)
}

func (s *Server) handleAdminFile(w http.ResponseWriter, r *http.Request) {
	var path, mime string
	if err := s.db.QueryRow(r.Context(), `SELECT path, mime FROM files WHERE id=$1`, r.PathValue("fid")).Scan(&path, &mime); err != nil {
		http.NotFound(w, r)
		return
	}
	f, err := os.Open(path)
	if err != nil {
		http.NotFound(w, r)
		return
	}
	defer f.Close()
	w.Header().Set("Content-Type", mime)
	st, _ := f.Stat()
	http.ServeContent(w, r, "", st.ModTime(), f)
}

func (s *Server) handleAdminAddSurveyor(w http.ResponseWriter, r *http.Request) {
	r.ParseForm()
	phone := NormalizePhone(r.FormValue("phone"))
	name := strings.TrimSpace(r.FormValue("name"))
	district, block := r.FormValue("district"), r.FormValue("block")
	if phone == "" || name == "" || district == "" || block == "" {
		http.Error(w, "name, 10-digit phone, district and block are needed", 400)
		return
	}
	ctx := r.Context()
	var id string
	err := s.db.QueryRow(ctx, `INSERT INTO surveyors(phone, name, district, home_block) VALUES ($1,$2,$3,$4)
		ON CONFLICT (phone) DO UPDATE SET name=EXCLUDED.name RETURNING id`, phone, name, district, block).Scan(&id)
	if err != nil {
		http.Error(w, err.Error(), 500)
		return
	}
	s.db.Exec(ctx, `INSERT INTO surveyor_blocks(surveyor_id, district, block) VALUES ($1,$2,$3) ON CONFLICT DO NOTHING`, id, district, block)
	s.fillStatesSoon() // this page has no state field: take it from the district
	http.Redirect(w, r, "/admin", http.StatusSeeOther)
}
