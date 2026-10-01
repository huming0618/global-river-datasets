#!/usr/bin/env python3
"""Attach OSM nearest-waterway names to unnamed HydroRIVERS Sichuan segments.

For each HydroRIVERS feature lacking a usable Chinese name, find the nearest
named OSM waterway (from osm_waterways_sichuan) within a distance threshold,
preferring same-ish direction and river > canal > stream when ambiguous.

Writes inferred `name` + `name_source=osm_nearest` (or main_riv_fallback).
Original synthetic placeholders (主河道/河段 ID) are cleared when unmatched so
the app can show 未命名河段 · #id.

Usage:
  python3 scripts/label_hydrorivers_from_osm.py
  python3 scripts/label_hydrorivers_from_osm.py --threshold-m 120 --assets-dir app/app/src/main/assets
"""
from __future__ import annotations

import argparse
import gzip
import json
import math
import os
import re
import shutil
import sys
import time
from collections import Counter, defaultdict
from typing import Any

from shapely.geometry import mapping, shape
from shapely import STRtree
from shapely.ops import nearest_points

PLACEHOLDER_RE = re.compile(r"^(主河道|河段)\s*\d+$")
WATERWAY_RANK = {"river": 0, "canal": 1, "stream": 2}


def load_gz(path: str) -> dict:
    with gzip.open(path, "rt", encoding="utf-8") as f:
        return json.load(f)


def load_json(path: str) -> dict:
    with open(path, "r", encoding="utf-8") as f:
        return json.load(f)


def write_gz(path: str, obj: dict) -> None:
    tmp = path + ".tmp"
    # compact JSON then gzip
    raw_path = tmp + ".json"
    with open(raw_path, "w", encoding="utf-8") as f:
        json.dump(obj, f, ensure_ascii=False, separators=(",", ":"))
    with open(raw_path, "rb") as i, gzip.open(tmp, "wb", compresslevel=9) as o:
        shutil.copyfileobj(i, o)
    os.replace(tmp, path)
    try:
        os.remove(raw_path)
    except OSError:
        pass


def is_usable_name(name: str | None) -> bool:
    n = (name or "").strip()
    if not n:
        return False
    if PLACEHOLDER_RE.match(n):
        return False
    if n.startswith("未命名") or n.startswith("HydroRIVERS") or n.startswith("OSM "):
        return False
    return True


def line_bearing_deg(geom) -> float | None:
    """Bearing of overall line direction (first→last vertex), degrees [0,360)."""
    try:
        if geom.geom_type == "MultiLineString":
            # longest part
            geom = max(geom.geoms, key=lambda g: g.length)
        coords = list(geom.coords)
        if len(coords) < 2:
            return None
        x1, y1 = coords[0]
        x2, y2 = coords[-1]
        if x1 == x2 and y1 == y2:
            return None
        br = math.degrees(math.atan2(x2 - x1, y2 - y1))  # from north, clockwise-ish via atan2(east, north)
        return br % 360.0
    except Exception:
        return None


def angular_diff_deg(a: float, b: float) -> float:
    """Smallest angle between two bearings, treating opposite (180°) as aligned for undirected rivers."""
    d = abs(a - b) % 360.0
    if d > 180.0:
        d = 360.0 - d
    # undirected: 170° heading mismatch ≈ 10° if one is reversed
    return min(d, 180.0 - d)


def meters_scale(lat: float) -> tuple[float, float]:
    m_lon = 111320.0 * max(0.2, math.cos(math.radians(lat)))
    m_lat = 110540.0
    return m_lon, m_lat


def distance_m(a, b, m_lon: float, m_lat: float) -> float:
    p1, p2 = nearest_points(a, b)
    dx = (p1.x - p2.x) * m_lon
    dy = (p1.y - p2.y) * m_lat
    return math.hypot(dx, dy)


def score_candidate(
    dist_m: float,
    waterway: str,
    dir_diff: float | None,
    threshold_m: float,
) -> float:
    """Lower is better."""
    ww_pen = WATERWAY_RANK.get(waterway, 3) * 25.0
    dir_pen = 0.0
    if dir_diff is not None:
        # 0° → 0, 45° → ~20, 90° → 60
        dir_pen = (dir_diff / 90.0) * 60.0
    # slight preference for closer when tied
    return dist_m + ww_pen + dir_pen


