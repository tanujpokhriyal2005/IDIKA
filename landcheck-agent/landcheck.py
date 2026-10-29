"""Land check agent, V2 (test version).

Looks at a farm parcel's satellite history from 2017 and says whether trees were
cleared or a building stands on it.

  python landcheck.py train              collect the training points (once, ~10-20 min)
  python landcheck.py accuracy           how often the model agrees with held-out points
  python landcheck.py check <kml|folder> [output folder]

How it works, per 10 m pixel and per year:
  features  Sentinel-2's 10 land bands in three seasons, greenness (NDVI) and moisture
            (NDMI) through the year, Sentinel-1 radar, and Google's AlphaEarth yearly
            summary (2017-2025).
  model     a random forest that gives the chance of trees / crops / shrub / built /
            bare / water. Until our own farms are labelled it learns from points where
            two independent global maps (ESA WorldCover and Google Dynamic World) agree.
  rules     lasting tree loss, buildings (Google Open Buildings), edge-only changes and
            Global Forest Watch as a second opinion turn the yearly maps into a verdict.
"""
import datetime
import html
import json
import os
import re
import sys
import urllib.request
import warnings
import xml.etree.ElementTree as ET

import ee

warnings.filterwarnings("ignore")

def _project():
    """The Google Cloud project for Earth Engine: EE_PROJECT, else the project of the
    service-account key (server), else the one-line .ee-project file next to this one (PC).
    Kept out of the code because the repository is public."""
    if os.environ.get("EE_PROJECT"):
        return os.environ["EE_PROJECT"]
    if os.environ.get("EE_KEY_FILE"):
        with open(os.environ["EE_KEY_FILE"], encoding="utf-8") as f:
            return json.load(f)["project_id"]
    path = os.path.join(os.path.dirname(os.path.abspath(__file__)), ".ee-project")
    if os.path.exists(path):
        return open(path, encoding="utf-8").read().strip()
    sys.exit("No Earth Engine project: set EE_PROJECT or put the project id in landcheck-agent/.ee-project")


PROJECT = _project()
TRAIN_FILE = os.path.join(os.path.dirname(os.path.abspath(__file__)), "training_2021.json")
CALIB_FILE = os.path.join(os.path.dirname(os.path.abspath(__file__)), "calibration.json")
FIRST_YEAR, EMB_LAST_YEAR = 2017, 2025

CLASSES = ["trees", "crops", "shrub", "built", "bare", "water"]
PALETTE = ["1b7f3b", "f2d64b", "a3b86c", "d7301f", "c9a27e", "2b83ba"]

# Rules (section "The rules" of the approach doc). Not yet calibrated on our farms.
IGNORE_M2, FLAG_M2, FLAG_SHARE = 300, 1000, 0.20
SURE = 0.80  # below this the agent asks a person instead of deciding
WET_SHARE = 0.25   # under water this share of the time (or more) = water body or river bed
RADAR_DROP = 0.5   # dB lower radar than in the other years = trees really were felled that year

S2_BANDS = ["B2", "B3", "B4", "B5", "B6", "B7", "B8", "B8A", "B11", "B12"]
SEASONS = {"jfm": ("01-01", "04-01"), "amj": ("04-01", "07-01"), "ond": ("10-01", "12-31")}
BASE_FEATURES = ([f"{b}_{s}" for s in SEASONS for b in S2_BANDS]
                 + ["ndvi_p10", "ndvi_p50", "ndvi_p90", "ndvi_sd", "ndmi_p50",
                    "VV", "VH", "VV_sd", "VH_sd", "VHVV"])
EMB_FEATURES = [f"A{i:02d}" for i in range(64)]

# Training areas: western Uttar Pradesh, Marathwada-Vidarbha, central Madhya Pradesh.
TRAIN_BOXES = [[77.0, 26.0, 80.5, 29.0], [75.0, 18.5, 80.0, 21.5], [76.0, 22.0, 80.0, 24.5]]


# On the server the agent signs in with the Earth Engine service account's key file
# (EE_KEY_FILE); on a PC with the person's own `earthengine authenticate` sign-in.
if os.environ.get("EE_KEY_FILE"):
    ee.Initialize(ee.ServiceAccountCredentials(None, key_file=os.environ["EE_KEY_FILE"]), project=PROJECT)
else:
    ee.Initialize(project=PROJECT)
S2 = ee.ImageCollection("COPERNICUS/S2_HARMONIZED")
CS = ee.ImageCollection("GOOGLE/CLOUD_SCORE_PLUS/V1/S2_HARMONIZED")
S1 = ee.ImageCollection("COPERNICUS/S1_GRD")
EMB = ee.ImageCollection("GOOGLE/SATELLITE_EMBEDDING/V1/ANNUAL")
DW = ee.ImageCollection("GOOGLE/DYNAMICWORLD/V1")
GFC = ee.Image("UMD/hansen/global_forest_change_2025_v1_13")
BUILDINGS = ee.FeatureCollection("GOOGLE/Research/open-buildings/v3/polygons")


