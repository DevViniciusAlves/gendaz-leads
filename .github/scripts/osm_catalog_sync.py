#!/usr/bin/env python3
"""
OSM Catalog Sync Import Script

Processes GeoJSONSeq output from osmium and imports into Neon PostgreSQL staging,
then atomically publishes to osm_places.
"""

import argparse
import json
import re
import sys
import os
import psycopg2
from psycopg2.extras import execute_batch
from datetime import datetime
from typing import Dict, Any, Optional, List, Tuple


COMMERCIAL_TAG_KEYS = {
    'shop', 'amenity', 'craft', 'healthcare', 'leisure', 'office', 'tourism'
}


CONTACT_PHONE_KEYS = [
    'contact:whatsapp',
    'whatsapp',
    'contact:phone',
    'phone',
    'contact:mobile',
    'mobile',
]
CONTACT_WEBSITE_KEYS = ['contact:website', 'website', 'url']
CONTACT_EMAIL_KEYS = ['contact:email', 'email']
CONTACT_INSTAGRAM_KEYS = ['contact:instagram', 'instagram']

ADDRESS_KEYS = [
    'addr:street', 'addr:housenumber', 'addr:suburb', 'addr:neighbourhood',
    'addr:district', 'addr:city', 'addr:town', 'addr:village', 'addr:municipality',
    'addr:state', 'addr:country'
]


BATCH_SIZE = 1000
SANITY_DROP_THRESHOLD = 0.10  # 10%


def parse_args():
    parser = argparse.ArgumentParser(description='OSM Catalog Sync Import')
    parser.add_argument('--sync-run-id', required=True, type=int)
    parser.add_argument('--region-id', required=True, type=int)
    parser.add_argument('--city', required=True)
    parser.add_argument('--state', required=True)
    parser.add_argument('--country-code', required=True)
    parser.add_argument('--input', required=True, help='Input GeoJSONSeq file')
    return parser.parse_args()


def log(level: str, message: str, **kwargs):
    extra = ' '.join(f'{k}={v}' for k, v in kwargs.items())
    print(f'[osm-sync] {level} {message} {extra}', file=sys.stderr)


def connect_db(database_url: str):
    return psycopg2.connect(database_url)


def validate_sync_run(conn, sync_run_id: int, region_id: int):
    with conn.cursor() as cur:
        cur.execute("""
            SELECT status, region_id FROM osm_sync_runs WHERE id = %s
        """, (sync_run_id,))
        row = cur.fetchone()
        if not row:
            raise ValueError(f'Sync run {sync_run_id} not found')
        status, run_region_id = row
        if run_region_id != region_id:
            raise ValueError(f'Sync run {sync_run_id} belongs to region {run_region_id}, not {region_id}')
        if status not in ('QUEUED', 'RUNNING'):
            raise ValueError(f'Sync run {sync_run_id} has invalid status: {status}')


def mark_running(conn, sync_run_id: int):
    with conn.cursor() as cur:
        cur.execute("""
            UPDATE osm_sync_runs SET status = 'RUNNING', started_at = NOW()
            WHERE id = %s
        """, (sync_run_id,))
    conn.commit()


def mark_failed(
    conn,
    sync_run_id: int,
    region_id: int,
    error_message: str,
):
    safe_error = error_message[:1000]

    with conn.cursor() as cur:
        cur.execute(
            """
            UPDATE osm_sync_runs
            SET
                status = 'FAILED',
                finished_at = NOW(),
                error_message = %s
            WHERE id = %s
              AND status <> 'SUCCESS'
            """,
            (safe_error, sync_run_id),
        )

        cur.execute(
            """
            UPDATE osm_catalog_regions
            SET
                last_error = %s,
                last_attempt_at = NOW(),
                updated_at = NOW()
            WHERE id = %s
            """,
            (safe_error, region_id),
        )
    conn.commit()


