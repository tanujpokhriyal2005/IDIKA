# Fieldproof — new farmer flow (Step 1: local)

Android app (Kotlin, Jetpack Compose) + Go API + PostgreSQL/PostGIS, all running on this laptop in Docker.
The phone talks to the laptop over the USB cable (`adb reverse`), so no Wi-Fi setup or cloud is needed yet.

```
fieldproof/
  android/            Kotlin app (all 11 designed screens + My farmers, Sync, evidence capture)
  backend/            Go API, PostGIS schema, seed data, admin pages, KML export
  docker-compose.yml  db (PostGIS 17) + api
  tools/              phone-usb.ps1, validate-kml.sh, sample KML files
```

## Run it

1. Start Docker Desktop.
2. Plug in the phone (USB debugging on, screen unlocked), then from this folder:

   ```
   powershell -ExecutionPolicy Bypass -File tools\phone-usb.ps1
   ```

   This starts the backend, links the phone's port 8080 to the laptop, installs the app and opens it.
   **Run it again every time you re-plug the phone** (the port link drops on unplug).
3. Log in with the demo surveyor's mobile number (seeded by `SEED_DEMO`). There is no SMS gateway yet, so the server hands the OTP back and it fills in by itself, like SMS auto-read.

Admin pages (a satellite map of every walked boundary, KML downloads, photos, add surveyor):
http://localhost:8080/admin — user and password from `ADMIN_USER` / `ADMIN_PASSWORD` in `docker-compose.yml`.
To log in with your own number, add yourself as a surveyor there first.

## Web dashboard (review and logins)

Open http://localhost:8080/dash/ after `docker compose up`.

- Admin: username `admin`, password from `ADMIN_PASSWORD` in `docker-compose.yml`. The first admin is created from
  `ADMIN_USER` / `ADMIN_PASSWORD` when none exists.
- Demo logins (only when `SEED_DEMO=true`; see `seed_dashboard.go`): two state leads and three NGOs in Maharashtra
  and Madhya Pradesh, for trying the roles locally. They are never created on a public server
  (`SEED_DASHBOARD_DEMO=false`).
- Hierarchy: admin → state lead → NGO → surveyor. Each role issues logins only below itself; every list and filter is
  limited to the person's area (`dash_scope.go`).
- Review: Aadhaar, land record, water source and boundary are reviewed separately. Reject and Pending need a message and
  become a red-dot task on the phone of the surveyor who onboarded the farmer. A new upload or walk puts the document
  back to New and closes the task.
- Locally the dashboard files are served from `backend/internal/api/web` (`WEB_DIR`), so edits show on reload. The
  production image embeds them.
- Emulator: long-press the logo on the phone login screen and set the server to `http://10.0.2.2:8080`.

## Testing the boundary walk on the phone

