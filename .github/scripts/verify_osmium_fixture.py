#!/usr/bin/env python3

import json
import sys


def main():
    if len(sys.argv) != 2:
        raise SystemExit("usage: verify_osmium_fixture.py <geojsonseq>")

    path = sys.argv[1]

    types = set()
    count = 0

    with open(path, "r", encoding="utf-8") as handle:
        for raw in handle:
            raw = raw.lstrip("\x1e").strip()

            if not raw:
                continue

            feature = json.loads(raw)
            props = feature.get("properties") or {}

            osm_type = props.get("@type")
            osm_id = props.get("@id")

            if osm_type not in {"node", "way", "relation"}:
                continue

            if osm_id is None:
                raise AssertionError(f"Feature {osm_type} sem @id")

            types.add(osm_type)
            count += 1

    if count == 0:
        raise AssertionError("Nenhuma feature foi exportada pelo osmium")

    missing = {"node", "way", "relation"} - types

    if missing:
        raise AssertionError(
            "Tipos OSM ausentes no pipeline da fixture: "
            + ", ".join(sorted(missing))
        )

    print(
        "OSMIUM_FIXTURE_PASS "
        f"count={count} "
        f"types={','.join(sorted(types))}"
    )


if __name__ == "__main__":
    main()