def mark_success(conn, sync_run_id: int, stats: Dict[str, int]):
    with conn.cursor() as cur:
        cur.execute("""
            UPDATE osm_sync_runs
            SET status = 'SUCCESS', finished_at = NOW(),
                places_read = %s, places_staged = %s,
                places_inserted = %s, places_updated = %s, places_deactivated = %s
            WHERE id = %s
        """, (stats['read'], stats['staged'], stats['inserted'], stats['updated'], stats['deactivated'], sync_run_id))
    conn.commit()


def first_present(tags: Dict[str, Any], keys: List[str]) -> Optional[str]:
    for k in keys:
        v = tags.get(k)
        if v is not None and str(v).strip():
            return str(v).strip()
    return None


def first_phone_like(tags: Dict[str, Any], keys: List[str]) -> Optional[str]:
    for key in keys:
        value = tags.get(key)

        if value is None:
            continue

        raw = str(value).strip()

        if not raw:
            continue

        for candidate in raw.split(';'):
            candidate = candidate.strip()

            if not candidate:
                continue

            digits = re.sub(r'\D', '', candidate)

            if len(digits) >= 8:
                return candidate

    return None


def build_address(tags: Dict[str, Any], city: str, state: str) -> Optional[str]:
    street = tags.get('addr:street')
    number = tags.get('addr:housenumber')
    suburb = first_present(tags, ['addr:suburb', 'addr:neighbourhood', 'addr:district'])
    addr_city = first_present(tags, ['addr:city', 'addr:town', 'addr:village', 'addr:municipality'])
    addr_state = tags.get('addr:state')

    head = []
    if street:
        street_part = str(street)
        if number:
            street_part += f' {number}'
        head.append(street_part)
    elif number:
        head.append(str(number))
    if suburb:
        head.append(str(suburb))

    tail = []
    if addr_city:
        tail.append(str(addr_city))
    else:
        tail.append(city)
    if addr_state:
        tail.append(str(addr_state))
    else:
        tail.append(state)

    left = ', '.join(head)
    right = ' - '.join(tail)
    if left and right:
        return f'{left}, {right}'
    if left:
        return left
    if right:
        return right
    return None


def normalize_name(name: str) -> str:
    import unicodedata
    s = unicodedata.normalize('NFD', name.strip().lower())
    s = ''.join(c for c in s if not unicodedata.combining(c))
    s = ' '.join(s.split())
    return s


def representative_ring_point(ring):
    if not ring:
        raise ValueError('Empty polygon ring')

    points = ring

    if len(points) > 1 and points[0] == points[-1]:
        points = points[:-1]

    if not points:
        raise ValueError('Polygon ring has no usable points')

    x = sum(float(p[0]) for p in points) / len(points)
    y = sum(float(p[1]) for p in points) / len(points)

    return x, y


def extract_representative_coords(feature: Dict[str, Any]) -> Tuple[float, float]:
    geom = feature.get('geometry')
    if not geom:
        raise ValueError('No geometry in feature')
    gtype = geom.get('type')
    coords = geom.get('coordinates')
    if gtype == 'Point':
        return float(coords[0]), float(coords[1])
    elif gtype in ('LineString', 'MultiLineString'):
        # Use first point
        if gtype == 'LineString':
            return float(coords[0][0]), float(coords[0][1])
        else:
            return float(coords[0][0][0]), float(coords[0][0][1])
    elif gtype in ('Polygon', 'MultiPolygon'):
        if gtype == 'Polygon':
            ring = coords[0]
        else:
            ring = coords[0][0]

        x, y = representative_ring_point(ring)

        return float(x), float(y)
    else:
        raise ValueError(f'Unsupported geometry type: {gtype}')


def is_commercial(tags: Dict[str, Any]) -> bool:
    return any(k in tags for k in COMMERCIAL_TAG_KEYS)


def has_contact_signal(tags: Dict[str, Any]) -> bool:
    if first_phone_like(tags, CONTACT_PHONE_KEYS) is not None:
        return True

    if first_present(tags, CONTACT_WEBSITE_KEYS) is not None:
        return True

    if first_present(tags, CONTACT_EMAIL_KEYS) is not None:
        return True

    if first_present(tags, CONTACT_INSTAGRAM_KEYS) is not None:
        return True

    return False


