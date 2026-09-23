#!/usr/bin/env python3
"""
OSM Catalog Sync Import Script

Processes GeoJSONSeq output from osmium and imports into Neon PostgreSQL staging,
then atomically publishes to osm_places.
"""

import argparse
import html
import ipaddress
import json
import re
import sys
import os
import socket
import urllib.error
import urllib.parse
import urllib.request
import psycopg2
from psycopg2.extras import execute_batch
from datetime import datetime
from typing import Dict, Any, Optional, List, Tuple
from math import radians, sin, cos, sqrt, atan2
from collections import defaultdict
from concurrent.futures import ThreadPoolExecutor, as_completed
from dataclasses import dataclass
from html.parser import HTMLParser


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

# Website enrichment constants with env overrides
def _env_int(name: str, default: int) -> int:
    try:
        val = os.environ.get(name)
        if val is not None and str(val).strip():
            return int(str(val).strip())
    except Exception:
        pass
    return default

WEBSITE_CONNECT_TIMEOUT_SECONDS = _env_int('OSM_SYNC_WEBSITE_CONNECT_TIMEOUT_SECONDS', 2)
WEBSITE_READ_TIMEOUT_SECONDS = _env_int('OSM_SYNC_WEBSITE_READ_TIMEOUT_SECONDS', 3)
WEBSITE_MAX_BYTES = _env_int('OSM_SYNC_WEBSITE_MAX_BYTES', 500_000)
WEBSITE_MAX_WORKERS = _env_int('OSM_SYNC_WEBSITE_MAX_WORKERS', 8)

WEBSITE_MAX_REDIRECTS = 3
WEBSITE_MAX_CONTACT_PAGES = 2

WHATSAPP_PATTERNS = [
    re.compile(r'(?i)(?:https?://)?(?:www\.)?wa\.me/([^&"\'\s<>]+)'),
    re.compile(r'(?i)(?:https?://)?(?:www\.)?(?:api|web)\.whatsapp\.com/send\?[^"\'\s>]*?phone=([^&"\'\s<>]+)'),
]

TEL_LINK_PATTERN = re.compile(r'''(?i)href\s*=\s*["']\s*tel:([^"']+)["']''')

GENERIC_PHONE_PATTERN = re.compile(r'(?:\+?55[\s().-]*)?(?:\(?\d{2}\)?[\s.-]*)?\d{4,5}[\s.-]?\d{4}')

EMAIL_PATTERN = re.compile(r'([A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,})', re.I)

INSTAGRAM_PATTERN = re.compile(r'(?:https?://)?(?:www\.)?instagram\.com/([A-Za-z0-9_.]+)', re.I)

CONTACT_KEYWORDS = ['contato', 'contact', 'fale-conosco', 'fale conosco', 'atendimento']

RESERVED_INSTAGRAM = {'p', 'reel', 'tv', 'explore', 'accounts', 'direct', 'developer', 'about', 'legal', 'press', 'jobs', 'api', 'graph', 'www'}


@dataclass
class WebsiteContactResult:
    phone: Optional[str]
    email: Optional[str]
    instagram: Optional[str]
    status: str


def is_public_ip(value: str) -> bool:
    try:
        ip = ipaddress.ip_address(value)
        return not (
            ip.is_private
            or ip.is_loopback
            or ip.is_link_local
            or ip.is_multicast
            or ip.is_reserved
            or ip.is_unspecified
        )
    except ValueError:
        return False


def is_safe_public_url(url: str) -> bool:
    try:
        parsed = urllib.parse.urlparse(url)

        if parsed.scheme not in ('http', 'https'):
            return False

        host = parsed.hostname
        if not host or host.lower() == 'localhost':
            return False

        infos = socket.getaddrinfo(
            host,
            parsed.port or (443 if parsed.scheme == 'https' else 80),
            type=socket.SOCK_STREAM,
        )

        if not infos:
            return False

        return all(
            is_public_ip(info[4][0])
            for info in infos
        )
    except Exception:
        return False


def normalize_website_url(raw) -> Optional[str]:
    if raw is None:
        return None

    value = str(raw).strip()
    if not value:
        return None

    if not value.startswith(('http://', 'https://')):
        value = 'https://' + value

    try:
        parsed = urllib.parse.urlparse(value)

        if (
            parsed.scheme not in ('http', 'https')
            or not parsed.hostname
        ):
            return None

        return parsed.geturl()
    except Exception:
        return None


