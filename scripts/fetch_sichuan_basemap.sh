#!/usr/bin/env bash
# Download sichuan-basemap.mbtiles into app assets (not stored in git).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DEST="$ROOT/app/app/src/main/assets/sichuan-basemap.mbtiles"
TAG="${1:-v0.2.0-sichuan-debug}"
REPO="${REPO:-huming0618/global-river-datasets}"
URL="https://github.com/${REPO}/releases/download/${TAG}/sichuan-basemap.mbtiles"
mkdir -p "$(dirname "$DEST")"
if [[ -f "$DEST" && $(stat -c%s "$DEST") -gt 1000000 ]]; then
  echo "Already present: $DEST ($(du -h "$DEST" | cut -f1))"
  exit 0
fi
echo "Downloading $URL"
curl -L --fail -o "$DEST" "$URL"
ls -lh "$DEST"
