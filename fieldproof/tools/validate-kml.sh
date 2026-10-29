#!/bin/sh
# Validates KML files against the official OGC KML 2.2 XML Schema (plus the
# Atom and OASIS xAL schemas it imports). Runs in Docker, no local installs.
#   tools/validate-kml.sh file1.kml [file2.kml ...]
set -e
dir=$(cd "$(dirname "$1")" && pwd -W 2>/dev/null || pwd)
files=""
for f in "$@"; do files="$files /kml/$(basename "$f")"; done
MSYS_NO_PATHCONV=1 docker run --rm -v "$dir:/kml:ro" alpine:3.22 sh -c "
  apk add --no-cache -q libxml2-utils curl >/dev/null
  mkdir -p /xsd && cd /xsd
  curl -sfL -o ogckml22.xsd https://schemas.opengis.net/kml/2.2.0/ogckml22.xsd
  curl -sfL -o atom-author-link.xsd https://schemas.opengis.net/kml/2.2.0/atom-author-link.xsd
  curl -sfL -o xAL.xsd http://docs.oasis-open.org/election/external/xAL.xsd
  sed -i 's#schemaLocation=\"[^\"]*atom-author-link.xsd\"#schemaLocation=\"atom-author-link.xsd\"#; s#schemaLocation=\"[^\"]*xAL.xsd\"#schemaLocation=\"xAL.xsd\"#' ogckml22.xsd
  grep -o 'schemaLocation=\"[^\"]*\"' ogckml22.xsd
  xmllint --noout --schema ogckml22.xsd $files
"
