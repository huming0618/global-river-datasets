#!/usr/bin/env python3
"""Extract OSM water surface polygons (areas) from Sichuan PBF → GeoJSON(.gz)."""
from __future__ import annotations

import gzip
import json
import os
import shutil
import sys
from typing import Any

import osmium
from osmium.geom import GeoJSONFactory

WATER_LANDUSE = {"reservoir", "basin"}
# Skip tiny decorative features / swimming pools etc.
SKIP_WATER = {"swimming_pool", "reflecting_pool", "fountain", "wastewater"}
SKIP_NATURAL = {"mud", "beach", "sand"}


def is_water_area(tags: osmium.osm.TagList) -> bool:
    natural = (tags.get("natural") or "").lower()
    water = (tags.get("water") or "").lower()
    landuse = (tags.get("landuse") or "").lower()
    waterway = (tags.get("waterway") or "").lower()

    if natural == "water":
        if water in SKIP_WATER:
            return False
        return True
    if water and water not in SKIP_WATER:
        # water=* without natural sometimes appears on multipolygons
        if natural in SKIP_NATURAL:
            return False
        return True
    if landuse in WATER_LANDUSE:
        return True
    # riverbank / dock polygons occasionally tagged only as waterway=
    if waterway in {"riverbank", "dock", "boatyard"}:
        return True
    return False


def simplify_ring(coords: list, step: int) -> list:
    if step <= 1 or len(coords) <= 8:
        return coords
    out = coords[::step]
    if out[-1] != coords[-1]:
        out.append(coords[-1])
    # Ensure closed
    if out[0] != out[-1]:
        out.append(out[0])
    return out if len(out) >= 4 else coords


def round_coords(geom: dict, precision: int = 5, simplify_step: int = 2) -> dict | None:
    """Round + light vertex thinning for size. Returns Polygon/MultiPolygon or None."""
    gtype = geom.get("type")
    coords = geom.get("coordinates")
    if not gtype or coords is None:
        return None

    def fix_ring(ring):
        pts = [[round(c[0], precision), round(c[1], precision)] for c in ring]
        # drop consecutive duplicates
        cleaned = [pts[0]]
        for p in pts[1:]:
            if p != cleaned[-1]:
                cleaned.append(p)
        if len(cleaned) >= 4 and cleaned[0] != cleaned[-1]:
            cleaned.append(cleaned[0])
        cleaned = simplify_ring(cleaned, simplify_step)
        return cleaned if len(cleaned) >= 4 else None

    def fix_polygon(poly):
        rings = []
        for ring in poly:
            fixed = fix_ring(ring)
            if fixed is None:
                if not rings:
                    return None  # outer ring invalid
                continue
            rings.append(fixed)
        return rings if rings else None

    if gtype == "Polygon":
        fixed = fix_polygon(coords)
        if not fixed:
            return None
        return {"type": "Polygon", "coordinates": fixed}
    if gtype == "MultiPolygon":
        polys = []
        for poly in coords:
            fixed = fix_polygon(poly)
            if fixed:
                polys.append(fixed)
        if not polys:
            return None
        if len(polys) == 1:
            return {"type": "Polygon", "coordinates": polys[0]}
        return {"type": "MultiPolygon", "coordinates": polys}
    return None


def bbox_of(geom: dict) -> tuple[float, float, float, float] | None:
    minx = miny = float("inf")
    maxx = maxy = float("-inf")

    def walk(obj):
        nonlocal minx, miny, maxx, maxy
        if isinstance(obj, (list, tuple)) and obj and isinstance(obj[0], (int, float)):
            x, y = obj[0], obj[1]
            minx = min(minx, x)
            maxx = max(maxx, x)
            miny = min(miny, y)
            maxy = max(maxy, y)
        elif isinstance(obj, (list, tuple)):
            for c in obj:
                walk(c)

    walk(geom.get("coordinates"))
    if minx == float("inf"):
        return None
    return minx, miny, maxx, maxy