def fetch_public_html(
    url: str,
    redirect_count: int = 0,
) -> Optional[str]:
    if redirect_count > WEBSITE_MAX_REDIRECTS:
        return None
    if not is_safe_public_url(url):
        return None
    try:
        req = urllib.request.Request(
            url,
            headers={'User-Agent': 'GendazLeads-OSM-Sync/1.0'},
            method='GET'
        )
        # use read timeout as overall timeout; connect timeout handled via env but urllib uses single timeout
        timeout = max(WEBSITE_CONNECT_TIMEOUT_SECONDS, 1) + max(WEBSITE_READ_TIMEOUT_SECONDS, 1)
        # opener without redirect
        # create custom handler to prevent auto redirect
        class NoRedirect(urllib.request.HTTPRedirectHandler):
            def redirect_request(self, req, fp, code, msg, headers, newurl):
                return None

        opener = urllib.request.build_opener(NoRedirect())
        # fallback: use opener.open
        try:
            resp = opener.open(req, timeout=timeout)
        except urllib.error.HTTPError as e:
            # Handle redirects manually
            if 300 <= e.code < 400:
                location = e.headers.get('Location') if e.headers else None
                if not location:
                    return None
                next_url = urllib.parse.urljoin(url, location)
                if not is_safe_public_url(next_url):
                    return None
                return fetch_public_html(next_url, redirect_count + 1)
            return None

        try:
            status = getattr(resp, 'status', resp.getcode())
            if 300 <= status < 400:
                location = resp.headers.get('Location')
                if not location:
                    return None
                next_url = urllib.parse.urljoin(url, location)
                if not is_safe_public_url(next_url):
                    return None
                return fetch_public_html(next_url, redirect_count + 1)
            if not (200 <= status < 300):
                return None
            content_type = resp.headers.get('Content-Type', '') or ''
            # If content-type indicates binary and not html/text, return None
            # but allow missing content-type and try to read
            if content_type:
                ct = content_type.lower()
                if 'text/html' not in ct and 'text/plain' not in ct and 'application/xhtml' not in ct and 'application/xml' not in ct:
                    # check for obvious binary
                    if any(x in ct for x in ['image/', 'video/', 'audio/', 'application/octet-stream', 'application/pdf', 'application/zip']):
                        return None
            data = resp.read(WEBSITE_MAX_BYTES + 1)
            if len(data) > WEBSITE_MAX_BYTES:
                # truncate to max
                data = data[:WEBSITE_MAX_BYTES]
            # decode
            text = data.decode('utf-8', errors='ignore')
            return text
        finally:
            try:
                resp.close()
            except Exception:
                pass
    except (urllib.error.URLError, socket.timeout, socket.gaierror, OSError, ValueError):
        return None
    except Exception:
        return None


def _extract_phone_from_html(html_text: str, country_code: str) -> Optional[str]:
    if not html_text:
        return None
    # priority 1..4 whatsapp and tel, then generic
    for pat in WHATSAPP_PATTERNS:
        for m in pat.finditer(html_text):
            raw = m.group(1)
            if raw:
                try:
                    raw = urllib.parse.unquote(raw)
                except Exception:
                    pass
                normalized = normalize_phone_for_catalog(raw, country_code or '')
                if normalized:
                    return normalized
    for m in TEL_LINK_PATTERN.finditer(html_text):
        raw = m.group(1)
        if raw:
            try:
                raw = urllib.parse.unquote(raw)
            except Exception:
                pass
            normalized = normalize_phone_for_catalog(raw, country_code or '')
            if normalized:
                return normalized
    for m in GENERIC_PHONE_PATTERN.finditer(html_text):
        raw = m.group(0)
        if raw:
            normalized = normalize_phone_for_catalog(raw, country_code or '')
            if normalized:
                return normalized
    return None


