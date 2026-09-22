#!/usr/bin/env python3

import argparse
import json
import sys

import requests


def parse_args():
    parser = argparse.ArgumentParser()
    parser.add_argument("--osm-id", required=True, type=int)
    parser.add_argument("--output", required=True)
    return parser.parse_args()


def main():
    args = parse_args()

    response = requests.get(
        "https://nominatim.openstreetmap.org/lookup",
        params={
            "osm_ids": f"R{args.osm_id}",
            "format": "json",
            "polygon_geojson": 1,
        },
        headers={
            "User-Agent": "GendazLeads/1.0"
        },
        timeout=30,
    )

    response.raise_for_status()

    data = response.json()

    if not data:
        raise RuntimeError("Empty response from Nominatim")

    geometry = data[0].get("geojson")

    if not geometry:
        raise RuntimeError("No geojson in Nominatim response")

    if geometry.get("type") not in {"Polygon", "MultiPolygon"}:
        raise RuntimeError(
            f"Invalid boundary geometry type: {geometry.get('type')}"
        )

    feature = {
        "type": "Feature",
        "properties": {},
        "geometry": geometry,
    }

    with open(args.output, "w", encoding="utf-8") as handle:
        json.dump(feature, handle)

    print(
        "[osm-sync] boundary_ok "
        f"type={geometry.get('type')}"
    )


if __name__ == "__main__":
    try:
        main()
    except Exception as exc:
        print(
            f"[osm-sync] boundary_failed error={exc}",
            file=sys.stderr,
        )
        raise