def is_catalog_candidate(tags: Dict[str, Any]) -> bool:
    return is_commercial(tags) or has_contact_signal(tags)


def parse_feature(feature: Dict[str, Any], sync_run_id: int, region_id: int,
                  city: str, state: str, country_code: str) -> Optional[Dict[str, Any]]:
    props = feature.get('properties', {})

    # Osmium exports tags as flat properties with @type, @id, @timestamp
    osm_type = props.get('@type', '').lower()
    osm_id = props.get('@id')

    # Extract tags (all properties except @-prefixed ones)
    tags = {
        key: value
        for key, value in props.items()
        if not key.startswith('@')
    }

    if osm_type not in ('node', 'way', 'relation'):
        return None
    if osm_id is None:
        return None

    # Require catalog candidate (commercial OR contact signal) AND name
    if not is_catalog_candidate(tags):
        return None

    name = first_present(tags, ['name'])
    if not name:
        return None

    name = first_present(tags, ['name'])
    if not name:
        return None

    # Extract contacts
    phone = first_phone_like(tags, CONTACT_PHONE_KEYS)
    website = first_present(tags, CONTACT_WEBSITE_KEYS)
    email = first_present(tags, CONTACT_EMAIL_KEYS)
    instagram = first_present(tags, CONTACT_INSTAGRAM_KEYS)

    # Coordinates
    try:
        lon, lat = extract_representative_coords(feature)
    except Exception as e:
        log('warn', 'Failed to extract coordinates', osm_type=osm_type, osm_id=osm_id, error=str(e))
        return None

    # Address
    address = build_address(tags, city, state)

    # Source timestamp
    timestamp_str = props.get('@timestamp')
    source_timestamp = None
    if timestamp_str:
        try:
            # Try ISO format first
            source_timestamp = datetime.fromisoformat(timestamp_str.replace('Z', '+00:00'))
        except Exception:
            try:
                # Try epoch seconds
                source_timestamp = datetime.fromtimestamp(float(timestamp_str))
            except Exception:
                pass

    return {
        'sync_run_id': sync_run_id,
        'region_id': region_id,
        'osm_type': osm_type,
        'osm_id': osm_id,
        'business_name': name,
        'normalized_name': normalize_name(name),
        'latitude': lat,
        'longitude': lon,
        'address': address,
        'city': city,
        'state': state,
        'country': 'Brasil' if country_code == 'br' else country_code.upper(),
        'country_code': country_code,
        'phone': phone,
        'email': email,
        'website': website,
        'instagram': instagram,
        'tags': json.dumps(tags, ensure_ascii=False),
        'source_timestamp': source_timestamp,
    }


def insert_staging_batch(conn, batch: List[Dict[str, Any]]):
    sql = """
        INSERT INTO osm_place_staging (
            sync_run_id, region_id, osm_type, osm_id,
            business_name, normalized_name,
            latitude, longitude,
            address, city, state, country, country_code,
            phone, email, website, instagram,
            tags, source_timestamp
        ) VALUES (
            %(sync_run_id)s, %(region_id)s, %(osm_type)s, %(osm_id)s,
            %(business_name)s, %(normalized_name)s,
            %(latitude)s, %(longitude)s,
            %(address)s, %(city)s, %(state)s, %(country)s, %(country_code)s,
            %(phone)s, %(email)s, %(website)s, %(instagram)s,
            %(tags)s::jsonb, %(source_timestamp)s
        )
        ON CONFLICT (sync_run_id, osm_type, osm_id) DO NOTHING
    """
    with conn.cursor() as cur:
        execute_batch(cur, sql, batch, page_size=BATCH_SIZE)
    conn.commit()


def validate_staging(conn, sync_run_id: int) -> int:
    with conn.cursor() as cur:
        cur.execute("""
            SELECT COUNT(*) FROM osm_place_staging WHERE sync_run_id = %s
        """, (sync_run_id,))
        return cur.fetchone()[0]


def get_previous_count(conn, region_id: int) -> int:
    with conn.cursor() as cur:
        cur.execute("""
            SELECT COUNT(*) FROM osm_places WHERE region_id = %s AND active = true
        """, (region_id,))
        return cur.fetchone()[0]