def _extract_email_from_html(html_text: str) -> Optional[str]:
    if not html_text:
        return None
    for m in EMAIL_PATTERN.finditer(html_text):
        candidate = m.group(1).strip()
        if candidate and '@' in candidate:
            # basic normalization lower
            val = candidate.lower()
            if re.match(r'^[^@\s]+@[^@\s]+\.[^@\s]+$', val):
                return val
    return None


def _extract_instagram_from_html(html_text: str) -> Optional[str]:
    if not html_text:
        return None
    for m in INSTAGRAM_PATTERN.finditer(html_text):
        handle = m.group(1).strip().strip('/')
        if not handle:
            continue
        # skip query params
        handle = handle.split('?')[0].split('#')[0].split('/')[0]
        lower = handle.lower()
        if lower in RESERVED_INSTAGRAM:
            continue
        # normalize: lower, alphanumeric _. keep as spec in WebsiteContactEnricher java
        if handle:
            return handle.lower()
    return None


def _find_contact_page_urls(base_url: str, html_text: str) -> List[str]:
    if not html_text or not base_url:
        return []
    try:
        base_parsed = urllib.parse.urlparse(base_url)
        base_host = (base_parsed.hostname or '').lower()
    except Exception:
        return []
    candidates = []
    # regex for anchor tags
    anchor_pat = re.compile(r'<a[^>]+href\s*=\s*["\']([^"\']+)["\'][^>]*>(.*?)</a>', re.I | re.S)
    for m in anchor_pat.finditer(html_text):
        href = m.group(1).strip()
        inner = re.sub(r'<[^>]+>', '', m.group(2) or '').strip()
        href_lower = href.lower()
        inner_lower = inner.lower()
        combined = href_lower + ' ' + inner_lower
        # check keyword
        has_keyword = any(kw in combined for kw in CONTACT_KEYWORDS)
        if not has_keyword:
            # also check path segment?
            continue
        # resolve url
        try:
            resolved = urllib.parse.urljoin(base_url, href)
            parsed = urllib.parse.urlparse(resolved)
            if parsed.scheme not in ('http', 'https'):
                continue
            host = (parsed.hostname or '').lower()
            if not host:
                continue
            # same hostname only
            if host != base_host:
                continue
            # also ensure not external social link
            candidates.append(resolved)
            if len(candidates) >= WEBSITE_MAX_CONTACT_PAGES:
                break
        except Exception:
            continue
    return candidates[:WEBSITE_MAX_CONTACT_PAGES]


def _extract_contact_from_html(html_text: str, country_code: str) -> Tuple[Optional[str], Optional[str], Optional[str]]:
    phone = _extract_phone_from_html(html_text, country_code)
    email = _extract_email_from_html(html_text)
    instagram = _extract_instagram_from_html(html_text)
    return phone, email, instagram


def fetch_website_contact_result(normalized_url: str, country_code: str) -> WebsiteContactResult:
    if not normalized_url:
        return WebsiteContactResult(None, None, None, 'INVALID_WEBSITE')
    if not is_safe_public_url(normalized_url):
        return WebsiteContactResult(None, None, None, 'UNSAFE_URL')
    html_text = fetch_public_html(normalized_url, 0)
    if html_text is None:
        return WebsiteContactResult(None, None, None, 'FETCH_FAILED')
    phone, email, instagram = _extract_contact_from_html(html_text, country_code)
    if phone:
        return WebsiteContactResult(phone, email, instagram, 'FOUND_PHONE')
    # try contact pages
    contact_urls = _find_contact_page_urls(normalized_url, html_text)
    best_email = email
    best_insta = instagram
    for curl in contact_urls:
        if not is_safe_public_url(curl):
            continue
        c_html = fetch_public_html(curl, 0)
        if c_html is None:
            continue
        c_phone, c_email, c_insta = _extract_contact_from_html(c_html, country_code)
        if c_email and not best_email:
            best_email = c_email
        if c_insta and not best_insta:
            best_insta = c_insta
        if c_phone:
            return WebsiteContactResult(c_phone, best_email, best_insta, 'FOUND_PHONE')
        # keep best
        if c_email and not email:
            email = c_email
        if c_insta and not instagram:
            instagram = c_insta
    # aggregated email/instagram but no phone
    return WebsiteContactResult(None, email or best_email, instagram or best_insta, 'NO_PHONE')


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


