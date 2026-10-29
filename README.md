# IDIKA

Tools for Idika Foundation's tree-planting programme.

| Folder | What it is |
|---|---|
| [`fieldproof/`](fieldproof/) | **Fieldproof**: Android app for surveyors (farmer onboarding, boundary walk KML, payment and plantation steps), Go + PostGIS API, and the web dashboard (review, roles, Land check). Setup, tests and deployment in [`fieldproof/README.md`](fieldproof/README.md). |
| [`landcheck-agent/`](landcheck-agent/) | **Land check agent**: checks farm parcels (KML) against satellite history since 2017 for tree clearing, buildings and water, using Google Earth Engine. Runs as the `landcheck` service behind the dashboard's Land check tab. See [`landcheck-agent/README.md`](landcheck-agent/README.md). |

Secrets (database and admin passwords, the Earth Engine service-account key) are not in
this repository; see the READMEs for where they live on the server.