class WaterAreaHandler(osmium.SimpleHandler):
    def __init__(self, bbox: tuple[float, float, float, float] | None = None):
        super().__init__()
        self.factory = GeoJSONFactory()
        self.features: list[dict[str, Any]] = []
        self.bbox = bbox  # minlon, minlat, maxlon, maxlat
        self.seen = 0
        self.kept = 0
        self.skipped_geom = 0

    def area(self, a: osmium.osm.Area) -> None:
        if not is_water_area(a.tags):
            return
        self.seen += 1
        try:
            raw = self.factory.create_multipolygon(a)
        except Exception:
            self.skipped_geom += 1
            return
        if not raw:
            self.skipped_geom += 1
            return
        geom = json.loads(raw)
        geom = round_coords(geom, precision=5, simplify_step=2)
        if geom is None:
            self.skipped_geom += 1
            return
        bb = bbox_of(geom)
        if bb and self.bbox:
            minlon, minlat, maxlon, maxlat = self.bbox
            if bb[2] < minlon or bb[0] > maxlon or bb[3] < minlat or bb[1] > maxlat:
                return
        tags = a.tags
        props = {
            "name": tags.get("name") or "",
            "natural": tags.get("natural") or "",
            "water": tags.get("water") or "",
            "landuse": tags.get("landuse") or "",
            "waterway": tags.get("waterway") or "",
            "osm_id": a.orig_id() if hasattr(a, "orig_id") else a.id,
        }
        # Drop empty optional keys to save space
        props = {k: v for k, v in props.items() if v != "" or k in ("name", "osm_id")}
        self.features.append(
            {"type": "Feature", "properties": props, "geometry": geom}
        )
        self.kept += 1
        if self.kept % 500 == 0:
            print(f"  kept {self.kept} (seen water tags {self.seen})...", flush=True)


def main() -> int:
    pbf = sys.argv[1] if len(sys.argv) > 1 else "/workspace/river-data/sichuan/sichuan-latest.osm.pbf"
    out_gz = (
        sys.argv[2]
        if len(sys.argv) > 2
        else "/workspace/global-river-datasets/app/app/src/main/assets/osm_water_areas_sichuan.geojson.gz"
    )
    # Sichuan bbox (same as build script)
    bbox = (97.3, 26.0, 108.6, 34.4)

    print(f"Reading {pbf} ...", flush=True)
    h = WaterAreaHandler(bbox=bbox)
    # locations=True builds node cache; area callback triggers area assembly
    h.apply_file(pbf, locations=True, idx="flex_mem")
    print(f"Done. seen={h.seen} kept={h.kept} skipped_geom={h.skipped_geom}", flush=True)

    fc = {"type": "FeatureCollection", "features": h.features}
    out_json = out_gz[:-3] if out_gz.endswith(".gz") else out_gz + ".tmp.geojson"
    if out_gz.endswith(".gz"):
        # write uncompressed next to gz for inspection, then gzip
        with open(out_json, "w", encoding="utf-8") as f:
            json.dump(fc, f, ensure_ascii=False, separators=(",", ":"))
        with open(out_json, "rb") as i, gzip.open(out_gz, "wb", compresslevel=9) as o:
            shutil.copyfileobj(i, o)
        # remove large uncompressed from assets dir; keep under scratch if desired
        if "/assets/" in out_json:
            os.remove(out_json)
            scratch = "/workspace/river-data/sichuan/osm_water_areas_sichuan.geojson"
            with open(scratch, "w", encoding="utf-8") as f:
                json.dump(fc, f, ensure_ascii=False, separators=(",", ":"))
            with open(scratch, "rb") as i, gzip.open(scratch + ".gz", "wb", compresslevel=9) as o:
                shutil.copyfileobj(i, o)
            print(f"scratch: {scratch} ({os.path.getsize(scratch)/1e6:.2f} MB)", flush=True)
            print(f"scratch gz: {scratch}.gz ({os.path.getsize(scratch+'.gz')/1e6:.2f} MB)", flush=True)
    else:
        with open(out_gz, "w", encoding="utf-8") as f:
            json.dump(fc, f, ensure_ascii=False, separators=(",", ":"))

    print(f"Wrote {out_gz} ({os.path.getsize(out_gz)/1e6:.2f} MB), features={len(h.features)}", flush=True)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