def has_valid_brazilian_ddd(national: str) -> bool:
    if national is None or len(national) not in (10, 11):
        return False

    if not national[:2].isdigit():
        return False

    ddd = int(national[:2])

    return 11 <= ddd <= 99


def normalize_phone_for_catalog(
    raw_phone: Optional[str],
    country_code: str,
) -> Optional[str]:
    if raw_phone is None:
        return None

    raw = str(raw_phone).strip()

    if not raw:
        return None

    for candidate in raw.split(';'):
        candidate = candidate.strip()

        if not candidate:
            continue

        digits = re.sub(r'\D', '', candidate)

        while digits.startswith('00') and len(digits) > 2:
            digits = digits[2:]

        if country_code == 'br':
            if len(digits) in (10, 11):
                if not has_valid_brazilian_ddd(digits):
                    continue

                return '55' + digits

            if (
                len(digits) in (12, 13)
                and digits.startswith('55')
            ):
                national = digits[2:]

                if not has_valid_brazilian_ddd(national):
                    continue

                if digits.startswith('5555'):
                    continue

                return digits

            continue

        if 8 <= len(digits) <= 15:
            return digits

    return None


def row_tags(row):
    tags = row.get('tags')

    if isinstance(tags, dict):
        return tags

    if isinstance(tags, str):
        try:
            parsed = json.loads(tags)
            return parsed if isinstance(parsed, dict) else {}
        except Exception:
            return {}

    return {}


def row_is_commercial(row):
    return is_commercial(row_tags(row))


def distance_meters(a, b):
    lat1 = a.get('latitude')
    lon1 = a.get('longitude')
    lat2 = b.get('latitude')
    lon2 = b.get('longitude')

    if None in (lat1, lon1, lat2, lon2):
        return float('inf')

    r = 6_371_000.0

    p1 = radians(float(lat1))
    p2 = radians(float(lat2))
    dp = radians(float(lat2) - float(lat1))
    dl = radians(float(lon2) - float(lon1))

    h = (
        sin(dp / 2.0) ** 2
        + cos(p1) * cos(p2) * sin(dl / 2.0) ** 2
    )

    return r * 2.0 * atan2(
        sqrt(h),
        sqrt(1.0 - h),
    )


def stable_osm_key(row):
    order = {
        'node': 0,
        'way': 1,
        'relation': 2,
    }

    return (
        order.get(row.get('osm_type'), 9),
        int(row.get('osm_id') or 0),
    )


def can_merge_rows(a, b, radius_meters):
    if (
        not a.get('normalized_name')
        or a.get('normalized_name')
        != b.get('normalized_name')
    ):
        return False

    if distance_meters(a, b) > radius_meters:
        return False

    a_commercial = row_is_commercial(a)
    b_commercial = row_is_commercial(b)

    if not a_commercial or not b_commercial:
        return True

    if a.get('osm_type') != b.get('osm_type'):
        return True

    a_phone = normalize_phone_for_catalog(
        a.get('phone'),
        a.get('country_code') or '',
    )

    b_phone = normalize_phone_for_catalog(
        b.get('phone'),
        b.get('country_code') or '',
    )

    if a_phone and b_phone and a_phone == b_phone:
        return True

    a_site = (a.get('website') or '').strip().lower()
    b_site = (b.get('website') or '').strip().lower()

    return bool(
        a_site
        and b_site
        and a_site == b_site
    )


def cluster_rows(rows, radius_meters):
    if not rows:
        return []

    parent = list(range(len(rows)))

    def find(i):
        while parent[i] != i:
            parent[i] = parent[parent[i]]
            i = parent[i]
        return i

    def union(a, b):
        ra = find(a)
        rb = find(b)

        if ra != rb:
            parent[rb] = ra

    for i in range(len(rows)):
        for j in range(i + 1, len(rows)):
            if can_merge_rows(
                rows[i],
                rows[j],
                radius_meters,
            ):
                union(i, j)

    grouped = {}

    for i, row in enumerate(rows):
        root = find(i)
        grouped.setdefault(root, []).append(row)

    return list(grouped.values())