def sanity_check(conn, sync_run_id: int, region_id: int, staged_count: int) -> bool:
    previous_count = get_previous_count(conn, region_id)
    if previous_count > 50 and staged_count == 0:
        log('error', 'Sanity check failed: previous count > 50 but staged count is 0',
            previous=previous_count, staged=staged_count)
        return False
    if previous_count > 50 and staged_count < previous_count * SANITY_DROP_THRESHOLD:
        log('error', 'Sanity check failed: staged count dropped below 10% of previous',
            previous=previous_count, staged=staged_count, threshold=SANITY_DROP_THRESHOLD)
        return False
    return True


def publish_staging(
    conn,
    sync_run_id: int,
    region_id: int,
    stats: Dict[str, int],
):
    try:
        with conn:
            with conn.cursor() as cur:
                cur.execute(
                    """
                    SELECT COUNT(*)
                    FROM osm_place_staging s
                    LEFT JOIN osm_places p
                      ON p.osm_type = s.osm_type
                     AND p.osm_id = s.osm_id
                    WHERE s.sync_run_id = %s
                      AND p.id IS NULL
                    """,
                    (sync_run_id,),
                )

                inserted = cur.fetchone()[0]

                staged = stats["staged"]
                updated = max(0, staged - inserted)

                cur.execute(
                    """
                    INSERT INTO osm_places (
                        region_id,
                        osm_type,
                        osm_id,
                        business_name,
                        normalized_name,
                        latitude,
                        longitude,
                        address,
                        city,
                        state,
                        country,
                        country_code,
                        phone,
                        email,
                        website,
                        instagram,
                        tags,
                        active,
                        last_seen_at,
                        source_timestamp
                    )
                    SELECT
                        s.region_id,
                        s.osm_type,
                        s.osm_id,
                        s.business_name,
                        s.normalized_name,
                        s.latitude,
                        s.longitude,
                        s.address,
                        s.city,
                        s.state,
                        s.country,
                        s.country_code,
                        s.phone,
                        s.email,
                        s.website,
                        s.instagram,
                        s.tags,
                        true,
                        NOW(),
                        s.source_timestamp
                    FROM osm_place_staging s
                    WHERE s.sync_run_id = %s
                    ON CONFLICT (osm_type, osm_id)
                    DO UPDATE SET
                        region_id = EXCLUDED.region_id,
                        business_name = EXCLUDED.business_name,
                        normalized_name = EXCLUDED.normalized_name,
                        latitude = EXCLUDED.latitude,
                        longitude = EXCLUDED.longitude,
                        address = EXCLUDED.address,
                        city = EXCLUDED.city,
                        state = EXCLUDED.state,
                        country = EXCLUDED.country,
                        country_code = EXCLUDED.country_code,
                        phone = EXCLUDED.phone,
                        email = EXCLUDED.email,
                        website = EXCLUDED.website,
                        instagram = EXCLUDED.instagram,
                        tags = EXCLUDED.tags,
                        active = true,
                        last_seen_at = NOW(),
                        source_timestamp = EXCLUDED.source_timestamp,
                        updated_at = NOW()
                    """,
                    (sync_run_id,),
                )

                cur.execute(
                    """
                    UPDATE osm_places
                    SET
                        active = false,
                        updated_at = NOW()
                    WHERE region_id = %s
                      AND active = true
                      AND (osm_type, osm_id) NOT IN (
                          SELECT osm_type, osm_id
                          FROM osm_place_staging
                          WHERE sync_run_id = %s
                      )
                    """,
                    (region_id, sync_run_id),
                )

                deactivated = cur.rowcount

                cur.execute(
                    """
                    UPDATE osm_catalog_regions
                    SET
                        catalog_status = 'READY',
                        last_success_at = NOW(),
                        last_error = NULL,
                        place_count = (
                            SELECT COUNT(*)
                            FROM osm_places
                            WHERE region_id = %s
                              AND active = true
                        ),
                        updated_at = NOW()
                    WHERE id = %s
                    """,
                    (region_id, region_id),
                )

                cur.execute(
                    """
                    UPDATE osm_sync_runs
                    SET
                        status = 'SUCCESS',
                        finished_at = NOW(),
                        places_read = %s,
                        places_staged = %s,
                        places_inserted = %s,
                        places_updated = %s,
                        places_deactivated = %s
                    WHERE id = %s
                    """,
                    (
                        stats["read"],
                        stats["staged"],
                        inserted,
                        updated,
                        deactivated,
                        sync_run_id,
                    ),
                )

                cur.execute(
                    """
                    DELETE FROM osm_place_staging
                    WHERE sync_run_id = %s
                    """,
                    (sync_run_id,),
                )

        return inserted, updated, deactivated

    except Exception:
        conn.rollback()
        raise


