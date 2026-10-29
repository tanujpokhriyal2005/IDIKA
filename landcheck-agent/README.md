# Land check agent — V2 (test version)

Checks farm parcels drawn as KML for tree clearing and buildings since 2017, using free
satellite data in Google Earth Engine. This is a test tool on this computer, not yet part
of the Fieldproof app.

## Check your own parcels

1. Draw each parcel in Google Earth and export the KML (see the steps in the chat), or use
   any KML with polygons. One file can hold many parcels.
2. Put the KML files in one folder, for example `kml-tests/batch1/`.
3. Ask Claude to run the check on that folder, or run it yourself in a terminal:

   ```
   cd landcheck-agent
   .venv\Scripts\python landcheck.py check ..\kml-tests\batch1
   ```

4. Open `report\report.html` inside that folder. Each parcel shows the verdict, the
   reasons, the confidence, spring satellite pictures from 2017 and this year, what the
   agent sees (its trees / crops / built-up map) and the tree area for every year.
5. Compare each verdict with what you know about the parcel, and note where it is wrong.
   Those notes become our first own training labels.

Name parcels with what you expect ("Ram's field — orchard cut 2020") so the report is easy
to judge.

## Reports from the dashboard (Land check tab)

Anyone on the Fieldproof dashboard (admin, state lead, NGO) can upload a KML on the
**Land check** tab. The upload waits in the database until the worker on this computer
picks it up, checks the parcels and stores the report, which then opens from the tab.

```
cd landcheck-agent
.venv\Scripts\python worker.py
```

Keep that window open while checks should run; the tab shows whether the agent is
running. `DATABASE_URL` picks the database (default: the local docker compose one). For
the cloud server the worker needs an Earth Engine service-account key on the server
instead of this computer's sign-in.

## What the verdicts mean

| Verdict | When |
|---|---|
| Approve | No lasting tree loss, no building inside, and the agent is at least 80 % sure |
| A person checks | Small tree loss (300–1,000 m²), a change only along the edge, a building on the edge, Global Forest Watch disagrees, or the agent is unsure |
| Flag | Lasting tree loss over 1,000 m² or 20 % of the parcel's trees, or a building well inside the parcel — and the agent is at least 80 % sure |

"Lasting" means the trees are still gone in the best of the last three years, so a crop
harvest or a felling that grew back is not flagged.

## How it works

For each 10 m square and each year from 2017 the agent reads Sentinel-2 (10 land bands in
three seasons, greenness and moisture through the year), Sentinel-1 radar, and Google's
AlphaEarth yearly summary (to 2025). A random-forest model turns these into the chance of
trees, crops, shrub, built-up, bare or water. The rules above turn the yearly maps into a
verdict. Buildings come from Google Open Buildings (traced from 0.5 m imagery), and Global
Forest Watch is a second opinion.

## Limits of this version

- The model learned from 7,336 points where two global maps agree (ESA WorldCover and
  Google Dynamic World, 2021), in western UP, Marathwada–Vidarbha and central MP — not yet
  from our own farms. On held-out points of that kind it is right 94 % of the time (90 %
  for the current year, which has no AlphaEarth data yet); on real farms it will be lower.
- The 80 % confidence bar and the m² thresholds are not calibrated yet.
- 10 m pixels: single trees and tiny sheds are not visible.

## Rules added after the first blind test (28 Oct 2026)

- **Land type now** is the headline: the current year so far, with the last full year shown
  below it (a river can move, a field can be built on).
- **Water / river bed**: if a parcel is under water at least 25 % of the time since 2017
  (Sentinel-2 water index, which also catches muddy river water), it is called water / river
  bed and sent to a person as "not farmland".
- **Cut and grew back**: a drop of at least 1,000 m² or 20 % of the trees that grows back to
  90 % later, confirmed by radar (0.5 dB lower than other years) or Global Forest Watch, is
  sent to a person as "looks like a plantation harvest". Lasting loss is still flagged.

`my_labels.json` holds parcels our team has confirmed. Add each checked KML there; once
there are a few hundred they become training data.

## Files

- `landcheck.py` — the agent (`train`, `accuracy`, `check`)
- `training_2021.json` — the training points (`python landcheck.py train` rebuilds it)
- Earth Engine project: set `EE_PROJECT`, or put the project id on one line in
  `landcheck-agent/.ee-project` (git-ignored); on the server it is read from the
  service-account key (`EE_KEY_FILE`). The PC sign-in is stored by
  `earthengine authenticate` in your user profile, not here.