def build_network_index(all_path: str | None) -> tuple[dict[int, int], dict[int, int]]:
    """HYRIV_ID -> NEXT_DOWN and HYRIV_ID -> ORD_STRA from full Asia clip."""
    if not all_path or not os.path.isfile(all_path):
        return {}, {}
    print(f"Loading NEXT_DOWN network from {all_path} ...")
    t0 = time.time()
    data = load_json(all_path)
    next_down: dict[int, int] = {}
    ord_stra: dict[int, int] = {}
    for feat in data["features"]:
        p = feat.get("properties") or {}
        hid = p.get("HYRIV_ID")
        if hid is None:
            continue
        try:
            hid_i = int(hid)
        except (TypeError, ValueError):
            continue
        nd = p.get("NEXT_DOWN")
        if nd is not None:
            try:
                nd_i = int(nd)
                if nd_i > 0:
                    next_down[hid_i] = nd_i
            except (TypeError, ValueError):
                pass
        try:
            ord_stra[hid_i] = int(p.get("ORD_STRA") or 0)
        except (TypeError, ValueError):
            ord_stra[hid_i] = 0
    print(f"  NEXT_DOWN links: {len(next_down)} ({time.time() - t0:.1f}s)")
    return next_down, ord_stra


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument(
        "--assets-dir",
        default=None,
        help="Directory with hydrorivers/osm .geojson.gz (default: app/app/src/main/assets)",
    )
    ap.add_argument("--threshold-m", type=float, default=120.0, help="Max match distance meters (default 120)")
    ap.add_argument(
        "--query-pad-m",
        type=float,
        default=180.0,
        help="STRtree buffer pad meters before precise filter (default 180)",
    )
    ap.add_argument(
        "--dir-max-deg",
        type=float,
        default=55.0,
        help="Max undirected direction difference to accept (default 55)",
    )
    ap.add_argument(
        "--hydro-all",
        default=None,
        help="Optional hydrorivers_sichuan_all.geojson for MAIN_RIV fallback",
    )
    ap.add_argument("--no-fallback", action="store_true", help="Skip NEXT_DOWN name propagation / 支流 fallback")
    ap.add_argument("--dry-run", action="store_true", help="Compute stats only; do not write")
    args = ap.parse_args()

    root = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
    assets = args.assets_dir or os.path.join(root, "app", "app", "src", "main", "assets")
    hydro_path = os.path.join(assets, "hydrorivers_sichuan.geojson.gz")
    osm_path = os.path.join(assets, "osm_waterways_sichuan.geojson.gz")
    if not os.path.isfile(hydro_path) or not os.path.isfile(osm_path):
        print(f"Missing assets under {assets}", file=sys.stderr)
        return 1

    hydro_all = args.hydro_all
    if hydro_all is None:
        cand = "/workspace/river-data/sichuan/hydrorivers_sichuan_all.geojson"
        if os.path.isfile(cand):
            hydro_all = cand

    print(f"Loading hydro {hydro_path}")
    hydro = load_gz(hydro_path)
    print(f"Loading osm   {osm_path}")
    osm = load_gz(osm_path)

    # Build OSM named index
    osm_geoms = []
    osm_meta: list[tuple[str, str, Any]] = []  # name, waterway, osm_id
    for feat in osm["features"]:
        p = feat.get("properties") or {}
        name = (p.get("name") or "").strip()
        if not name:
            continue
        try:
            g = shape(feat["geometry"])
        except Exception:
            continue
        if g.is_empty:
            continue
        ww = (p.get("waterway") or "").lower()
        osm_geoms.append(g)
        osm_meta.append((name, ww, p.get("osm_id")))
    print(f"OSM named waterways: {len(osm_geoms)}")
    tree = STRtree(osm_geoms)

    next_down, ord_stra_map = ({}, {}) if args.no_fallback else build_network_index(hydro_all)

    threshold = float(args.threshold_m)
    pad_m = float(args.query_pad_m)
    dir_max = float(args.dir_max_deg)

    stats = Counter()

    t0 = time.time()
    features = hydro["features"]
    n = len(features)

    for i, feat in enumerate(features):
        props = feat.setdefault("properties", {})
        original = props.get("name")
        original_s = (original if isinstance(original, str) else "") or ""
        original_s = original_s.strip()

        if is_usable_name(original_s) and props.get("name_source") not in (
            "osm_nearest",
            "network_propagate",
            "network_tributary",
        ):
            # Already has a real name from upstream — leave alone
            stats["already_usable"] += 1
            continue

        # Needs labeling (empty or placeholder)
        stats["needs_label"] += 1
        try:
            hgeom = shape(feat["geometry"])
        except Exception:
            stats["bad_geom"] += 1
            continue
        if hgeom.is_empty:
            stats["bad_geom"] += 1
            continue

        lat = hgeom.centroid.y
        m_lon, m_lat = meters_scale(lat)
        buf_deg = pad_m / min(m_lon, m_lat)
        try:
            idxs = list(tree.query(hgeom.buffer(buf_deg)))
        except Exception:
            idxs = []

        h_br = line_bearing_deg(hgeom)
        best = None  # (score, dist_m, name, ww, osm_id, dir_diff)

        for oi in idxs:
            oname, oww, oid = osm_meta[oi]
            ogeom = osm_geoms[oi]
            try:
                dm = distance_m(hgeom, ogeom, m_lon, m_lat)
            except Exception:
                continue
            if dm > threshold:
                continue
            o_br = line_bearing_deg(ogeom)
            dir_diff = None
            if h_br is not None and o_br is not None:
                dir_diff = angular_diff_deg(h_br, o_br)
                if dir_diff > dir_max:
                    # allow very close matches even if direction disagrees (confluence noise)
                    if dm > threshold * 0.45:
                        continue
            sc = score_candidate(dm, oww, dir_diff, threshold)
            cand = (sc, dm, oname, oww, oid, dir_diff)
            if best is None or cand[0] < best[0]:
                best = cand

        if best is not None:
            _, dm, oname, oww, oid, dir_diff = best
            props["name_original"] = original_s
            props["name"] = oname
            props["name_source"] = "osm_nearest"
            props["name_osm_id"] = oid
            props["name_dist_m"] = round(dm, 1)
            if oww:
                props["name_waterway"] = oww
            stats["labeled_osm"] += 1
            stats[f"osm_ww_{oww or 'other'}"] += 1
        else:
            # Clear synthetic placeholders so list shows 未命名河段
            if PLACEHOLDER_RE.match(original_s):
                props["name_original"] = original_s
                props["name"] = ""
                props.pop("name_source", None)
                stats["cleared_placeholder"] += 1
            else:
                stats["still_unnamed_pass1"] += 1

        if (i + 1) % 5000 == 0:
            print(f"  ... {i + 1}/{n}  osm_labeled={stats['labeled_osm']}")

    print(f"Pass 1 done in {time.time() - t0:.1f}s")

    # Pass 2: propagate OSM names along NEXT_DOWN when ORD_STRA matches (same stem).
    # Pass 3: direct tributary join → "{name}支流" when NEXT_DOWN is a higher-order named stem.
    if not args.no_fallback and next_down:
        by_id: dict[int, dict] = {}
        for feat in features:
            props = feat.get("properties") or {}
            hid = props.get("HYRIV_ID")
            if hid is None:
                continue
            try:
                by_id[int(hid)] = props
            except (TypeError, ValueError):
                continue

        # reverse adjacency: downstream -> upstreams
        upstreams: dict[int, list[int]] = defaultdict(list)
        for hid, nd in next_down.items():
            if hid in by_id and nd in by_id:
                upstreams[nd].append(hid)

        def segment_ord(hid: int, props: dict) -> int:
            if hid in ord_stra_map:
                return ord_stra_map[hid]
            try:
                return int(props.get("ORD_STRA") or 0)
            except (TypeError, ValueError):
                return 0

        # BFS from OSM-labeled seeds, same ORD_STRA only
        from collections import deque

        queue: deque[int] = deque()
        for hid, props in by_id.items():
            if props.get("name_source") == "osm_nearest" and is_usable_name(props.get("name")):
                queue.append(hid)

        visited_prop = set(queue)
        while queue:
            hid = queue.popleft()
            props = by_id[hid]
            name = props.get("name")
            o = segment_ord(hid, props)
            neighbors = []
            nd = next_down.get(hid)
            if nd is not None:
                neighbors.append(nd)
            neighbors.extend(upstreams.get(hid, []))
            for nb in neighbors:
                if nb in visited_prop or nb not in by_id:
                    continue
                np = by_id[nb]
                if is_usable_name(np.get("name")):
                    visited_prop.add(nb)
                    continue
                if segment_ord(nb, np) != o:
                    continue
                np["name_original"] = np.get("name_original") or (np.get("name") or "")
                np["name"] = name
                np["name_source"] = "network_propagate"
                np["name_via"] = hid
                stats["labeled_propagate"] += 1
                visited_prop.add(nb)
                queue.append(nb)

        print(f"Propagated along network: {stats['labeled_propagate']}")

        # Tributary fallback: unnamed whose NEXT_DOWN is named with higher ORD_STRA
        for hid, props in by_id.items():
            if is_usable_name(props.get("name")):
                continue
            nd = next_down.get(hid)
            if nd is None or nd not in by_id:
                continue
            down = by_id[nd]
            if down.get("name_source") not in ("osm_nearest", "network_propagate"):
                continue
            stem = down.get("name")
            if not is_usable_name(stem) or str(stem).endswith("支流"):
                continue
            if segment_ord(nd, down) <= segment_ord(hid, props):
                continue
            props["name_original"] = props.get("name_original") or (props.get("name") or "")
            props["name"] = f"{stem}支流"
            props["name_source"] = "network_tributary"
            props["name_via"] = nd
            stats["labeled_fallback"] += 1

        print(f"Tributary fallback labels: {stats['labeled_fallback']}")

    still = sum(1 for f in features if not is_usable_name((f.get("properties") or {}).get("name")))
    newly = stats["labeled_osm"] + stats["labeled_propagate"] + stats["labeled_fallback"]
    print("--- stats ---")
    print(f"total_features:     {n}")
    print(f"already_usable:     {stats['already_usable']}")
    print(f"needs_label:        {stats['needs_label']}")
    print(f"labeled_osm:        {stats['labeled_osm']}")
    print(f"labeled_propagate:  {stats['labeled_propagate']}")
    print(f"labeled_fallback:   {stats['labeled_fallback']}")
    print(f"cleared_placeholder:{stats['cleared_placeholder']}")
    print(f"still_unnamed:      {still}")
    print(f"newly_named:        {newly}")
    for k in sorted(stats):
        if k.startswith("osm_ww_"):
            print(f"{k}: {stats[k]}")

    if args.dry_run:
        print("Dry-run: not writing.")
        return 0

    # Also write uncompressed copy under data/sichuan if present
    print(f"Writing {hydro_path}")
    write_gz(hydro_path, hydro)
    out_data = os.path.join(root, "data", "sichuan")
    if os.path.isdir(out_data):
        plain = os.path.join(out_data, "hydrorivers_sichuan.geojson")
        with open(plain, "w", encoding="utf-8") as f:
            json.dump(hydro, f, ensure_ascii=False, separators=(",", ":"))
        print(f"Wrote {plain}")
        # mirror gz
        shutil.copy2(hydro_path, os.path.join(out_data, "hydrorivers_sichuan.geojson.gz"))

    # stats sidecar for release notes
    stats_path = os.path.join(root, "scripts", "label_hydrorivers_stats.json")
    with open(stats_path, "w", encoding="utf-8") as f:
        json.dump(
            {
                "total_features": n,
                "already_usable": stats["already_usable"],
                "needs_label": stats["needs_label"],
                "labeled_osm": stats["labeled_osm"],
                "labeled_propagate": stats["labeled_propagate"],
                "labeled_fallback": stats["labeled_fallback"],
                "cleared_placeholder": stats["cleared_placeholder"],
                "still_unnamed": still,
                "newly_named": newly,
                "threshold_m": threshold,
            },
            f,
            indent=2,
        )
    print(f"Wrote {stats_path}")
    print(f"gz_mb: {os.path.getsize(hydro_path) / 1e6:.2f}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
