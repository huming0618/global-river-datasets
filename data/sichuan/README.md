# Sichuan offline extracts

Processed outputs are produced by `scripts/build_sichuan_offline.sh` into
`/workspace/river-data/sichuan/` (scratch) and copied into `app/app/src/main/assets/`.

Committed assets (gzipped river GeoJSON) live under the Android app. The basemap
`sichuan-basemap.mbtiles` is **not** stored in Git; download via
`scripts/fetch_sichuan_basemap.sh` or the GitHub Release.