# ---------- features ----------

def s2_clear(geom, start, end):
    def prep(raw):
        # Image maths drops the date, which the season filters need, so copy it back.
        i = raw.updateMask(raw.select("cs_cdf").gte(0.6)).select(S2_BANDS).divide(10000)
        i = i.addBands(i.normalizedDifference(["B8", "B4"]).rename("ndvi")) \
             .addBands(i.normalizedDifference(["B8", "B11"]).rename("ndmi"))
        return i.copyProperties(raw, ["system:time_start"])
    return S2.filterBounds(geom).filterDate(start, end).linkCollection(CS, ["cs_cdf"]).map(prep)


def features(year, geom):
    """One image, one band per feature, for the given year."""
    y0, y1 = f"{year}-01-01", f"{year + 1}-01-01"
    s2 = s2_clear(geom, y0, y1)
    annual = s2.select(S2_BANDS).median()
    seasons = [s2.filterDate(f"{year}-{a}", f"{year}-{b}").select(S2_BANDS).median().unmask(annual)
               .rename([f"{x}_{k}" for x in S2_BANDS]) for k, (a, b) in SEASONS.items()]
    ndvi = s2.select("ndvi")
    nd = (ndvi.reduce(ee.Reducer.percentile([10, 50, 90])).rename(["ndvi_p10", "ndvi_p50", "ndvi_p90"])
          .addBands(ndvi.reduce(ee.Reducer.stdDev()).rename("ndvi_sd"))
          .addBands(s2.select("ndmi").median().rename("ndmi_p50")))
    s1 = (S1.filterBounds(geom).filterDate(y0, y1).filter(ee.Filter.eq("instrumentMode", "IW"))
          .filter(ee.Filter.listContains("transmitterReceiverPolarisation", "VH")).select(["VV", "VH"]))
    radar = (s1.median().addBands(s1.reduce(ee.Reducer.stdDev()).rename(["VV_sd", "VH_sd"])))
    radar = radar.addBands(radar.select("VH").subtract(radar.select("VV")).rename("VHVV"))
    img = ee.Image.cat(seasons + [nd, radar]).select(BASE_FEATURES)
    if year <= EMB_LAST_YEAR:
        emb = EMB.filterDate(y0, y1).filterBounds(geom).mosaic().select(EMB_FEATURES)
        img = img.addBands(emb)
    return img.float()


def consensus_label(year):
    """Class where ESA WorldCover 2021 and Dynamic World agree, else masked."""
    wc = ee.ImageCollection("ESA/WorldCover/v200").first().select("Map")
    wc = wc.remap([10, 95, 40, 20, 30, 50, 60, 80], [0, 0, 1, 2, 2, 3, 4, 5], -1)
    dw = DW.filterDate(f"{year}-01-01", f"{year + 1}-01-01").select("label").mode()
    dw = dw.remap([1, 4, 2, 5, 6, 7, 0], [0, 1, 2, 2, 3, 4, 5], -1)
    return wc.updateMask(wc.eq(dw).And(wc.gte(0))).rename("label")


# ---------- training ----------

def train(tiles=48, per_class=30):
    """Samples points in many 6 km tiles across the training areas, an equal number per
    class in each tile, and saves them to training_2021.json next to this file."""
    import random
    from concurrent.futures import ThreadPoolExecutor
    rnd = random.Random(11)
    centres = []
    for i in range(tiles):
        x0, y0, x1, y1 = TRAIN_BOXES[i % len(TRAIN_BOXES)]
        centres.append((rnd.uniform(x0, x1), rnd.uniform(y0, y1)))

    def one(c):
        g = ee.Geometry.Point(c).buffer(3000).bounds()
        stack = consensus_label(2021).addBands(features(2021, g))
        for attempt in range(3):
            try:
                fc = stack.stratifiedSample(numPoints=per_class, classBand="label", region=g, scale=10,
                                            seed=7, tileScale=4, geometries=False, dropNulls=True)
                return [f["properties"] for f in fc.getInfo()["features"]]
            except Exception as e:
                err = e
        print("  tile skipped:", str(err)[:100])
        return []

    rows = []
    with ThreadPoolExecutor(6) as pool:
        for i, part in enumerate(pool.map(one, centres)):
            rows += part
            print(f"  tile {i + 1}/{tiles}: {len(part)} points")
    keep = BASE_FEATURES + EMB_FEATURES
    rows = [{**{k: round(r[k], 4) for k in keep}, "label": int(r["label"])} for r in rows if all(k in r for k in keep)]
    json.dump(rows, open(TRAIN_FILE, "w"))
    counts = {c: sum(1 for r in rows if r["label"] == i) for i, c in enumerate(CLASSES)}
    print(f"Saved {len(rows)} training points to {TRAIN_FILE}: {counts}")


