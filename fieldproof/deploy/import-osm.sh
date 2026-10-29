#!/bin/bash
# One-time (or occasional) import of India's districts, blocks (talukas) and
# villages from OpenStreetMap into PostGIS. Runs on the server:
#   nohup /opt/fieldproof/import-osm.sh > /var/log/fieldproof-osm.log 2>&1 &
set -euxo pipefail
cd /opt/fieldproof
. ./.env
mkdir -p /data/osm
cd /data/osm
if [ ! -s india.osm.pbf ]; then
  curl -fL --retry 3 -o india.osm.pbf.part https://download.geofabrik.de/asia/india-latest.osm.pbf
  mv india.osm.pbf.part india.osm.pbf
fi
ls -la india.osm.pbf
PG="PG:host=db dbname=fieldproof user=fieldproof password=$DB_PASSWORD"
GDAL="docker run --rm --network fieldproof_default -v /data/osm:/data ghcr.io/osgeo/gdal:ubuntu-small-3.10.0"
$GDAL ogr2ogr -f PostgreSQL "$PG" /data/india.osm.pbf multipolygons -nln osm_admin -overwrite \
  -lco GEOMETRY_NAME=geom -lco SPATIAL_INDEX=GIST -nlt PROMOTE_TO_MULTI \
  -where "boundary='administrative' AND admin_level IN ('4','5','6')" \
  --config OSM_MAX_TMPFILE_SIZE 1024 --config PG_USE_COPY YES
$GDAL ogr2ogr -f PostgreSQL "$PG" /data/india.osm.pbf points -nln osm_places -overwrite \
  -lco GEOMETRY_NAME=geom -lco SPATIAL_INDEX=GIST \
  -where "place IN ('village','hamlet','town','city','suburb')" \
  --config OSM_MAX_TMPFILE_SIZE 1024 --config PG_USE_COPY YES
docker compose -f docker-compose.prod.yml exec -T db psql -U fieldproof -v ON_ERROR_STOP=1 -f - < /opt/fieldproof/build-places.sql
echo OSM_IMPORT_DONE