def choose_canonical_member(cluster):
    commercial = [
        row
        for row in cluster
        if row_is_commercial(row)
    ]

    if not commercial:
        return None

    def score(row):
        phone = normalize_phone_for_catalog(
            row.get('phone'),
            row.get('country_code') or '',
        )

        return (
            0 if phone else 1,
            0 if row.get('address') else 1,
            stable_osm_key(row),
        )

    return sorted(
        commercial,
        key=score,
    )[0]


def merge_tags(base, cluster):
    merged = dict(row_tags(base))

    ordered = sorted(
        cluster,
        key=lambda row: (
            distance_meters(base, row),
            stable_osm_key(row),
        ),
    )

    for row in ordered:
        for key, value in row_tags(row).items():
            if (
                key not in merged
                or merged.get(key) in (None, '')
            ):
                merged[key] = value

    return merged


def first_cluster_value(base, cluster, field):
    ordered = [base] + sorted(
        [row for row in cluster if row is not base],
        key=lambda row: (
            distance_meters(base, row),
            stable_osm_key(row),
        ),
    )

    for row in ordered:
        value = row.get(field)

        if value is not None and str(value).strip():
            return str(value).strip()

    return None


def build_merged_cluster_candidate(cluster):
    base = choose_canonical_member(cluster)

    if base is None:
        return None

    country_code = base.get('country_code') or ''

    ordered = [base] + sorted(
        [row for row in cluster if row is not base],
        key=lambda row: (
            distance_meters(base, row),
            stable_osm_key(row),
        ),
    )

    phone = None
    phone_source = None

    for row in ordered:
        normalized = normalize_phone_for_catalog(
            row.get('phone'),
            country_code,
        )
        if normalized:
            phone = normalized
            # Determine if from base or companion
            if row is base:
                phone_source = 'direct'
            else:
                # companion phone (could be commercial second but treat as companion)
                phone_source = 'companion' if row not in [base] or True else 'companion'
                # Distinguish direct vs companion: if first phone found is not base, it's companion
                if phone_source is None:
                    phone_source = 'companion'
            # more precise: if row is base => direct else companion
            phone_source = 'direct' if row is base else 'companion'
            break

    result = dict(base)
    # merge website/email/instagram before phone decision
    result['website'] = first_cluster_value(base, cluster, 'website')
    result['email'] = first_cluster_value(base, cluster, 'email')
    result['instagram'] = first_cluster_value(base, cluster, 'instagram')
    result['tags'] = json.dumps(merge_tags(base, cluster), ensure_ascii=False)
    if phone:
        result['phone'] = phone
        result['_phone_source'] = phone_source
    else:
        result['phone'] = None
        result['_phone_source'] = None
    return result


def enrich_candidate_from_osm_website(candidate, website_cache):
    # phone already valid -> no HTTP
    existing = normalize_phone_for_catalog(candidate.get('phone'), candidate.get('country_code') or '')
    if existing:
        candidate['phone'] = existing
        return WebsiteContactResult(existing, candidate.get('email'), candidate.get('instagram'), 'FOUND_PHONE')

    website_raw = candidate.get('website')
    normalized_url = normalize_website_url(website_raw)
    if not normalized_url:
        return WebsiteContactResult(None, None, None, 'INVALID_WEBSITE')
    country_code = candidate.get('country_code') or ''
    # cache check
    if normalized_url in website_cache:
        cached = website_cache[normalized_url]
        if cached.phone:
            candidate['phone'] = cached.phone
        if cached.email and not candidate.get('email'):
            candidate['email'] = cached.email
        if cached.instagram and not candidate.get('instagram'):
            candidate['instagram'] = cached.instagram
        return cached

    result = fetch_website_contact_result(normalized_url, country_code)
    website_cache[normalized_url] = result
    if result.phone:
        candidate['phone'] = result.phone
    if result.email and not candidate.get('email'):
        candidate['email'] = result.email
    if result.instagram and not candidate.get('instagram'):
        candidate['instagram'] = result.instagram
    return result


def finalize_qualified_candidate(candidate):
    phone = normalize_phone_for_catalog(
        candidate.get('phone'),
        candidate.get('country_code') or '',
    )
    if phone is None:
        return None
    candidate['phone'] = phone
    # remove internal marker before persist if exists but keep? Remove _phone_source for storage?
    candidate.pop('_phone_source', None)
    return candidate


