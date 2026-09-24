#!/usr/bin/env python3
"""Offline proof that the production boundary flow works without Nominatim.

Proves:
    PBF fixture
    -> osmium getid -r (relation + complete references)
    -> boundary.osm.pbf (valid, contains the relation and its references)
    -> osmium extract -p boundary.osm.pbf (accepts the OSM boundary file)
    -> city extract contains inner objects and excludes outer ones.

Usage:
    verify_boundary_fixture.py <boundary.osm> <city.geojsonseq>
"""

import json
import sys
import xml.etree.ElementTree as ET


def load_boundary(path):
    tree = ET.parse(path)
    root = tree.getroot()
    nodes = {n.get("id") for n in root.findall("node")}
    ways = {}
    for way in root.findall("way"):
        ways[way.get("id")] = [nd.get("ref") for nd in way.findall("nd")]
    relations = {}
    for rel in root.findall("relation"):
        relations[rel.get("id")] = (
            {t.get("k"): t.get("v") for t in rel.findall("tag")},
            [(m.get("type"), m.get("ref"), m.get("role"))
             for m in rel.findall("member")],
        )
    return nodes, ways, relations


def load_features(path):
    features = []
    with open(path, "r", encoding="utf-8") as handle:
        for raw in handle:
            raw = raw.lstrip("\x1e").strip()
            if not raw:
                continue
            features.append(json.loads(raw))
    return features


def key_of(feature):
    props = feature.get("properties") or {}
    return (props.get("@type"), props.get("@id"))


def main():
    if len(sys.argv) != 3:
        raise SystemExit(
            "usage: verify_boundary_fixture.py "
            "<boundary.osm> <city.geojsonseq>"
        )

    boundary_path, city_path = sys.argv[1], sys.argv[2]

    nodes, ways, relations = load_boundary(boundary_path)
    if "100" not in relations:
        raise AssertionError(
            "boundary.osm.pbf does not contain relation r100"
        )
    tags, members = relations["100"]
    if ("way", "200", "outer") not in members:
        raise AssertionError(
            "relation r100 is missing member way 200/outer"
        )
    if "200" not in ways:
        raise AssertionError(
            "boundary.osm.pbf is missing referenced way 200 "
            "(osmium getid -r must include references)"
        )
    missing_nodes = [ref for ref in ways["200"] if ref not in nodes]
    if missing_nodes:
        raise AssertionError(
            "boundary.osm.pbf is missing referenced nodes "
            + ",".join(sorted(set(missing_nodes)))
        )

    city_keys = {key_of(f) for f in load_features(city_path)}
    if ("node", 110) not in city_keys:
        raise AssertionError("city extract is missing inner node 110")
    if ("node", 111) not in city_keys:
        raise AssertionError("city extract is missing inner node 111")
    if ("node", 112) in city_keys:
        raise AssertionError(
            "city extract leaked outer node 112 "
            "(boundary polygon was not applied)"
        )

    print(
        "BOUNDARY_FIXTURE_PASS "
        "relation=r100 "
        "inner=n110,n111 "
        "outer_excluded=n112"
    )


if __name__ == "__main__":
    main()