def training_table():
    rows = json.load(open(TRAIN_FILE))
    rnd = __import__("random").Random(5)
    by = {}
    for r in rows:
        by.setdefault(r["label"], []).append(r)
    feats = []
    for label, rs in by.items():
        rnd.shuffle(rs)
        # Balance the classes: at most 600 points each, so common crops don't drown rare built-up.
        for r in rs[:600]:
            feats.append(ee.Feature(None, {**r, "split": rnd.random()}))
    return ee.FeatureCollection(feats)


def model(with_emb, mode="MULTIPROBABILITY", part=None):
    fc = training_table()
    if part == "train":
        fc = fc.filter(ee.Filter.lt("split", 0.75))
    props = BASE_FEATURES + (EMB_FEATURES if with_emb else [])
    return ee.Classifier.smileRandomForest(numberOfTrees=150, minLeafPopulation=2, seed=7) \
        .train(fc, "label", props).setOutputMode(mode)


def accuracy():
    test = training_table().filter(ee.Filter.gte("split", 0.75))
    print("points per class:", dict(zip(CLASSES, training_table().aggregate_histogram("label").values().getInfo())))
    for with_emb in (True, False):
        m = test.classify(model(with_emb, "CLASSIFICATION", "train")).errorMatrix("label", "classification")
        r = ee.Dictionary({"acc": m.accuracy(), "kappa": m.kappa(), "prod": m.producersAccuracy(),
                           "cons": m.consumersAccuracy(), "matrix": m.array()}).getInfo()
        name = "with AlphaEarth (2017-2025)" if with_emb else "without AlphaEarth (2026)"
        print(f"\nModel {name}: {r['acc']:.1%} of held-out points right (kappa {r['kappa']:.2f})")
        for i, c in enumerate(CLASSES):
            print(f"  {c:6}  found {r['prod'][i][0]:.0%} of real {c}; when it says {c} it is right {r['cons'][0][i]:.0%}")
    calibrate()


def calibrate(bins=10):
    """The model's raw 'chance of trees / crops / ...' is not a real probability. On the
    held-out points, measure for each class how often a pixel with a given raw score really
    is that class, and save those curves (made non-decreasing) to calibration.json.
    check() turns raw scores into real ones with them."""
    test = training_table().filter(ee.Filter.gte("split", 0.75))
    out = {}
    for key, with_emb in (("full", True), ("base", False)):
        got = test.classify(model(with_emb, "MULTIPROBABILITY", "train")) \
            .map(lambda f: ee.Feature(None, {"p": f.get("classification"), "label": f.get("label")}))
        rows = [f["properties"] for f in got.getInfo()["features"]]
        out[key] = {}
        for ci, cname in enumerate(CLASSES):
            n, hit = [0] * bins, [0] * bins
            for r in rows:
                k = min(int(r["p"][ci] * bins), bins - 1)
                n[k] += 1
                hit[k] += int(r["label"] == ci)
            xs, ys, best = [], [], 0.0
            for k in range(bins):
                if n[k] >= 5:
                    best = max(best, hit[k] / n[k])
                    xs.append((k + 0.5) / bins)
                    ys.append(best)
            out[key][cname] = {"x": [0.0] + xs + [1.0], "y": [0.0] + ys + [1.0]}
            print(f"Calibration ({key}, {cname}):", ", ".join(f"{x:.2f}->{y:.2f}" for x, y in zip(xs, ys)))
    json.dump(out, open(CALIB_FILE, "w"), indent=1)


# ---------- parcels ----------

def read_parcels(path):
    files = [os.path.join(path, f) for f in sorted(os.listdir(path)) if f.lower().endswith(".kml")] if os.path.isdir(path) else [path]
    ns = "{http://www.opengis.net/kml/2.2}"
    for f in files:
        for pm in ET.parse(f).getroot().iter(ns + "Placemark"):
            name = (pm.findtext(ns + "name") or os.path.splitext(os.path.basename(f))[0]).strip()
            for i, poly in enumerate(pm.iter(ns + "Polygon")):
                text = poly.find(f".//{ns}outerBoundaryIs//{ns}coordinates").text
                ring = [[float(v) for v in p.split(",")[:2]] for p in text.split()]
                yield (name if i == 0 else f"{name} ({i + 1})"), os.path.basename(f), ring