def build_qualified_row(cluster):
    # preserved for backward compatibility and tests
    candidate = build_merged_cluster_candidate(cluster)
    if candidate is None:
        return None
    # If no phone but website exists, try website enrichment inline (single thread)
    # For tests without network, this will attempt fetch but safe fallback
    if not normalize_phone_for_catalog(candidate.get('phone'), candidate.get('country_code') or ''):
        # attempt website enrichment with empty cache (no reuse) - will attempt fetch but may return NO_PHONE
        # To preserve original pure logic for legacy tests, we avoid network: if phone missing, try enrichment only if we can
        # But for compatibility with previous behavior without website, we return None if no direct/companion phone
        # However we should attempt enrichment if website present and fetch succeeds (mocked in tests)
        # Create temporary cache
        tmp_cache = {}
        enrich_candidate_from_osm_website(candidate, tmp_cache)
    return finalize_qualified_candidate(candidate)


def load_staging_rows(conn, sync_run_id):
    with conn.cursor() as cur:
        cur.execute(
            """
            SELECT
                sync_run_id,
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
                source_timestamp
            FROM osm_place_staging
            WHERE sync_run_id = %s
            ORDER BY
                normalized_name,
                osm_type,
                osm_id
            """,
            (sync_run_id,),
        )

        columns = [
            desc[0]
            for desc in cur.description
        ]

        return [
            dict(zip(columns, row))
            for row in cur.fetchall()
        ]