def clean_staging(conn, sync_run_id: int):
    with conn.cursor() as cur:
        cur.execute("DELETE FROM osm_place_staging WHERE sync_run_id = %s", (sync_run_id,))
    conn.commit()


def update_region_error(conn, region_id: int, error_message: str):
    with conn.cursor() as cur:
        cur.execute("""
            UPDATE osm_catalog_regions
            SET last_error = %s,
                last_attempt_at = NOW(),
                updated_at = NOW()
            WHERE id = %s
        """, (error_message[:1000], region_id))
    conn.commit()


def main():
    args = parse_args()

    database_url = os.environ.get('OSM_SYNC_DATABASE_URL')
    if not database_url:
        log('error', 'OSM_SYNC_DATABASE_URL environment variable not set')
        sys.exit(1)

    log('info', 'staging_start', sync_run_id=args.sync_run_id, region_id=args.region_id)

    conn = None
    try:
        conn = connect_db(database_url)
        conn.autocommit = False

        validate_sync_run(conn, args.sync_run_id, args.region_id)
        mark_running(conn, args.sync_run_id)

        stats = {'read': 0, 'staged': 0, 'inserted': 0, 'updated': 0, 'deactivated': 0}
        batch = []

        with open(args.input, 'r', encoding='utf-8') as f:
            for line_num, line in enumerate(f, 1):
                # Handle record separator (0x1e) if present
                line = line.lstrip('\x1e').strip()
                if not line:
                    continue
                stats['read'] += 1

                try:
                    feature = json.loads(line)
                except json.JSONDecodeError as e:
                    log('warn', 'Invalid JSON line', line=line_num, error=str(e))
                    continue

                parsed = parse_feature(feature, args.sync_run_id, args.region_id,
                                       args.city, args.state, args.country_code)
                if parsed:
                    batch.append(parsed)
                    if len(batch) >= BATCH_SIZE:
                        insert_staging_batch(conn, batch)
                        stats['staged'] += len(batch)
                        batch = []

        if batch:
            insert_staging_batch(conn, batch)
            stats['staged'] += len(batch)

        log('info', 'staging_complete', **stats)

        # Validate staging
        staged_count = validate_staging(conn, args.sync_run_id)
        if staged_count != stats['staged']:
            log('warn', 'Staging count mismatch', expected=stats['staged'], actual=staged_count)
            stats['staged'] = staged_count

        # Sanity check
        if not sanity_check(conn, args.sync_run_id, args.region_id, staged_count):
            raise ValueError('Sanity check failed: extreme drop in place count')

        # Publish atomically (single transaction - includes SUCCESS marking and staging cleanup)
        log('info', 'publish_start', sync_run_id=args.sync_run_id)
        inserted, updated, deactivated = publish_staging(
            conn,
            args.sync_run_id,
            args.region_id,
            stats,
        )

        stats["inserted"] = inserted
        stats["updated"] = updated
        stats["deactivated"] = deactivated

        log(
            "info",
            "publish_success",
            inserted=inserted,
            updated=updated,
            deactivated=deactivated,
        )

    except Exception as e:
        log('error', 'sync_failed', error=str(e))
        if conn:
            try:
                conn.rollback()
                mark_failed(conn, args.sync_run_id, args.region_id, str(e))
            except Exception:
                pass
        sys.exit(1)
    finally:
        if conn:
            conn.close()


if __name__ == '__main__':
    main()