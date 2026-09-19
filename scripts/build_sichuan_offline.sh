#!/usr/bin/env bash
# Build Sichuan offline river GeoJSON + optional OpenMapTiles MBTiles.
# Requires: curl, unzip, ogr2ogr (gdal-bin), osmium-tool, python3, tippecanoe (optional),
#           java + planetiler.jar (for basemap).
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SCRATCH="${SCRATCH_DIR:-/workspace/river-data/sichuan}"
ASSETS="$ROOT/app/app/src/main/assets"
OUT_DATA="$ROOT/data/sichuan"
BBOX="97.3 26.0 108.6 34.4"
HYDRO_URL="${HYDRO_URL:-https://data.hydrosheds.org/file/HydroRIVERS/HydroRIVERS_v10_as_shp.zip}"
OSM_URL="${OSM_URL:-https://download.geofabrik.de/asia/china/sichuan-latest.osm.pbf}"
PLANETILER_JAR="${PLANETILER_JAR:-$SCRATCH/planetiler.jar}"

mkdir -p "$SCRATCH" "$OUT_DATA" "$ASSETS"
cd "$SCRATCH"

echo "==> HydroRIVERS Asia"
if [[ ! -f HydroRIVERS_v10_as_shp.zip ]]; then
  curl -L --fail -o HydroRIVERS_v10_as_shp.zip "$HYDRO_URL"
fi
if [[ ! -f HydroRIVERS_v10_as_shp/HydroRIVERS_v10_as.shp ]]; then
  unzip -o HydroRIVERS_v10_as_shp.zip
fi

echo "==> Clip + filter ORD_STRA >= 2"
ogr2ogr -f GeoJSON -clipsrc $BBOX -lco COORDINATE_PRECISION=5 -simplify 0.0008 \
  hydrorivers_sichuan_all.geojson HydroRIVERS_v10_as_shp/HydroRIVERS_v10_as.shp
python3 - <<'PY'
import json, gzip, shutil, os
src=json.load(open('hydrorivers_sichuan_all.geojson'))
keep=['HYRIV_ID','ORD_STRA','LENGTH_KM','ENDORHEIC','DIS_AV_CMS','ORD_FLOW']
out={'type':'FeatureCollection','features':[]}
for f in src['features']:
    p=f['properties']
    if (p.get('ORD_STRA') or 0) < 2: continue
    props={k:p.get(k) for k in keep}
    props['name'] = f"主河道 {p.get('HYRIV_ID')}" if (p.get('ORD_STRA') or 0) >= 6 else ''
    out['features'].append({'type':'Feature','properties':props,'geometry':f['geometry']})
json.dump(out, open('hydrorivers_sichuan.geojson','w'), separators=(',',':'))
with open('hydrorivers_sichuan.geojson','rb') as i, gzip.open('hydrorivers_sichuan.geojson.gz','wb',compresslevel=9) as o:
    shutil.copyfileobj(i,o)
print('hydro features', len(out['features']), 'gz_mb', round(os.path.getsize('hydrorivers_sichuan.geojson.gz')/1e6,2))
PY

echo "==> OSM Sichuan waterways"
if [[ ! -f sichuan-latest.osm.pbf ]]; then
  curl -L --fail -o sichuan-latest.osm.pbf "$OSM_URL"
fi
osmium tags-filter sichuan-latest.osm.pbf w/waterway=river,stream,canal -o osm_waterways.osm.pbf --overwrite
rm -f osm_waterways_sichuan_raw.geojson
ogr2ogr -f GeoJSON -clipsrc $BBOX -lco COORDINATE_PRECISION=5 -simplify 0.0005 \
  -sql "SELECT osm_id, name, waterway FROM lines WHERE waterway IS NOT NULL" \
  osm_waterways_sichuan_raw.geojson osm_waterways.osm.pbf
python3 - <<'PY'
import json, gzip, shutil, os
d=json.load(open('osm_waterways_sichuan_raw.geojson'))
out={'type':'FeatureCollection','features':[]}
for f in d['features']:
    p=f.get('properties') or {}
    wt=(p.get('waterway') or '').lower(); name=p.get('name') or ''
    if wt in ('river','canal') or (wt=='stream' and name):
        out['features'].append({'type':'Feature','properties':{'name':name,'waterway':wt,'osm_id':p.get('osm_id')},'geometry':f['geometry']})
json.dump(out, open('osm_waterways_sichuan.geojson','w'), ensure_ascii=False, separators=(',',':'))
with open('osm_waterways_sichuan.geojson','rb') as i, gzip.open('osm_waterways_sichuan.geojson.gz','wb',compresslevel=9) as o:
    shutil.copyfileobj(i,o)
print('osm features', len(out['features']), 'gz_mb', round(os.path.getsize('osm_waterways_sichuan.geojson.gz')/1e6,2))
PY

if [[ "${SKIP_BASEMAP:-0}" != "1" ]]; then
  echo "==> Basemap MBTiles (planetiler z6-12)"
  if [[ ! -f "$PLANETILER_JAR" ]]; then
    curl -L --fail -o "$PLANETILER_JAR" \
      https://github.com/onthegomap/planetiler/releases/download/v0.10.2/planetiler.jar
  fi
  java -Xmx4g -jar "$PLANETILER_JAR" \
    --osm-path=sichuan-latest.osm.pbf \
    --output=sichuan-basemap.mbtiles \
    --bounds=97.3,26.0,108.6,34.4 \
    --minzoom=6 --maxzoom=12 \
    --download --fetch-wikidata=false --languages=zh,en \
    --force
  cp -f sichuan-basemap.mbtiles "$ASSETS/sichuan-basemap.mbtiles"
  cp -f sichuan-basemap.mbtiles "$OUT_DATA/sichuan-basemap.mbtiles"
fi

cp -f hydrorivers_sichuan.geojson.gz osm_waterways_sichuan.geojson.gz "$ASSETS/"
cp -f hydrorivers_sichuan.geojson osm_waterways_sichuan.geojson "$OUT_DATA/"
echo "Done. Assets in $ASSETS"
ls -lh "$ASSETS"