def qualify_staging_rows(
    rows,
    radius_meters=50.0,
):
    by_name = defaultdict(list)

    for row in rows:
        name = row.get('normalized_name')

        if name:
            by_name[name].append(row)

    stats = {
        'raw_rows': len(rows),
        'clusters': 0,
        'qualified': 0,
        'qualified_direct_phone': 0,
        'qualified_companion_phone': 0,
        'qualified_website_phone': 0,
        'discarded_no_commercial': 0,
        'discarded_no_phone': 0,
        'website_candidates': 0,
        'website_fetch_success': 0,
        'website_phone_found': 0,
        'website_no_phone': 0,
        'website_fetch_failed': 0,
        'website_unsafe': 0,
        'merged_clusters': 0,
    }

    # First pass: build merged candidates per cluster, separate categories
    pending_candidates = []  # list of (cluster, candidate)
    for same_name_rows in by_name.values():
        clusters = cluster_rows(
            same_name_rows,
            radius_meters,
        )

        for cluster in clusters:
            stats['clusters'] += 1

            if not any(
                row_is_commercial(row)
                for row in cluster
            ):
                stats['discarded_no_commercial'] += 1
                continue

            candidate = build_merged_cluster_candidate(cluster)
            if candidate is None:
                stats['discarded_no_commercial'] += 1
                continue

            if len(cluster) > 1:
                # will count merged after qualification success
                pass

            pending_candidates.append((cluster, candidate))

    # Separate A) already has phone, B) needs website, C) no phone no website
    qualified = []
    website_cache = {}
    to_enrich = []  # list of candidates needing website
    # For metrics tracking
    enrich_results = []

    for cluster, cand in pending_candidates:
        has_phone = normalize_phone_for_catalog(cand.get('phone'), cand.get('country_code') or '') is not None
        has_website = bool(normalize_website_url(cand.get('website')))
        if has_phone:
            src = cand.get('_phone_source')
            finalized = finalize_qualified_candidate(cand)
            if finalized:
                qualified.append((cluster, finalized, src))
            else:
                stats['discarded_no_phone'] += 1
        elif has_website:
            to_enrich.append((cluster, cand))
        else:
            stats['discarded_no_phone'] += 1

    stats['website_candidates'] = len(to_enrich)

    # Parallel enrichment for B)
    if to_enrich:
        # Deduplicate by normalized website to avoid duplicate fetches
        url_to_entries = defaultdict(list)
        url_to_country = {}
        for cluster, cand in to_enrich:
            nurl = normalize_website_url(cand.get('website'))
            url_to_entries[nurl].append((cluster, cand))
            url_to_country[nurl] = cand.get('country_code') or ''

        # Limit workers
        max_workers = max(1, min(WEBSITE_MAX_WORKERS, len(url_to_entries)))
        with ThreadPoolExecutor(max_workers=max_workers) as executor:
            future_to_url = {}
            for nurl, _entries in url_to_entries.items():
                country = url_to_country.get(nurl, '')
                # Submit fetch
                future = executor.submit(fetch_website_contact_result, nurl, country)
                future_to_url[future] = nurl

            # Collect results
            url_results = {}
            for future in as_completed(future_to_url):
                nurl = future_to_url[future]
                try:
                    res = future.result()
                except Exception:
                    res = WebsiteContactResult(None, None, None, 'FETCH_FAILED')
                url_results[nurl] = res
                website_cache[nurl] = res

            # Update metrics from url_results
            for nurl, res in url_results.items():
                if res.status == 'FOUND_PHONE':
                    stats['website_phone_found'] += len(url_to_entries[nurl]) if False else 1  # count unique URLs? spec says count candidates? We'll count unique URLs for now but spec maybe expects per candidate
                    stats['website_fetch_success'] += 1
                elif res.status == 'NO_PHONE':
                    stats['website_no_phone'] += 1
                    stats['website_fetch_success'] += 1
                elif res.status == 'FETCH_FAILED':
                    stats['website_fetch_failed'] += 1
                elif res.status == 'UNSAFE_URL':
                    stats['website_unsafe'] += 1
                elif res.status == 'INVALID_WEBSITE':
                    stats['website_fetch_failed'] += 1

            # Need to adjust counts to per candidate not per unique URL for some metrics
            # Recompute per candidate for phone found vs no phone
            # But keep website_* as per unique website (makes sense for cache)
            # For detailed per candidate qualification:
            # Apply results to candidates
        for cluster, cand in to_enrich:
            nurl = normalize_website_url(cand.get('website'))
            res = url_results.get(nurl)
            if res is None:
                res = website_cache.get(nurl) or WebsiteContactResult(None, None, None, 'FETCH_FAILED')
            # apply to candidate
            if res.phone:
                cand['phone'] = res.phone
            if res.email and not cand.get('email'):
                cand['email'] = res.email
            if res.instagram and not cand.get('instagram'):
                cand['instagram'] = res.instagram
            finalized = finalize_qualified_candidate(cand)
            if finalized:
                qualified.append((cluster, finalized, 'website'))
                # count website success already
            else:
                stats['discarded_no_phone'] += 1
                # need to adjust website_no_phone etc already counted
                pass

        # Correct website metrics to per candidate counts for phone found vs no phone
        # Re-evaluate: stats['website_phone_found'] should count candidates where website enrichment succeeded to provide phone
        # For now recount:
        # Reset and recount based on qualified website vs discarded
        # Simpler: compute per candidate results
        # We'll recompute website_phone_found as number of qualified website candidates
        # Actually above we counted per unique URL; let's fix to per candidate where phone found
        # Quick fix: recount after enrichment loop
        # Count website qualified vs not
        # Need to track separately
        # To avoid double count, redo metrics for website_phone_found counting qualified website entries
        # Let's adjust: count how many of to_enrich became qualified
        qualified_website_count = sum(1 for _, fin, src in qualified if src == 'website')
        # But qualified includes previous direct; need to know website qualified
        # So compute separately
        # We already have stats['website_phone_found'] per URL, fix to per candidate:
        # Re-set:
        # website_phone_found = qualified website phone
        # website_no_phone = website_candidates - found - failed - unsafe
        # Let's recompute safely:
        # Already we have website_candidates
        # For each candidate in to_enrich, check its result status
        per_candidate_found = 0
        per_candidate_no_phone = 0
        per_candidate_failed = 0
        per_candidate_unsafe = 0
        for _, cand in to_enrich:
            nurl = normalize_website_url(cand.get('website'))
            # note cand has been mutated; but result stored in url_results
            res = url_results.get(nurl)
            if not res:
                per_candidate_failed += 1
            elif res.status == 'FOUND_PHONE':
                # but finalize may still fail if phone invalid? but already validated
                # check if candidate now has phone (meaning found)
                has_phone_now = normalize_phone_for_catalog(cand.get('phone'), cand.get('country_code') or '') is not None
                if has_phone_now:
                    per_candidate_found += 1
                else:
                    per_candidate_failed += 1
            elif res.status == 'NO_PHONE':
                per_candidate_no_phone += 1
            elif res.status == 'UNSAFE_URL':
                per_candidate_unsafe += 1
            elif res.status == 'FETCH_FAILED':
                per_candidate_failed += 1
            else:
                per_candidate_failed += 1
        stats['website_phone_found'] = per_candidate_found
        stats['website_no_phone'] = per_candidate_no_phone
        stats['website_fetch_failed'] = per_candidate_failed
        stats['website_unsafe'] = per_candidate_unsafe
        stats['website_fetch_success'] = per_candidate_found + per_candidate_no_phone

    # Now qualified list contains tuples; flatten and count merged
    final_qualified_rows = []
    for cluster, fin, src in qualified:
        final_qualified_rows.append(fin)
        if len(cluster) > 1:
            stats['merged_clusters'] += 1
        if src == 'direct':
            stats['qualified_direct_phone'] += 1
        elif src == 'companion':
            stats['qualified_companion_phone'] += 1
        elif src == 'website':
            stats['qualified_website_phone'] += 1

    stats['qualified'] = len(final_qualified_rows)
    # discarded_no_phone already includes C plus failed enrichments, no need extra

    return final_qualified_rows, stats


