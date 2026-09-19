#!/usr/bin/env python3
"""Validate data/datasets.json against required fields and docs pages."""
from __future__ import annotations

import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
CATALOG = ROOT / "data" / "datasets.json"
DOCS = ROOT / "docs" / "datasets"

REQUIRED = (
    "id",
    "name",
    "category",
    "provider",
    "coverage",
    "url",
    "license_note",
    "formats",
    "summary_zh",
)


def main() -> int:
    data = json.loads(CATALOG.read_text(encoding="utf-8"))
    datasets = data.get("datasets")
    if not isinstance(datasets, list) or not datasets:
        print("ERROR: datasets must be a non-empty list", file=sys.stderr)
        return 1

    errors: list[str] = []
    seen: set[str] = set()
    for i, item in enumerate(datasets):
        if not isinstance(item, dict):
            errors.append(f"[{i}] not an object")
            continue
        for key in REQUIRED:
            if key not in item or item[key] in (None, ""):
                errors.append(f"[{i}] missing {key}")
        did = item.get("id")
        if isinstance(did, str):
            if did in seen:
                errors.append(f"duplicate id: {did}")
            seen.add(did)
            page = DOCS / f"{did}.md"
            if not page.is_file():
                errors.append(f"missing docs page: docs/datasets/{did}.md")
        url = item.get("url", "")
        if isinstance(url, str) and url and not url.startswith(("http://", "https://")):
            errors.append(f"[{did}] url must start with http(s): {url!r}")
        formats = item.get("formats")
        if formats is not None and not isinstance(formats, list):
            errors.append(f"[{did}] formats must be a list")

    # Orphan docs pages (optional warn)
    if DOCS.is_dir():
        for md in DOCS.glob("*.md"):
            if md.name == "README.md":
                continue
            stem = md.stem
            if stem not in seen:
                errors.append(f"orphan docs page without catalog entry: {md.name}")

    if errors:
        print("Validation FAILED:")
        for e in errors:
            print(f"  - {e}")
        return 1
    print(f"OK: {len(datasets)} datasets validated.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