def year_maps(geom, last_year, full, base):
    """Per year: probability image (one band per class) and which model made it."""
    return {y: (features(y, geom).classify(full if y <= EMB_LAST_YEAR else base).arrayFlatten([CLASSES]),
                "full" if y <= EMB_LAST_YEAR else "base")
            for y in range(FIRST_YEAR, last_year + 1)}


def classes(p):
    """Most likely class of each pixel (0 = trees ... 5 = water)."""
    return p.toArray().arrayArgmax().arrayGet([0])


def calibrated(p, curves):
    """Calibrated chance of each class per pixel, scaled to add up to 1."""
    cal = ee.Image.cat([p.select(c).interpolate(curves[c]["x"], curves[c]["y"], "clamp").rename(c) for c in CLASSES])
    return cal.divide(cal.reduce(ee.Reducer.sum()).max(1e-6))


def parcel_numbers(geom, maps, calib):
    area = ee.Image.pixelArea()
    inner = geom.buffer(-10, 1)
    strip = geom.buffer(30, 1).difference(geom, 1)
    last = max(maps)
    rows = []
    for y, (p, key) in maps.items():
        cls = classes(p)
        # Each 10 m square counts as its single most likely class (no smearing of chances).
        hard = ee.Image.cat([area.updateMask(cls.eq(k)).rename(c) for k, c in enumerate(CLASSES)]
                            + [area.updateMask(cls.mask()).rename("covered")])
        cal = calibrated(p, calib[key])
        # How sure the agent is about "trees or not" for each square.
        cert = cal.select("trees").max(ee.Image(1).subtract(cal.select("trees"))).rename("cert")
        y0, y1 = f"{y}-01-01", f"{y + 1}-01-01"
        # Share of the year's clear images in which each square is under water (MNDWI > 0);
        # muddy river water counts too, which Sentinel-2's short-wave infrared tells apart from sand.
        wet = s2_clear(geom, y0, y1).map(lambda i: i.normalizedDifference(["B3", "B11"]).gt(0)).mean().rename("wet")
        # Radar backscatter (VH) responds to trunks and branches: it drops when trees are felled.
        vh = S1.filterBounds(geom).filterDate(y0, y1).filter(ee.Filter.eq("instrumentMode", "IW")) \
            .filter(ee.Filter.listContains("transmitterReceiverPolarisation", "VH")).select("VH").median()
        row = {
            "year": y,
            "m2": hard.reduceRegion(ee.Reducer.sum(), geom, 10, maxPixels=1e9),
            "cert": cert.reduceRegion(ee.Reducer.mean(), geom, 10).get("cert"),
            "inner_trees": hard.select("trees").reduceRegion(ee.Reducer.sum(), inner, 10).get("trees"),
            "strip_trees": hard.select("trees").reduceRegion(ee.Reducer.sum(), strip, 10).get("trees"),
            "wet": wet.reduceRegion(ee.Reducer.mean(), geom, 10).get("wet"),
            "vh": vh.reduceRegion(ee.Reducer.mean(), geom, 10).get("VH"),
        }
        if y >= last - 1:
            # Average calibrated chance of each land type over the parcel (latest two years).
            row["pcal"] = cal.reduceRegion(ee.Reducer.mean(), geom, 10)
        rows.append(ee.Dictionary(row))
    gfw = area.updateMask(GFC.select("lossyear").gte(17))
    b = BUILDINGS.filterBounds(geom).filter(ee.Filter.gte("confidence", 0.75))
    return ee.Dictionary({
        "area": geom.area(1), "inner_area": inner.area(1), "centre": geom.centroid(1).coordinates(),
        "years": rows,
        "gfw": gfw.reduceRegion(ee.Reducer.sum(), geom, 30).get("area"),
        "bld_n": b.size(),
        "bld_conf": b.aggregate_mean("confidence"),
        "bld_m2": b.map(lambda x: x.set("a", x.geometry().intersection(geom, 1).area(1))).aggregate_sum("a"),
        "bld_inner_n": b.filterBounds(inner).size(),
    }).getInfo()