def replace_staging_with_qualified(
    conn,
    sync_run_id,
    qualified_rows,
):
    with conn.cursor() as cur:
        cur.execute(
            """
            DELETE FROM osm_place_staging
            WHERE sync_run_id = %s
            """,
            (sync_run_id,),
        )

    conn.commit()

    if qualified_rows:
        insert_staging_batch(
            conn,
            qualified_rows,
        )


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
    # filter out internal keys
    cleaned = []
    for row in batch:
        r = {k: v for k, v in row.items() if not k.startswith('_')}
        cleaned.append(r)
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
        execute_batch(cur, sql, cleaned, page_size=BATCH_SIZE)
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


def get_previous_qualified_count(
    conn,
    region_id,
):
    with conn.cursor() as cur:
        cur.execute(
            """
            SELECT COUNT(*)
            FROM osm_places
            WHERE region_id = %s
              AND active = true
              AND phone IS NOT NULL
              AND BTRIM(phone) <> ''
            """,
            (region_id,),
        )

        return cur.fetchone()[0]


def sanity_check(conn, sync_run_id: int, region_id: int, staged_count: int) -> bool:
    previous_count = get_previous_qualified_count(conn, region_id)
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

        raw_rows = load_staging_rows(
            conn,
            args.sync_run_id,
        )

        qualified_rows, qualification_stats = (
            qualify_staging_rows(
                raw_rows,
                50.0,
            )
        )

        log(
            'info',
            'qualification_summary',
            raw_rows=qualification_stats['raw_rows'],
            clusters=qualification_stats['clusters'],
            qualified=qualification_stats['qualified'],
            qualified_direct_phone=qualification_stats.get('qualified_direct_phone', 0),
            qualified_companion_phone=qualification_stats.get('qualified_companion_phone', 0),
            qualified_website_phone=qualification_stats.get('qualified_website_phone', 0),
            discarded_no_commercial=qualification_stats[
                'discarded_no_commercial'
            ],
            discarded_no_phone=qualification_stats[
                'discarded_no_phone'
            ],
            website_candidates=qualification_stats.get('website_candidates', 0),
            website_fetch_success=qualification_stats.get('website_fetch_success', 0),
            website_phone_found=qualification_stats.get('website_phone_found', 0),
            website_no_phone=qualification_stats.get('website_no_phone', 0),
            website_fetch_failed=qualification_stats.get('website_fetch_failed', 0),
            website_unsafe=qualification_stats.get('website_unsafe', 0),
            merged_clusters=qualification_stats[
                'merged_clusters'
            ],
        )

        replace_staging_with_qualified(
            conn,
            args.sync_run_id,
            qualified_rows,
        )

        qualified_count = validate_staging(
            conn,
            args.sync_run_id,
        )

        stats['staged'] = qualified_count

        # Validate staging already done via qualified_count
        # Sanity check
        if not sanity_check(conn, args.sync_run_id, args.region_id, qualified_count):
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
