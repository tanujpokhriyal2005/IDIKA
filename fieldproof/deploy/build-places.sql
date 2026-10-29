-- Turns the raw OSM import (osm_admin, osm_places) into the villages table:
-- each village tagged with the district (admin_level 5) and block
-- (admin_level 6) polygons it lies in.
\timing on
BEGIN;

CREATE OR REPLACE FUNCTION fp_name(name text, tags text) RETURNS text LANGUAGE sql IMMUTABLE AS $$
  SELECT btrim(regexp_replace(
    COALESCE(NULLIF(substring(tags from '"name:en"=>"([^"]*)"'), ''), name),
    '\s+(Taluka|Tehsil|Tahsil|Taluk|Mandal|Block|Subdivision|Subdistrict|Sub-District|District)$', '', 'i'))
$$;
CREATE OR REPLACE FUNCTION fp_canon(s text) RETURNS text LANGUAGE sql IMMUTABLE AS $$
  SELECT regexp_replace(lower(s), '[^a-z0-9]|h', '', 'g')
$$;

ALTER TABLE osm_admin ADD COLUMN IF NOT EXISTS name_clean text;
UPDATE osm_admin SET name_clean = fp_name(name, other_tags);
DELETE FROM osm_admin WHERE name_clean IS NULL OR name_clean = '';
CREATE INDEX IF NOT EXISTS osm_admin_level_idx ON osm_admin (admin_level);

DROP TABLE IF EXISTS osm_villages;
CREATE TABLE osm_villages AS
SELECT DISTINCT ON (d.name_clean, b.name_clean, fp_name(p.name, p.other_tags))
       d.name_clean AS district, b.name_clean AS block, fp_name(p.name, p.other_tags) AS name,
       p.place, ST_Y(p.geom) AS lat, ST_X(p.geom) AS lng
FROM osm_places p
JOIN osm_admin b ON b.admin_level = '6' AND ST_Contains(b.geom, p.geom)
JOIN osm_admin d ON d.admin_level = '5' AND ST_Contains(d.geom, p.geom)
WHERE p.name IS NOT NULL
ORDER BY d.name_clean, b.name_clean, fp_name(p.name, p.other_tags),
         CASE p.place WHEN 'village' THEN 0 WHEN 'town' THEN 1 WHEN 'hamlet' THEN 2 ELSE 3 END;

-- Re-spell seeded districts/blocks/villages the way OSM does, so they don't appear twice.
UPDATE villages v SET district = o.district
FROM (SELECT DISTINCT district FROM osm_villages) o
WHERE v.source = 'seed' AND fp_canon(v.district) = fp_canon(o.district) AND v.district <> o.district;
UPDATE surveyor_blocks sb SET district = o.district
FROM (SELECT DISTINCT district FROM osm_villages) o
WHERE fp_canon(sb.district) = fp_canon(o.district) AND sb.district <> o.district;
UPDATE villages v SET block = o.block
FROM (SELECT DISTINCT district, block FROM osm_villages) o
WHERE v.source = 'seed' AND v.district = o.district AND fp_canon(v.block) = fp_canon(o.block) AND v.block <> o.block;
UPDATE surveyor_blocks sb SET block = o.block
FROM (SELECT DISTINCT district, block FROM osm_villages) o
WHERE sb.district = o.district AND fp_canon(sb.block) = fp_canon(o.block) AND sb.block <> o.block;
-- Seeded villages that OSM also has: keep the seeded row (farmers point at it), add its coordinates.
UPDATE villages v SET name = o.name, lat = o.lat, lng = o.lng
FROM osm_villages o
WHERE v.source = 'seed' AND v.district = o.district AND v.block = o.block AND fp_canon(v.name) = fp_canon(o.name);

INSERT INTO villages (district, block, name, lat, lng, source)
SELECT district, block, name, lat, lng, 'osm' FROM osm_villages
ON CONFLICT (district, block, name) DO UPDATE SET lat = COALESCE(villages.lat, EXCLUDED.lat), lng = COALESCE(villages.lng, EXCLUDED.lng);

CREATE INDEX IF NOT EXISTS villages_block_idx ON villages (district, block);
COMMIT;

SELECT count(*) AS villages, count(DISTINCT district) AS districts, count(DISTINCT (district, block)) AS blocks FROM villages;
SELECT block, count(*) FROM villages WHERE district = 'Hingoli' GROUP BY block ORDER BY 1;