def decide(r, last_year):
    yrs = {y["year"]: y for y in r["years"]}
    trees = {y: (v["m2"].get("trees") or 0) for y, v in yrs.items()}
    built = {y: (v["m2"].get("built") or 0) for y, v in yrs.items()}
    inner = {y: (v["inner_trees"] or 0) for y, v in yrs.items()}
    early = [FIRST_YEAR, FIRST_YEAR + 1, FIRST_YEAR + 2]
    recent = [y for y in (last_year - 2, last_year - 1, last_year) if y in trees]
    base = max(trees[y] for y in early)
    now = max(trees[y] for y in recent)          # the best recent year: a real clearing stays low
    loss = max(base - now, 0)
    inner_loss = max(max(inner[y] for y in early) - max(inner[y] for y in recent), 0)
    drop_year = next((y for y in sorted(trees) if y > FIRST_YEAR and trees[y] <= base - loss / 2
                      and all(trees[z] <= base - loss / 2 for z in trees if z >= y)), None) if loss >= IGNORE_M2 else None
    new_built = max(built[y] for y in recent) - max(built[y] for y in early)
    gfw = r["gfw"] or 0
    covered = min((yrs[y]["m2"].get("covered") or 0) / r["area"] for y in yrs) if r["area"] else 0
    # Confidence about trees: the calibrated "trees or not" certainty, averaged over the
    # years the decision rests on (the first three and the last three).
    conf = sum(yrs[y]["cert"] or 0 for y in early + recent) / len(early + recent)
    if r["area"] < 2000:
        conf *= 0.9
    if covered < 0.8:
        conf *= 0.8

    found = []  # (level, reason)
    if r["bld_inner_n"]:
        # Buildings come from Google Open Buildings, which has its own confidence per outline.
        found.append(("flag", f"{r['bld_inner_n']} building outline(s) well inside the parcel "
                              f"({round(r['bld_m2'])} m² in all, building map {r['bld_conf']:.0%} sure)"))
    elif r["bld_n"]:
        found.append(("review", f"{r['bld_n']} building outline(s) on the edge of the parcel (may be the neighbour's)"))
    if new_built >= 200:
        found.append(("review", f"built-up area grew by about {round(new_built)} m² since {FIRST_YEAR}"))
    share = loss / base if base else 0
    if loss >= FLAG_M2 or (loss >= IGNORE_M2 and share >= FLAG_SHARE):
        when = f" around {drop_year}" if drop_year else ""
        if inner_loss < IGNORE_M2 / 2:
            found.append(("review", f"tree cover fell by {round(loss)} m²{when}, but only along the edge"))
        else:
            found.append(("flag", f"tree cover fell from {round(base)} m² to {round(now)} m² ({share:.0%}){when} and has not come back"))
    elif loss >= IGNORE_M2:
        found.append(("review", f"small lasting tree loss: {round(loss)} m²"))
    harvest = None
    if loss < IGNORE_M2:
        harvest = cut_and_regrown(trees, {y: v.get("vh") for y, v in yrs.items()}, gfw)
        if harvest:
            y, dip, peak, back, radar = harvest
            found.append(("review", f"trees cut around {y} (about {round(dip)} m², {dip / peak:.0%} of the trees) and "
                                    f"grown back by {back}: looks like a plantation harvest"
                                    + (f"; radar confirms ({radar:.1f} dB drop)" if radar >= RADAR_DROP else "")))
    if gfw >= IGNORE_M2 and loss < IGNORE_M2 and not harvest:
        found.append(("review", f"Global Forest Watch records {round(gfw)} m² of loss since 2017 that the agent doesn't see"))

    # Water and river beds: under water for a real part of the time.
    wet = {y: (v.get("wet") or 0) for y, v in yrs.items()}
    wet_share = sum(wet.values()) / len(wet)
    land, land_now = land_type(yrs, last_year - 1), land_type(yrs, last_year)
    river = wet_share >= WET_SHARE
    for t in (land, land_now):
        if river and t["type"] in ("water", "bare"):
            t["name"] = "Water / river bed"
    if river or land_now["type"] == "water":
        wet_years = sum(1 for v in wet.values() if v >= WET_SHARE)
        found.append(("review", f"water or river bed, not farmland: under water about {wet_share:.0%} of the time since "
                                f"{FIRST_YEAR} ({wet_years} of {len(wet)} years wet at least a quarter of the year); "
                                f"{last_year} so far: under water {wet[last_year]:.0%} of the time"))

    building_flag = any(l == "flag" and "building" in w for l, w in found)
    if building_flag:
        verdict = "Flag"
    elif any(l == "flag" for l, _ in found):
        verdict = "Flag" if conf >= SURE else "A person checks"
        if conf < SURE:
            found.append(("review", f"confidence only {conf:.0%}, so it is not flagged automatically"))
    elif found:
        verdict = "A person checks"
    elif conf >= SURE:
        verdict, found = "Approve", [("ok", "no lasting tree loss and no building inside")]
    else:
        verdict, found = "A person checks", [("review", f"nothing found, but confidence only {conf:.0%}")]
    return {"verdict": verdict, "reasons": found, "confidence": conf, "trees": trees, "built": built,
            "base": base, "now": now, "loss": loss, "drop_year": drop_year, "gfw": gfw, "covered": covered,
            "land": land, "land_now": land_now, "wet": wet, "vh": {y: v.get("vh") for y, v in yrs.items()}}