Home → **Walk farm boundary · Sunanda Raut** (or register a new farmer and tap *I'm at the farm now*).

* Stand outdoors. **Start walking** unlocks only at GPS ±5 m or better. The first 3 fixes are averaged to fix the start corner, so hold still for 3 seconds.
* Walk the edge. A point is laid every 2 m from the walk only; nothing can be drawn or moved on the map.
* Above 8 km/h, recording pauses. It resumes only when you are back within 10 m of where it stopped.
* **Finish boundary** unlocks within 10 m of the start, after at least 12 points and after walking at least 15 m away.
* On finish, an overshoot past the start corner is trimmed, the ring is closed, and it is checked for self-crossing. The review screen then compares the area with the 7/12 record and checks for overlap with other farmers' boundaries (PostGIS).
* The screen stays on, and a foreground service keeps recording if the screen locks. If the app is killed mid-walk, reopening offers to continue from the last point.
* Export: **Farmers → (farmer) → Share boundary KML** from the phone, or download from the admin page.

Over USB you'll be walking with a cable, so try a small plot first. Step 2 (deployment) removes the cable.

## How accurate the KML is

| What | How it is checked | Result |
|---|---|---|
| Area formula (phone and server) | Against PostGIS `ST_Area(geography)` (GeographicLib, WGS84 ellipsoid), field sizes 20 m to 1.5 km, latitudes 8° to 60° | agrees within 2×10⁻⁷ |
| Recorder, perfect GPS | Simulated walk of a known 7,564 m² field | 0.02% area error, every point within 0.5 m of the true edge, spacing 2.0 m |
| Recorder, realistic GPS | 60 simulated walks with drifting ±1.5 m bias plus ±1 m jitter, compared with the raw GPS fixes alone | adds no error beyond the GPS itself (median 1.37% vs 1.29% for raw fixes) |
| End to end on the emulator | GPS fed at 1 Hz around a known field → app → server → KML | −0.03% area, max 2.8 m deviation (with injected noise), 98% overlap with the true polygon |
| KML file | Official OGC KML 2.2 XSD (`tools/validate-kml.sh`), round-trip parse, PostGIS `ST_GeomFromKML` | valid; closed counter-clockwise ring; lon,lat order; 8 decimals (~1 mm) |

Real-world accuracy is limited by the phone's GPS, typically 2–5 m in open fields. The app records only raw GNSS fixes from the GPS chip (no Wi-Fi or cell positions) and refuses mock-location apps.

## Tests

```
cd android && gradlew :app:testDebugUnitTest          # 21 tests: geometry, KML, GPS walk simulator
docker run ... go test ./...                           # API + PostGIS integration, see backend/internal/api/api_test.go
sh tools/validate-kml.sh tools/*.kml                   # OGC schema validation
```

Backend integration tests need `TEST_DATABASE_URL`, e.g.
`postgres://fieldproof:fieldproof@db:5432/postgres?sslmode=disable` on the `fieldproof_default` Docker network.

## Not done yet / notes

* **OTP SMS**: dev mode returns the code to the app. Step 2 adds a real SMS gateway (and turns `DEV_MODE` off).
* **Satellite map tiles** (Esri World Imagery) need mobile data or Wi-Fi on the phone. Recording itself works with no network.
* **Aadhaar number**: only the photos are captured; the number is never shown on screen. OCR for the last 4 digits isn't built.
* **Translations**: Hindi and Marathi are included (Home → language). Have a native speaker review the wording.
* The app is a debug build, signed with the debug key.

## Step 2: cloud deployment

Production runs on one small cloud server with `deploy/docker-compose.prod.yml`:

| Piece | What |
|---|---|
| Stack | PostGIS + Go API + Caddy (automatic Let's Encrypt HTTPS) + the `landcheck` agent |
| Firewall | only 80/443 open; no SSH. Managed through the cloud provider's session manager |
| Backups | `deploy/backup.sh`: nightly database dump and uploaded photos to object storage |
| Secrets | `deploy/.env.prod` (database and admin password) and `deploy/secrets/` (Earth Engine service-account key). Never committed; kept on the server and on the maintainer's PC |
| Land check agent | the `landcheck` service (built from `landcheck/`, a copy of `landcheck-agent/`) signs in to Earth Engine with the service-account key mounted from `secrets/ee-key.json`. Start it only when that file exists: `docker compose -f docker-compose.prod.yml up -d landcheck` |
| Deploy an update | bundle `backend/`, the deploy files and `landcheck/`; on the server back up with `backup.sh`, keep the previous code for rollback, extract the bundle and run `docker compose -f docker-compose.prod.yml up -d --build api` (and `landcheck`) |
| Caddyfile changes | the file is bind-mounted, so after editing it run `docker compose -f docker-compose.prod.yml restart caddy` (a reload alone keeps the old file) |

Server addresses, account details, logins and the deploy checklist are in the maintainer's private notes
(`README.private.md`, not in this repository).

OTP is still in test mode (the code fills in by itself); add an SMS gateway before real use.
To test against a laptop over USB, long-press the logo on the phone's login screen and set `http://127.0.0.1:8080`.