def cut_and_regrown(trees, vh, gfw):
    """A big drop in tree area that grows back later (a plantation harvest, a felling that
    regrew). Needs a second witness so a noisy year doesn't trigger it: radar backscatter
    lower than in the other years, or Global Forest Watch loss. Returns
    (year, m² lost, peak m², year back, radar drop dB) or None."""
    ys = sorted(trees)
    for y in ys[1:]:
        peak = max(trees[z] for z in ys if z < y)
        dip = peak - trees[y]
        later = [z for z in ys if z > y and trees[z] >= 0.9 * peak]
        if dip < max(FLAG_M2, FLAG_SHARE * peak) or not later:
            continue
        others = sorted(v for z, v in vh.items() if z != y and v is not None)
        radar = (others[len(others) // 2] - vh[y]) if vh.get(y) is not None and others else 0
        if radar >= RADAR_DROP or gfw >= IGNORE_M2:
            return y, dip, peak, later[0], radar
    return None


LAND_NAMES = {"trees": "Forest / trees", "crops": "Agriculture", "shrub": "Shrub / grass",
              "built": "Urban / built-up", "bare": "Bare land", "water": "Water"}


def land_type(yrs, y):
    """What the parcel is in year y: the class covering most of it, its share of the parcel,
    and the calibrated chance (averaged over the parcel) that the land is that class."""
    m = yrs[y]["m2"]
    total = sum(m.get(c) or 0 for c in CLASSES) or 1
    shares = {c: (m.get(c) or 0) / total for c in CLASSES}
    top = max(shares, key=shares.get)
    return {"year": y, "type": top, "name": LAND_NAMES[top], "share": shares[top],
            "confidence": (yrs[y].get("pcal") or {}).get(top) or 0, "shares": shares}


def save_thumb(img, region, path, dims=300):
    urllib.request.urlretrieve(img.getThumbURL({"region": region, "dimensions": dims, "format": "png"}), path)


def pictures(name, geom, maps, outdir, last_year, tick=lambda: None):
    region = geom.buffer(120).bounds()
    edge = ee.Image().paint(ee.FeatureCollection([ee.Feature(geom)]), 1, 2).visualize(palette=["ffff00"])
    safe = re.sub(r"[^A-Za-z0-9]+", "_", name).strip("_")
    out = {}
    for y in (FIRST_YEAR, last_year):
        # October to December: after the monsoon, forests are in leaf and the air is clear.
        rgb = s2_clear(geom, f"{y}-10-01", f"{y + 1}-01-01").select(["B4", "B3", "B2"]).median()
        p = rgb.reduceRegion(ee.Reducer.percentile([2, 98]), region, 10).getInfo()
        # Remove each colour's haze (its darkest values), then use one scale for all three,
        # which keeps the natural colour balance.
        lo = [p[f"{b}_p2"] for b in ("B4", "B3", "B2")]
        span = max(p[f"{b}_p98"] - p[f"{b}_p2"] for b in ("B4", "B3", "B2"))
        vis = rgb.visualize(min=lo, max=[v + span for v in lo], gamma=1.2)
        f = f"{safe}_photo_{y}.png"
        save_thumb(vis.blend(edge), region, os.path.join(outdir, f))
        out[f"photo_{y}"] = f
        tick()
        f = f"{safe}_map_{y}.png"
        save_thumb(classes(maps[y][0]).visualize(min=0, max=5, palette=PALETTE).blend(edge), region, os.path.join(outdir, f))
        out[f"map_{y}"] = f
        tick()
    return out


# Work units per parcel, for progress: the satellite numbers take most of the time,
# then four pictures.
NUMBER_UNITS, PICTURE_UNITS = 6, 4
UNITS_PER_PARCEL = NUMBER_UNITS + PICTURE_UNITS


def check(path, outdir, title=None, progress=None):
    """Checks every parcel in a KML (file or folder) and writes report.html to outdir.
    progress, if given, is called as parcels move along with a dict: units done and in
    all, parcels done and started. It may raise to stop the check."""
    import threading
    from concurrent.futures import ThreadPoolExecutor
    os.makedirs(outdir, exist_ok=True)
    last_year = datetime.date.today().year
    if not os.path.exists(CALIB_FILE):
        sys.exit("No calibration.json yet: run  python landcheck.py accuracy  first.")
    calib = json.load(open(CALIB_FILE))
    full, base = model(True), model(False)
    parcels = list(read_parcels(path))
    lock = threading.Lock()
    state = {"units": 0, "total": len(parcels) * UNITS_PER_PARCEL, "done": 0, "started": 0, "parcels": len(parcels)}

    def tick(units=0, started=0, done=0):
        with lock:
            state["units"] += units
            state["started"] += started
            state["done"] += done
            now = dict(state)
        if progress:
            progress(now)

    def one(parcel):
        name, src, ring = parcel
        tick(started=1)
        geom = ee.Geometry.Polygon([ring])
        maps = year_maps(geom, last_year, full, base)
        r = parcel_numbers(geom, maps, calib)
        tick(NUMBER_UNITS)
        d = decide(r, last_year)
        d.update(name=name, src=src, area=r["area"], centre=r["centre"], ring=ring)
        d["pics"] = pictures(name, geom, maps, outdir, last_year, tick=lambda: tick(1))
        tick(done=1)
        return d

    print(f"Checking {len(parcels)} parcel(s), a few at a time...")
    with ThreadPoolExecutor(4) as pool:
        futures = [pool.submit(one, p) for p in parcels]
        try:
            results = [f.result() for f in futures]
        except BaseException:
            pool.shutdown(wait=False, cancel_futures=True)  # don't start the parcels still waiting
            raise
    for d in results:
        print(f"\n== {d['name']}  ({d['src']})")
        print(f"   {round(d['area'])} m², {d['centre'][1]:.5f} N {d['centre'][0]:.5f} E")
        print("   year  trees m²  built m²")
        for y in sorted(d["trees"]):
            print(f"   {y}  {round(d['trees'][y]):8}  {round(d['built'][y]):8}{'  (to date)' if y == last_year else ''}")
        print(f"   LAND TYPE NOW ({d['land_now']['year']} so far): {land_text(d['land_now'])}")
        print(f"   last full year ({d['land']['year']}): {land_text(d['land'])}")
        print(f"   VERDICT: {d['verdict']}  (confidence {d['confidence']:.0%})")
        for _, why in d["reasons"]:
            print("    -", why)
    json.dump(results, open(os.path.join(outdir, "results.json"), "w"), indent=1, default=str)
    report(results, outdir, last_year, title)
    print("\nReport:", os.path.join(outdir, "report.html"))
    return results


# ---------- report ----------

def land_text(t):
    others = ", ".join(f"{LAND_NAMES[c].lower()} {s:.0%}" for c, s in sorted(t["shares"].items(), key=lambda x: -x[1])
                       if c != t["type"] and s >= 0.05)
    return (f"{t['name']} ({t['share']:.0%} of the parcel{', ' + others if others else ''}), "
            f"{t['confidence']:.0%} sure")


def chart(trees, area):
    ys = sorted(trees)
    w, h, left = 360, 120, 34
    top = max(area, 1)
    bw = (w - left) / len(ys)
    bars = "".join(
        f'<rect x="{left + i * bw + 3:.0f}" y="{h - 18 - (trees[y] / top) * (h - 30):.0f}" width="{bw - 6:.0f}" '
        f'height="{(trees[y] / top) * (h - 30):.0f}" fill="#1b7f3b"><title>{y}: {round(trees[y])} m²</title></rect>'
        f'<text x="{left + i * bw + bw / 2:.0f}" y="{h - 4}" text-anchor="middle">{str(y)[2:]}</text>'
        for i, y in enumerate(ys))
    return (f'<svg viewBox="0 0 {w} {h}" class="chart"><text x="0" y="12">trees m²</text>'
            f'<line x1="{left}" x2="{w}" y1="{h - 18}" y2="{h - 18}" stroke="#999"/>{bars}</svg>')


def report(results, outdir, last_year, title=None):
    import base64
    colour = {"Approve": "ok", "A person checks": "review", "Flag": "flag"}
    cards = []
    for d in results:
        lat, lng = d["centre"][1], d["centre"][0]
        reasons = "".join(f"<li>{html.escape(w)}</li>" for _, w in d["reasons"])
        d["trees"] = {int(k): v for k, v in d["trees"].items()}
        d["built"] = {int(k): v for k, v in d["built"].items()}
        wet = {int(k): v for k, v in d.get("wet", {}).items()}
        rows = "".join(f"<tr><td>{y}</td><td>{round(d['trees'][y])}</td><td>{round(d['built'][y])}</td>"
                       f"<td>{wet.get(y, 0):.0%}</td></tr>" for y in sorted(d["trees"]))
        # Pictures go inside the page, so the one file can be shared.
        p = {k: "data:image/png;base64," + base64.b64encode(open(os.path.join(outdir, f), "rb").read()).decode()
             for k, f in d["pics"].items()}
        cards.append(f"""
<section class="card">
  <header><h2>{html.escape(d['name'])}</h2><span class="badge {colour[d['verdict']]}">{d['verdict']}</span></header>
  <p class="meta">{round(d['area'])} m² ({d['area'] / 4046.86:.2f} acres) · {html.escape(d['src'])} ·
     <a href="https://www.google.com/maps?q={lat:.6f},{lng:.6f}&amp;t=k&amp;z=18" target="_blank">open in Google Maps (pin at the parcel centre)</a> ·     confidence {d['confidence']:.0%}</p>
  <p class="land"><b>Land type now ({d['land_now']['year']} so far):</b> {html.escape(land_text(d['land_now']))}<br>
     <span class="meta">Last full year ({d['land']['year']}): {html.escape(land_text(d['land']))}</span></p>
  <ul class="reasons">{reasons}</ul>
  <div class="grid">
    <figure><img src="{p['photo_' + str(FIRST_YEAR)]}"><figcaption>Satellite, Oct–Dec {FIRST_YEAR}</figcaption></figure>
    <figure><img src="{p['photo_' + str(last_year)]}"><figcaption>Satellite, Oct–Dec {last_year}</figcaption></figure>
    <figure><img src="{p['map_' + str(FIRST_YEAR)]}"><figcaption>What the agent sees, {FIRST_YEAR}</figcaption></figure>
    <figure><img src="{p['map_' + str(last_year)]}"><figcaption>What the agent sees, {last_year}</figcaption></figure>
  </div>
  <div class="numbers">{chart(d['trees'], d['area'])}
    <table><tr><th>Year</th><th>Trees m²</th><th>Built m²</th><th>Under water</th></tr>{rows}</table></div>
  <p class="meta">Global Forest Watch loss since 2017: {round(d['gfw'])} m² · tree cover {round(d['base'])} m² in {FIRST_YEAR}–{FIRST_YEAR + 2}, best recent year {round(d['now'])} m²</p>
</section>""")
    legend = "".join(f'<span><i style="background:#{c}"></i>{n}</span>' for n, c in zip(CLASSES, PALETTE))
    page = f"""<!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>{html.escape(title or "Land check")} · report</title><style>
body{{font:14px/1.45 system-ui,sans-serif;margin:0;background:#f6f6f3;color:#1d1d1b}}
main{{max-width:900px;margin:0 auto;padding:16px}} h1{{font-size:20px}} h2{{font-size:16px;margin:0}}
.card{{background:#fff;border:1px solid #ddd;border-radius:10px;padding:16px;margin:16px 0}}
header{{display:flex;justify-content:space-between;align-items:center;gap:8px}}
.badge{{padding:3px 10px;border-radius:99px;font-weight:600}} .ok{{background:#e3f4e4;color:#1d6b2c}}
.review{{background:#fdf1d6;color:#8a5a00}} .flag{{background:#fbe3e1;color:#a3261c}}
.meta{{color:#666}} .grid{{display:grid;grid-template-columns:repeat(auto-fit,minmax(190px,1fr));gap:8px}}
figure{{margin:0}} img{{width:100%;border-radius:6px;image-rendering:auto}} figcaption{{font-size:12px;color:#666}}
.numbers{{display:flex;flex-wrap:wrap;gap:16px;align-items:flex-start;margin-top:12px}}
.chart{{width:360px;max-width:100%;font-size:10px;fill:#555}} table{{border-collapse:collapse;font-size:13px}}
td,th{{border-bottom:1px solid #eee;padding:2px 10px;text-align:right}}
.legend span{{margin-right:12px;white-space:nowrap}} .legend i{{display:inline-block;width:10px;height:10px;margin-right:4px;border-radius:2px}}
</style></head><body><main>
<h1>{html.escape(title or "Land check")} · {len(results)} parcel(s) · {datetime.date.today():%d %b %Y}</h1>
<p>{" · ".join(f'<span class="badge {colour[v]}">{n} {v}</span>' for v in ("Flag", "A person checks", "Approve") if (n := sum(1 for d in results if d["verdict"] == v)))}</p>
<p class="meta">Test version: the model learned from global land maps, not yet from our own farms, and its thresholds are not calibrated. Yellow line = the parcel. {last_year} is the year to date.</p>
<p class="legend">{legend}</p>
{''.join(cards)}
</main></body></html>"""
    open(os.path.join(outdir, "report.html"), "w", encoding="utf-8").write(page)


if __name__ == "__main__":
    cmd = sys.argv[1] if len(sys.argv) > 1 else ""
    if cmd == "train":
        train()
    elif cmd == "accuracy":
        accuracy()
    elif cmd == "report" and len(sys.argv) > 2:
        report(json.load(open(os.path.join(sys.argv[2], "results.json"))), sys.argv[2], datetime.date.today().year)
    elif cmd == "check" and len(sys.argv) > 2:
        check(sys.argv[2], sys.argv[3] if len(sys.argv) > 3 else os.path.join(os.path.dirname(os.path.abspath(sys.argv[2])), "report"))
    else:
        print(__doc__)
