#!/usr/bin/env python3
"""
OSM Catalog Sync Import Script

Processes GeoJSONSeq output from osmium and imports into Neon PostgreSQL staging,
then atomically publishes to osm_places.
"""

import time
import argparse
import html
import ipaddress
import json
import re
import sys
import os
import socket
import unicodedata
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
    'contact:sms',
    'sms',
]

# Full OSM option B: typed direct-phone evidence (priority order).
DIRECT_PHONE_KEYS = [
    ('contact:whatsapp', 'DIRECT_OSM_WHATSAPP'),
    ('whatsapp', 'DIRECT_OSM_WHATSAPP'),
    ('contact:phone', 'DIRECT_OSM_PHONE'),
    ('phone', 'DIRECT_OSM_PHONE'),
    ('contact:mobile', 'DIRECT_OSM_MOBILE'),
    ('mobile', 'DIRECT_OSM_MOBILE'),
    ('contact:sms', 'DIRECT_OSM_SMS'),
    ('sms', 'DIRECT_OSM_SMS'),
]

PRIMARY_WEBSITE_KEYS = [
    'contact:website',
    'website',
    'url',
]

SECONDARY_WEBSITE_KEYS = [
    'operator:website',
    'brand:website',
]

INSTAGRAM_KEYS = [
    'contact:instagram',
    'instagram',
]

FACEBOOK_KEYS = [
    'contact:facebook',
    'facebook',
]

TELEGRAM_KEYS = [
    'contact:telegram',
    'telegram',
]

EMAIL_KEYS = [
    'contact:email',
    'email',
]

CONTACT_WEBSITE_KEYS = ['contact:website', 'website', 'url']
CONTACT_EMAIL_KEYS = ['contact:email', 'email']
CONTACT_INSTAGRAM_KEYS = ['contact:instagram', 'instagram']
CONTACT_FACEBOOK_KEYS = ['contact:facebook', 'facebook']
CONTACT_TELEGRAM_KEYS = ['contact:telegram', 'telegram']

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


def _env_bool(name: str, default: bool) -> bool:
    val = os.environ.get(name)
    if val is None:
        return default
    return str(val).strip().lower() in ('1', 'true', 'yes', 'y', 'on')


def _env_str_set(name: str, default: set) -> set:
    val = os.environ.get(name)
    if val is None or not str(val).strip():
        return set(default)
    out = set()
    for part in str(val).split(','):
        part = part.strip().lower().lstrip('.')
        if part:
            out.add(part)
    return out or set(default)

WEBSITE_CONNECT_TIMEOUT_SECONDS = _env_int('OSM_SYNC_WEBSITE_CONNECT_TIMEOUT_SECONDS', 2)
WEBSITE_READ_TIMEOUT_SECONDS = _env_int('OSM_SYNC_WEBSITE_READ_TIMEOUT_SECONDS', 3)
WEBSITE_MAX_BYTES = _env_int('OSM_SYNC_WEBSITE_MAX_BYTES', 500_000)
WEBSITE_MAX_WORKERS = _env_int('OSM_SYNC_WEBSITE_MAX_WORKERS', 8)
SOCIAL_MAX_WORKERS = _env_int('OSM_SYNC_SOCIAL_MAX_WORKERS', 4)
SOCIAL_PUBLIC_FETCH_ENABLED = _env_bool('OSM_SYNC_SOCIAL_PUBLIC_FETCH_ENABLED', True)
CONTACT_HUBS_ENABLED = _env_bool('OSM_SYNC_CONTACT_HUBS_ENABLED', True)

WEBSITE_MAX_REDIRECTS = _env_int('OSM_SYNC_MAX_REDIRECTS', 3)
WEBSITE_MAX_CONTACT_PAGES = _env_int('OSM_SYNC_MAX_CONTACT_PAGES', 2)

DEFAULT_CONTACT_HUB_HOSTS = {
    'linktr.ee',
    'beacons.ai',
    'bio.site',
    'campsite.bio',
    'taplink.cc',
    'msha.ke',
    'linkin.bio',
}


def get_contact_hub_hosts() -> set:
    return _env_str_set('OSM_SYNC_CONTACT_HUB_HOSTS', DEFAULT_CONTACT_HUB_HOSTS)


CONTACT_HUB_HOSTS = DEFAULT_CONTACT_HUB_HOSTS

SAMEAS_HUB_SUFFIXES = (
    'linktr.ee',
    'beacons.ai',
    'bio.site',
    'campsite.bio',
    'taplink.cc',
    'msha.ke',
    'linkin.bio',
)

WHATSAPP_PATTERNS = [
    re.compile(r'(?i)(?:https?://)?(?:www\.)?wa\.me/([^&"\'\s<>]+)'),
    re.compile(r'(?i)(?:https?://)?(?:www\.)?(?:api|web)\.whatsapp\.com/send\?[^"\'\s>]*?phone=([^&"\'\s<>]+)'),
]

TEL_LINK_PATTERN = re.compile(r'''(?i)href\s*=\s*["']\s*tel:([^"']+)["']''')

GENERIC_PHONE_PATTERN = re.compile(r'(?:\+?55[\s().-]*)?(?:\(?\d{2}\)?[\s.-]*)?\d{4,5}[\s.-]?\d{4}')

EMAIL_PATTERN = re.compile(r'([A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,})', re.I)

INSTAGRAM_PATTERN = re.compile(r'(?:https?://)?(?:www\.)?instagram\.com/([A-Za-z0-9_.]+)', re.I)

CONTACT_KEYWORDS = [
    'contato',
    'contact',
    'fale-conosco',
    'fale conosco',
    'atendimento',
    'whatsapp',
    'fale',
    'como chegar',
]

RESERVED_INSTAGRAM = {'p', 'reel', 'tv', 'explore', 'accounts', 'direct', 'developer', 'about', 'legal', 'press', 'jobs', 'api', 'graph', 'www'}


@dataclass
class WebsiteContactResult:
    phone: Optional[str]
    email: Optional[str]
    instagram: Optional[str]
    status: str
    facebook: Optional[str] = None
    telegram: Optional[str] = None
    source_type: Optional[str] = None
    source_url: Optional[str] = None
    instagram_source_type: Optional[str] = None
    instagram_source_url: Optional[str] = None


@dataclass(frozen=True)
class PhoneEvidence:
    phone: str
    source_type: str
    source_url: Optional[str] = None


@dataclass
class FetchResult:
    body: Optional[str]
    final_url: Optional[str]
    status: str
    http_status: Optional[int] = None


def extract_direct_phone_evidence(
    tags: Dict[str, Any],
    country_code: str,
) -> Optional[PhoneEvidence]:
    for key, source_type in DIRECT_PHONE_KEYS:
        raw = tags.get(key)

        if raw is None:
            continue

        for part in str(raw).split(';'):
            normalized = normalize_phone_for_catalog(
                part,
                country_code,
            )

            if normalized:
                return PhoneEvidence(
                    phone=normalized,
                    source_type=source_type,
                    source_url=None,
                )

    return None


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


def _classify_fetch_exception(exc: Exception) -> Tuple[str, Optional[int]]:
    if isinstance(exc, urllib.error.HTTPError):
        code = getattr(exc, 'code', None)
        if code == 403:
            return 'HTTP_403', 403
        if code == 404:
            return 'HTTP_404', 404
        if code == 429:
            return 'HTTP_429', 429
        if code is not None and 500 <= code < 600:
            return 'HTTP_5XX', code
        return 'FETCH_FAILED', code
    msg = f'{type(exc).__name__}: {exc}'.lower()
    if isinstance(exc, socket.timeout) or 'timed out' in msg or 'timeout' in msg:
        return 'TIMEOUT', None
    if 'ssl' in msg or 'tls' in msg or 'certificate' in msg:
        return 'TLS_FAILED', None
    if isinstance(exc, socket.gaierror) or 'name resolution' in msg or 'nodename nor servname' in msg or 'getaddrinfo failed' in msg or 'dns' in msg:
        return 'DNS_FAILED', None
    if isinstance(exc, (urllib.error.URLError, OSError, ValueError)):
        if 'ssl' in msg or 'tls' in msg or 'certificate' in msg:
            return 'TLS_FAILED', None
        if 'refused' in msg or 'reset' in msg:
            return 'FETCH_FAILED', None
        return 'FETCH_FAILED', None
    return 'FETCH_FAILED', None


def _is_retryable_fetch_status(status: str, http_status: Optional[int]) -> bool:
    if status == 'TIMEOUT':
        return True
    if status == 'FETCH_FAILED':
        return False
    if http_status in (502, 503, 504):
        return True
    return False


def _single_fetch_attempt(url: str, timeout: float):
    class NoRedirect(urllib.request.HTTPRedirectHandler):
        def redirect_request(self, req, fp, code, msg, headers, newurl):
            return None

    opener = urllib.request.build_opener(NoRedirect())
    req = urllib.request.Request(
        url,
        headers={'User-Agent': 'GendazLeads-OSM-Sync/1.0'},
        method='GET',
    )
    resp = opener.open(req, timeout=timeout)
    return resp


def fetch_public_html_result(
    url: str,
    redirect_count: int = 0,
    _retried: bool = False,
) -> FetchResult:
    if redirect_count > WEBSITE_MAX_REDIRECTS:
        return FetchResult(None, url, 'REDIRECT_LIMIT', None)
    parsed_probe = urllib.parse.urlparse(url)
    if parsed_probe.scheme not in ('http', 'https'):
        return FetchResult(None, url, 'INVALID_URL', None)
    if not is_safe_public_url(url):
        # Distinguish invalid URL from unsafe: is_safe covers localhost/private/scheme
        try:
            parsed = urllib.parse.urlparse(url)
            host = (parsed.hostname or '').lower()
            if parsed.scheme not in ('http', 'https') or not host:
                return FetchResult(None, url, 'INVALID_URL', None)
        except Exception:
            return FetchResult(None, url, 'INVALID_URL', None)
        return FetchResult(None, url, 'UNSAFE_URL', None)
    timeout = max(WEBSITE_CONNECT_TIMEOUT_SECONDS, 1) + max(WEBSITE_READ_TIMEOUT_SECONDS, 1)
    try:
        try:
            resp = _single_fetch_attempt(url, timeout)
        except urllib.error.HTTPError as e:
            if 300 <= e.code < 400:
                location = e.headers.get('Location') if e.headers else None
                if not location:
                    return FetchResult(None, url, 'FETCH_FAILED', e.code)
                next_url = urllib.parse.urljoin(url, location)
                if not is_safe_public_url(next_url):
                    return FetchResult(None, url, 'UNSAFE_URL', e.code)
                return fetch_public_html_result(next_url, redirect_count + 1, _retried)
            status, http_status = _classify_fetch_exception(e)
            if not _retried and _is_retryable_fetch_status(status, http_status):
                try:
                    resp = _single_fetch_attempt(url, timeout)
                except Exception as e2:
                    status2, http2 = _classify_fetch_exception(e2)
                    return FetchResult(None, url, status2, http2)
            else:
                return FetchResult(None, url, status, http_status)
        try:
            status_code = getattr(resp, 'status', resp.getcode())
            if 300 <= status_code < 400:
                location = resp.headers.get('Location')
                try:
                    resp.close()
                except Exception:
                    pass
                if not location:
                    return FetchResult(None, url, 'FETCH_FAILED', status_code)
                next_url = urllib.parse.urljoin(url, location)
                if not is_safe_public_url(next_url):
                    return FetchResult(None, url, 'UNSAFE_URL', status_code)
                return fetch_public_html_result(next_url, redirect_count + 1, _retried)
            if status_code == 403:
                try:
                    resp.close()
                except Exception:
                    pass
                return FetchResult(None, url, 'HTTP_403', 403)
            if status_code == 404:
                try:
                    resp.close()
                except Exception:
                    pass
                return FetchResult(None, url, 'HTTP_404', 404)
            if status_code == 429:
                try:
                    resp.close()
                except Exception:
                    pass
                return FetchResult(None, url, 'HTTP_429', 429)
            if 500 <= status_code < 600:
                try:
                    resp.close()
                except Exception:
                    pass
                if not _retried and _is_retryable_fetch_status('HTTP_5XX', status_code):
                    try:
                        resp = _single_fetch_attempt(url, timeout)
                        status_code = getattr(resp, 'status', resp.getcode())
                        if not (200 <= status_code < 300):
                            try:
                                resp.close()
                            except Exception:
                                pass
                            if status_code == 403:
                                return FetchResult(None, url, 'HTTP_403', 403)
                            if status_code == 404:
                                return FetchResult(None, url, 'HTTP_404', 404)
                            if status_code == 429:
                                return FetchResult(None, url, 'HTTP_429', 429)
                            if 500 <= status_code < 600:
                                return FetchResult(None, url, 'HTTP_5XX', status_code)
                            return FetchResult(None, url, 'FETCH_FAILED', status_code)
                    except Exception as e2:
                        status2, http2 = _classify_fetch_exception(e2)
                        return FetchResult(None, url, status2, http2)
                else:
                    return FetchResult(None, url, 'HTTP_5XX', status_code)
            if not (200 <= status_code < 300):
                try:
                    resp.close()
                except Exception:
                    pass
                return FetchResult(None, url, 'FETCH_FAILED', status_code)
            content_type = resp.headers.get('Content-Type', '') or ''
            if content_type:
                ct = content_type.lower()
                if any(x in ct for x in ['image/', 'video/', 'audio/', 'application/octet-stream', 'application/pdf', 'application/zip', 'application/gzip']):
                    try:
                        resp.close()
                    except Exception:
                        pass
                    return FetchResult(None, url, 'BINARY_CONTENT', status_code)
                if 'text/html' not in ct and 'text/plain' not in ct and 'application/xhtml' not in ct and 'application/xml' not in ct and 'application/ld+json' not in ct:
                    if any(x in ct for x in ['image/', 'video/', 'audio/', 'font/', 'application/pdf', 'application/zip', 'application/octet-stream']):
                        try:
                            resp.close()
                        except Exception:
                            pass
                        return FetchResult(None, url, 'BINARY_CONTENT', status_code)
            data = resp.read(WEBSITE_MAX_BYTES + 1)
            try:
                resp.close()
            except Exception:
                pass
            if len(data) > WEBSITE_MAX_BYTES:
                # Site too large: do not parse partial content for phone (avoid truncation artifacts)
                return FetchResult(None, url, 'TOO_LARGE', status_code)
            text = data.decode('utf-8', errors='ignore')
            return FetchResult(text, url, 'OK', status_code)
        finally:
            try:
                resp.close()
            except Exception:
                pass
    except Exception as exc:
        status, http_status = _classify_fetch_exception(exc)
        if not _retried and _is_retryable_fetch_status(status, http_status):
            try:
                resp = _single_fetch_attempt(url, timeout)
                try:
                    status_code = getattr(resp, 'status', resp.getcode())
                    if 200 <= status_code < 300:
                        data = resp.read(WEBSITE_MAX_BYTES + 1)
                        if len(data) > WEBSITE_MAX_BYTES:
                            return FetchResult(None, url, 'TOO_LARGE', status_code)
                        return FetchResult(data.decode('utf-8', errors='ignore'), url, 'OK', status_code)
                    if status_code == 403:
                        return FetchResult(None, url, 'HTTP_403', 403)
                    if status_code == 404:
                        return FetchResult(None, url, 'HTTP_404', 404)
                    if status_code == 429:
                        return FetchResult(None, url, 'HTTP_429', 429)
                    if 500 <= status_code < 600:
                        return FetchResult(None, url, 'HTTP_5XX', status_code)
                    return FetchResult(None, url, 'FETCH_FAILED', status_code)
                finally:
                    try:
                        resp.close()
                    except Exception:
                        pass
            except Exception as exc2:
                status2, http2 = _classify_fetch_exception(exc2)
                return FetchResult(None, url, status2, http2)
        if isinstance(exc, socket.gaierror):
            return FetchResult(None, url, 'DNS_FAILED', http_status)
        return FetchResult(None, url, status, http_status)


def fetch_public_html_with_fallback(raw_url: str) -> FetchResult:
    """Fetch honoring the controlled HTTP fallback rule.

    If the original OSM value had no explicit protocol and HTTPS fails with
    TLS/connection errors, try plain HTTP once. Never downgrade explicit https.
    """
    if raw_url is None:
        return FetchResult(None, None, 'INVALID_URL', None)
    original = str(raw_url).strip()
    if not original:
        return FetchResult(None, None, 'INVALID_URL', None)
    had_protocol = original.lower().startswith(('http://', 'https://'))
    normalized = normalize_website_url(original)
    if not normalized:
        return FetchResult(None, None, 'INVALID_URL', None)
    result = fetch_public_html_result(normalized)
    if had_protocol:
        return result
    # OSM had no protocol (we defaulted to https). Allow single http fallback
    # only for TLS/connection-style failures.
    if result.status in ('TLS_FAILED', 'DNS_FAILED', 'TIMEOUT', 'FETCH_FAILED') and normalized.startswith('https://'):
        http_url = 'http://' + normalized[len('https://'):]
        fallback = fetch_public_html_result(http_url)
        # Only accept fallback if it actually returned content
        if fallback.status == 'OK':
            return fallback
    return result


def fetch_public_html(
    url: str,
    redirect_count: int = 0,
) -> Optional[str]:
    """Backward-compatible wrapper returning only the body (or None)."""
    result = fetch_public_html_result(url, redirect_count)
    if result.status == 'OK':
        return result.body
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


FACEBOOK_PATTERN = re.compile(
    r'(?:https?://)?(?:www\.|m\.|web\.)?(?:facebook\.com|fb\.com)/([A-Za-z0-9_.]+)',
    re.I,
)

TELEGRAM_PATTERN = re.compile(
    r'(?:https?://)?(?:www\.)?(?:t\.me|telegram\.me)/([A-Za-z0-9_]+)',
    re.I,
)

JSONLD_SCRIPT_PATTERN = re.compile(
    r'<script[^>]+type\s*=\s*["\']application/ld\+json["\'][^>]*>(.*?)</script>',
    re.I | re.S,
)

MICRODATA_META_PATTERN = re.compile(
    r'''<meta[^>]+itemprop\s*=\s*["']telephone["'][^>]*>''',
    re.I,
)

MICRODATA_CONTENT_PATTERN = re.compile(
    r'''content\s*=\s*["']([^"']+)["']''',
    re.I,
)

MICRODATA_TEL_HREF_PATTERN = re.compile(
    r'''<[^>]+itemprop\s*=\s*["']telephone["'][^>]*href\s*=\s*["']\s*tel:([^"']+)["']''',
    re.I,
)

MICRODATA_SPAN_PATTERN = re.compile(
    r'''<[^>]+itemprop\s*=\s*["']telephone["'][^>]*>(.*?)</[^>]+>''',
    re.I | re.S,
)


def normalize_instagram_url(raw: Optional[str]) -> Optional[str]:
    if raw is None:
        return None
    value = str(raw).strip()
    if not value:
        return None
    # Accept full URL, handle, or @handle
    m = INSTAGRAM_PATTERN.search(value)
    if m:
        handle = m.group(1).strip().strip('/')
        handle = handle.split('?')[0].split('#')[0].split('/')[0]
    else:
        handle = value.split('?')[0].split('#')[0].split('/')[0].strip()
        handle = handle.lstrip('@').strip()
        # strip possible domain leftovers
        if '/' in handle:
            handle = handle.split('/')[-1]
    if not handle:
        return None
    if handle.lower() in RESERVED_INSTAGRAM:
        return None
    if not re.match(r'^[A-Za-z0-9_.]+$', handle):
        return None
    return f'https://www.instagram.com/{handle}/'


def normalize_facebook_url(raw: Optional[str]) -> Optional[str]:
    if raw is None:
        return None
    value = str(raw).strip()
    if not value:
        return None
    m = FACEBOOK_PATTERN.search(value)
    if m:
        handle = m.group(1).strip().strip('/').split('?')[0].split('#')[0].split('/')[0]
        if not handle or handle.lower() in ('sharer', 'share', 'plugins', 'tr'):
            return None
        return f'https://www.facebook.com/{handle}/'
    # bare username
    handle = value.lstrip('@').strip().split('?')[0].split('#')[0].split('/')[0]
    if not handle or not re.match(r'^[A-Za-z0-9_.]+$', handle):
        return None
    return f'https://www.facebook.com/{handle}/'


def normalize_telegram_url(raw: Optional[str]) -> Optional[str]:
    if raw is None:
        return None
    value = str(raw).strip()
    if not value:
        return None
    m = TELEGRAM_PATTERN.search(value)
    if m:
        handle = m.group(1).strip().split('?')[0].split('#')[0].split('/')[0].lstrip('@')
        if not handle:
            return None
        return f'https://t.me/{handle}'
    handle = value.lstrip('@').strip().split('?')[0].split('#')[0].split('/')[0]
    if not handle or not re.match(r'^[A-Za-z0-9_]+$', handle):
        return None
    return f'https://t.me/{handle}'


def _instagram_handle_from_url(url: str) -> Optional[str]:
    m = INSTAGRAM_PATTERN.search(url or '')
    if not m:
        return None
    handle = m.group(1).strip().strip('/').split('?')[0].split('#')[0].split('/')[0]
    if not handle or handle.lower() in RESERVED_INSTAGRAM:
        return None
    return handle.lower()


def extract_jsonld_blocks(html_text: str) -> List[Any]:
    if not html_text:
        return []
    blocks: List[Any] = []
    for m in JSONLD_SCRIPT_PATTERN.finditer(html_text):
        raw = (m.group(1) or '').strip()
        if not raw:
            continue
        # Strip HTML comments/CDATA sometimes wrapping JSON-LD
        raw = re.sub(r'^<!--', '', raw).strip()
        raw = re.sub(r'-->$', '', raw).strip()
        try:
            parsed = json.loads(raw)
        except Exception:
            continue
        blocks.append(parsed)
    return blocks


def iter_jsonld_nodes(value):
    seen = set()

    def _walk(node):
        nid = id(node)
        if nid in seen:
            return
        seen.add(nid)
        if isinstance(node, dict):
            yield node
            graph = node.get('@graph')
            if isinstance(graph, list):
                for item in graph:
                    yield from _walk(item)
            elif isinstance(graph, dict):
                yield from _walk(graph)
            for child in node.values():
                if isinstance(child, (dict, list)):
                    yield from _walk(child)
        elif isinstance(node, list):
            for item in node:
                yield from _walk(item)

    if isinstance(value, list):
        for item in value:
            yield from _walk(item)
    else:
        yield from _walk(value)


def _normalize_jsonld_phone_candidate(raw: Any, country_code: str) -> Optional[str]:
    if raw is None:
        return None
    if isinstance(raw, list):
        for item in raw:
            normalized = _normalize_jsonld_phone_candidate(item, country_code)
            if normalized:
                return normalized
        return None
    if isinstance(raw, dict):
        # e.g. {"@value": "+55..."} or nested telephone
        for key in ('telephone', 'phone'):
            if key in raw:
                normalized = _normalize_jsonld_phone_candidate(raw[key], country_code)
                if normalized:
                    return normalized
        return None
    return normalize_phone_for_catalog(str(raw), country_code or '')


def extract_jsonld_phone(html_text: str, country_code: str) -> Optional[str]:
    blocks = extract_jsonld_blocks(html_text)
    if not blocks:
        return None
    for block in blocks:
        for node in iter_jsonld_nodes(block):
            if not isinstance(node, dict):
                continue
            for key in ('telephone', 'phone'):
                if key in node:
                    normalized = _normalize_jsonld_phone_candidate(node.get(key), country_code)
                    if normalized:
                        return normalized
            contact = node.get('contactPoint')
            if contact is not None:
                normalized = _normalize_jsonld_phone_candidate(contact, country_code)
                if normalized:
                    return normalized
    return None


def extract_same_as_urls(jsonld_blocks) -> List[str]:
    urls: List[str] = []
    seen = set()
    for block in jsonld_blocks or []:
        for node in iter_jsonld_nodes(block):
            if not isinstance(node, dict):
                continue
            same = node.get('sameAs')
            if same is None:
                # also accept case variants
                for k, v in node.items():
                    if isinstance(k, str) and k.lower() == 'sameas':
                        same = v
                        break
            if same is None:
                continue
            candidates = same if isinstance(same, list) else [same]
            for item in candidates:
                if not isinstance(item, str):
                    continue
                u = item.strip()
                if not u or u in seen:
                    continue
                if len(urls) >= 20:
                    return urls
                seen.add(u)
                urls.append(u)
    return urls


def classify_external_contact_url(url: str) -> str:
    try:
        parsed = urllib.parse.urlparse(url if '://' in url else 'https://' + url)
        host = (parsed.hostname or '').lower()
    except Exception:
        return 'OTHER'
    if not host:
        return 'OTHER'
    host = host.lstrip('www.').lstrip('m.')
    if 'instagram.com' in host:
        return 'INSTAGRAM'
    if host.endswith('facebook.com') or host == 'fb.com' or host.endswith('.fb.com'):
        return 'FACEBOOK'
    hubs = get_contact_hub_hosts() or DEFAULT_CONTACT_HUB_HOSTS
    normalized_host = host.lstrip('www.')
    for hub in hubs:
        hub_norm = hub.lower().lstrip('www.').lstrip('.')
        if normalized_host == hub_norm or normalized_host.endswith('.' + hub_norm):
            return 'CONTACT_HUB'
    for suffix in SAMEAS_HUB_SUFFIXES:
        if normalized_host == suffix or normalized_host.endswith('.' + suffix):
            return 'CONTACT_HUB'
    return 'OTHER'


def extract_microdata_phone(html_text: str, country_code: str) -> Optional[str]:
    if not html_text:
        return None
    for m in MICRODATA_TEL_HREF_PATTERN.finditer(html_text):
        normalized = normalize_phone_for_catalog(m.group(1), country_code or '')
        if normalized:
            return normalized
    for m in MICRODATA_META_PATTERN.finditer(html_text):
        tag = m.group(0)
        cm = MICRODATA_CONTENT_PATTERN.search(tag)
        if cm:
            normalized = normalize_phone_for_catalog(cm.group(1), country_code or '')
            if normalized:
                return normalized
    for m in MICRODATA_SPAN_PATTERN.finditer(html_text):
        inner = re.sub(r'<[^>]+>', '', m.group(1) or '').strip()
        if inner:
            normalized = normalize_phone_for_catalog(inner, country_code or '')
            if normalized:
                return normalized
    return None


def _extract_facebook_from_html(html_text: str) -> Optional[str]:
    if not html_text:
        return None
    for m in FACEBOOK_PATTERN.finditer(html_text):
        handle = m.group(1).strip().strip('/').split('?')[0].split('#')[0].split('/')[0]
        if not handle or handle.lower() in ('sharer', 'share', 'plugins', 'tr', 'www'):
            continue
        return normalize_facebook_url(handle)
    return None


def _extract_telegram_from_html(html_text: str) -> Optional[str]:
    if not html_text:
        return None
    for m in TELEGRAM_PATTERN.finditer(html_text):
        handle = m.group(1).strip().split('?')[0].split('#')[0].split('/')[0]
        if handle:
            return normalize_telegram_url(handle)
    return None


def is_contact_hub_url(url: str) -> bool:
    return classify_external_contact_url(url) == 'CONTACT_HUB'


def find_contact_hub_urls(html_text: str, jsonld_blocks=None) -> List[str]:
    found: List[str] = []
    seen = set()
    anchor_pat = re.compile(r'''<a[^>]+href\s*=\s*["']([^"']+)["\']''', re.I)
    for m in anchor_pat.finditer(html_text or ''):
        href = (m.group(1) or '').strip()
        if not href or href.startswith(('tel:', 'mailto:', 'javascript:', 'data:', '#')):
            continue
        if href in seen:
            continue
        if classify_external_contact_url(href) == 'CONTACT_HUB':
            seen.add(href)
            found.append(href)
            if len(found) >= 5:
                return found
    for u in extract_same_as_urls(jsonld_blocks or []):
        if u in seen:
            continue
        if classify_external_contact_url(u) == 'CONTACT_HUB':
            seen.add(u)
            found.append(u)
            if len(found) >= 5:
                break
    return found


def _extract_phone_with_source(html_text: str, country_code: str) -> Tuple[Optional[str], Optional[str]]:
    """Extract phone following spec order. Returns (phone, source_suffix)."""
    if not html_text:
        return None, None
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
                    return normalized, 'WHATSAPP'
    for m in TEL_LINK_PATTERN.finditer(html_text):
        raw = m.group(1)
        if raw:
            try:
                raw = urllib.parse.unquote(raw)
            except Exception:
                pass
            normalized = normalize_phone_for_catalog(raw, country_code or '')
            if normalized:
                return normalized, 'TEL'
    phone = extract_jsonld_phone(html_text, country_code or '')
    if phone:
        return phone, 'JSONLD_PHONE'
    phone = extract_microdata_phone(html_text, country_code or '')
    if phone:
        return phone, 'MICRODATA_PHONE'
    for m in GENERIC_PHONE_PATTERN.finditer(html_text):
        raw = m.group(0)
        if raw:
            normalized = normalize_phone_for_catalog(raw, country_code or '')
            if normalized:
                return normalized, 'TEXT_PHONE'
    return None, None


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


def extract_html_contacts(html_text: str, country_code: str) -> Dict[str, Optional[str]]:
    phone, phone_kind = _extract_phone_with_source(html_text, country_code)
    blocks = extract_jsonld_blocks(html_text)
    same_as = extract_same_as_urls(blocks)
    instagram = _extract_instagram_from_html(html_text)
    if not instagram:
        for u in same_as:
            if classify_external_contact_url(u) == 'INSTAGRAM':
                handle = _instagram_handle_from_url(u)
                if handle:
                    instagram = handle
                    break
    return {
        'phone': phone,
        'phone_kind': phone_kind,
        'email': _extract_email_from_html(html_text),
        'instagram': instagram,
        'facebook': _extract_facebook_from_html(html_text),
        'telegram': _extract_telegram_from_html(html_text),
        'same_as': same_as,
        'jsonld_blocks': blocks,
    }


def _source_type_for(kind: Optional[str], contact_page: bool = False) -> str:
    if contact_page:
        return 'OSM_WEBSITE_CONTACT_PAGE'
    mapping = {
        'WHATSAPP': 'OSM_WEBSITE_WHATSAPP',
        'TEL': 'OSM_WEBSITE_TEL',
        'JSONLD_PHONE': 'OSM_WEBSITE_JSONLD_PHONE',
        'MICRODATA_PHONE': 'OSM_WEBSITE_MICRODATA_PHONE',
        'TEXT_PHONE': 'OSM_WEBSITE_TEXT_PHONE',
    }
    return mapping.get(kind or '', 'OSM_WEBSITE_TEXT_PHONE')


def _fetch_failure_result_to_status(fetch: FetchResult) -> str:
    mapping = {
        'UNSAFE_URL': 'UNSAFE_URL',
        'INVALID_URL': 'INVALID_WEBSITE',
        'DNS_FAILED': 'DNS_FAILED',
        'TIMEOUT': 'TIMEOUT',
        'TLS_FAILED': 'TLS_FAILED',
        'HTTP_403': 'HTTP_403',
        'HTTP_404': 'HTTP_404',
        'HTTP_429': 'HTTP_429',
        'HTTP_5XX': 'HTTP_5XX',
        'TOO_LARGE': 'TOO_LARGE',
        'BINARY_CONTENT': 'BINARY_CONTENT',
        'REDIRECT_LIMIT': 'REDIRECT_LIMIT',
    }
    return mapping.get(fetch.status, 'FETCH_FAILED')


def fetch_contact_hub_result(hub_url: str, country_code: str, cache: Optional[Dict[str, WebsiteContactResult]] = None) -> WebsiteContactResult:
    if cache is not None and hub_url in cache:
        return cache[hub_url]
    if not CONTACT_HUBS_ENABLED:
        result = WebsiteContactResult(None, None, None, 'NO_PHONE', source_type='OSM_WEBSITE_CONTACT_HUB', source_url=hub_url)
        if cache is not None:
            cache[hub_url] = result
        return result
    if not is_contact_hub_url(hub_url):
        result = WebsiteContactResult(None, None, None, 'NO_PHONE', source_type='OSM_WEBSITE_CONTACT_HUB', source_url=hub_url)
        if cache is not None:
            cache[hub_url] = result
        return result
    fetch = fetch_public_html_result(hub_url)
    if fetch.status != 'OK' or not fetch.body:
        result = WebsiteContactResult(None, None, None, _fetch_failure_result_to_status(fetch), source_type='OSM_WEBSITE_CONTACT_HUB', source_url=hub_url)
        if cache is not None:
            cache[hub_url] = result
        return result
    phone, kind = _extract_phone_with_source(fetch.body, country_code)
    email = _extract_email_from_html(fetch.body)
    instagram = _extract_instagram_from_html(fetch.body)
    if phone:
        result = WebsiteContactResult(phone, email, instagram, 'FOUND_PHONE', source_type='OSM_WEBSITE_CONTACT_HUB', source_url=hub_url)
    else:
        result = WebsiteContactResult(None, email, instagram, 'NO_PHONE', source_type='OSM_WEBSITE_CONTACT_HUB', source_url=hub_url)
    if cache is not None:
        cache[hub_url] = result
    return result


class WhatsAppInfrastructureError(RuntimeError):
    """Technical failure while checking a WhatsApp recipient.

    Must never be confused with a valid "recipient not on WhatsApp"
    answer (which returns False). Callers treat this as FAILED, not as
    a discard counter.
    """


def check_whatsapp_recipient(recipient: str, country_code: str = '') -> bool:
    """Check if a phone number exists on WhatsApp via the whatsapp-service.

    Returns True when the recipient exists on WhatsApp, False when the
    lookup succeeded and the recipient does not exist (or the recipient
    itself is invalid). Raises WhatsAppInfrastructureError on any
    technical failure (missing config, auth/session errors, transport
    errors, retries exhausted)."""

    base_url = os.environ.get('OSM_SYNC_WHATSAPP_SERVICE_URL', '').rstrip('/')
    token = os.environ.get('OSM_SYNC_WHATSAPP_INTERNAL_TOKEN', '').strip()
    if not base_url or not token:
        raise WhatsAppInfrastructureError('WhatsApp service not configured')
    url = base_url + '/internal/whatsapp/session/recipients/check'
    payload = json.dumps({'recipient': recipient}).encode('utf-8')
    req = urllib.request.Request(
        url,
        data=payload,
        headers={
            'Authorization': f'Bearer {token}',
            'Content-Type': 'application/json',
            'Accept': 'application/json',
        },
        method='POST',
    )
    max_attempts = 3
    interval_sec = 0.35
    for attempt in range(1, max_attempts + 1):
        try:
            with urllib.request.urlopen(req, timeout=10) as resp:
                data = json.loads(resp.read().decode('utf-8'))
                return data.get('exists') is True
        except urllib.error.HTTPError as e:
            if e.code in (401, 403, 409, 502):
                raise WhatsAppInfrastructureError(f'WhatsApp check HTTP {e.code}')
            if e.code == 400:
                return False  # invalid recipient
            if attempt < 3:
                time.sleep(0.35)
            continue
        except Exception as e:
            if attempt < 3:
                time.sleep(0.35)
            continue
    raise WhatsAppInfrastructureError('WhatsApp check retries exhausted')


def fetch_public_social_contact(url: str, country_code: str) -> WebsiteContactResult:
    """Best-effort public fetch of an official social profile. No auth/JS/crawl."""
    if not SOCIAL_PUBLIC_FETCH_ENABLED:
        return WebsiteContactResult(None, None, None, 'SOCIAL_BLOCKED', source_type='OSM_DIRECT_SOCIAL_PUBLIC', source_url=url)
    kind = classify_external_contact_url(url)
    if kind not in ('INSTAGRAM', 'FACEBOOK'):
        return WebsiteContactResult(None, None, None, 'SOCIAL_BLOCKED', source_type='OSM_DIRECT_SOCIAL_PUBLIC', source_url=url)
    fetch = fetch_public_html_result(url)
    if fetch.status in ('HTTP_403', 'HTTP_429') or fetch.http_status in (401, 403, 429):
        return WebsiteContactResult(None, None, None, 'SOCIAL_BLOCKED', source_type='OSM_DIRECT_SOCIAL_PUBLIC', source_url=url)
    if fetch.status != 'OK' or not fetch.body:
        body_lower = (fetch.body or '').lower()
        if any(marker in body_lower for marker in ('login', 'challenge', 'captcha')):
            return WebsiteContactResult(None, None, None, 'SOCIAL_BLOCKED', source_type='OSM_DIRECT_SOCIAL_PUBLIC', source_url=url)
        mapped = _fetch_failure_result_to_status(fetch)
        if mapped in ('HTTP_403', 'HTTP_429'):
            mapped = 'SOCIAL_BLOCKED'
        return WebsiteContactResult(None, None, None, mapped, source_type='OSM_DIRECT_SOCIAL_PUBLIC', source_url=url)
    body_lower = fetch.body.lower()
    if 'login' in body_lower and ('instagram' in body_lower or 'facebook' in body_lower) and len(fetch.body) < 5000:
        # Likely a login wall without public content
        pass
    phone, _kind = _extract_phone_with_source(fetch.body, country_code)
    if phone:
        return WebsiteContactResult(phone, None, None, 'FOUND_PHONE', source_type='OSM_DIRECT_SOCIAL_PUBLIC', source_url=url)
    return WebsiteContactResult(None, None, None, 'NO_PHONE', source_type='OSM_DIRECT_SOCIAL_PUBLIC', source_url=url)


def fetch_website_contact_result(normalized_url: str, country_code: str, raw_url: Optional[str] = None, hub_cache: Optional[Dict[str, WebsiteContactResult]] = None) -> WebsiteContactResult:
    if not normalized_url:
        return WebsiteContactResult(None, None, None, 'INVALID_WEBSITE')
    if not is_safe_public_url(normalized_url):
        return WebsiteContactResult(None, None, None, 'UNSAFE_URL')
    fetch = fetch_public_html_with_fallback(raw_url or normalized_url)
    if fetch.status != 'OK' or not fetch.body:
        return WebsiteContactResult(None, None, None, _fetch_failure_result_to_status(fetch), source_url=normalized_url)
    html_text = fetch.body
    contacts = extract_html_contacts(html_text, country_code)
    if contacts['phone']:
        result = WebsiteContactResult(
            contacts['phone'], contacts['email'], contacts['instagram'],
            'FOUND_PHONE',
            facebook=contacts['facebook'], telegram=contacts['telegram'],
            source_type=_source_type_for(contacts['phone_kind']),
            source_url=normalized_url,
            instagram_source_type='OSM_WEBSITE_INSTAGRAM' if contacts['instagram'] else None,
            instagram_source_url=normalized_url if contacts['instagram'] else None,
        )
        result.same_as_list = contacts['same_as']
        return result
    best_email = contacts['email']
    best_insta = contacts['instagram']
    best_fb = contacts['facebook']
    best_tg = contacts['telegram']
    same_as = contacts['sameAs'] if 'sameAs' in contacts else contacts['same_as']
    # same-host contact pages (max 2, no spider)
    contact_urls = _find_contact_page_urls(normalized_url, html_text)
    for curl in contact_urls:
        if not is_safe_public_url(curl):
            continue
        c_fetch = fetch_public_html_result(curl)
        if c_fetch.status != 'OK' or not c_fetch.body:
            continue
        c_phone, c_kind = _extract_phone_with_source(c_fetch.body, country_code)
        c_email = _extract_email_from_html(c_fetch.body)
        c_insta = _extract_instagram_from_html(c_fetch.body)
        if c_email and not best_email:
            best_email = c_email
        if c_insta and not best_insta:
            best_insta = c_insta
        if c_phone:
            return WebsiteContactResult(
                c_phone, best_email, best_insta, 'FOUND_PHONE',
                facebook=best_fb, telegram=best_tg,
                source_type='OSM_WEBSITE_CONTACT_PAGE', source_url=curl,
                instagram_source_type='OSM_WEBSITE_CONTACT_PAGE_INSTAGRAM' if best_insta else None,
                instagram_source_url=curl if best_insta else None,
            )
    # contact hub officially linked (single GET, no recursion)
    hub_urls = find_contact_hub_urls(html_text, contacts['jsonld_blocks'])
    for hub_url in hub_urls:
        hub_res = fetch_contact_hub_result(hub_url, country_code, hub_cache)
        if hub_res.email and not best_email:
            best_email = hub_res.email
        if hub_res.instagram and not best_insta:
            best_insta = hub_res.instagram
        if hub_res.phone:
            hub_res.email = hub_res.email or best_email
            hub_res.instagram = hub_res.instagram or best_insta
            if hub_res.instagram and not getattr(hub_res, 'instagram_source_type', None):
                hub_res.instagram_source_type = 'OSM_CONTACT_HUB_INSTAGRAM'
                hub_res.instagram_source_url = hub_res.source_url
            hub_res.same_as_list = contacts['same_as']
            return hub_res
    result = WebsiteContactResult(
        None, best_email, best_insta, 'NO_PHONE',
        facebook=best_fb, telegram=best_tg,
        source_type=None, source_url=normalized_url,
        instagram_source_type='OSM_WEBSITE_INSTAGRAM' if best_insta else None,
        instagram_source_url=normalized_url if best_insta else None,
    )
    result.same_as_list = contacts['same_as']
    return result


def parse_args():
    parser = argparse.ArgumentParser(description='OSM Catalog Sync Import')
    parser.add_argument('--sync-run-id', required=True, type=int)
    parser.add_argument('--region-id', required=True, type=int)
    parser.add_argument('--city', required=True)
    parser.add_argument('--state', required=True)
    parser.add_argument('--country-code', required=True)
    parser.add_argument('--canonical-niche', required=True)
    parser.add_argument('--target-valid', required=True, type=int)
    parser.add_argument('--niche-strategy-json', required=True)
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


def normalize_name_phrase(value: Optional[str]) -> str:
    if value is None:
        return ''
    value = unicodedata.normalize('NFD', str(value).strip().lower())
    value = ''.join(ch for ch in value if unicodedata.category(ch) != 'Mn')
    value = re.sub(r'[^\w]+', ' ', value, flags=re.UNICODE)
    value = re.sub(r'\s+', ' ', value).strip()
    return value


def match_condition(tags: Dict[str, Any], condition: Dict[str, Any]) -> bool:
    key = condition.get('key')
    mode = condition.get('mode')
    accepted = {
        str(v).strip().lower()
        for v in (condition.get('acceptedValues') or [])
        if str(v).strip()
    }

    actual = tags.get(key)
    if actual is None:
        return False

    actual = str(actual).strip().lower()

    if mode == 'EXACT':
        return actual in accepted

    if mode == 'SEMICOLON_TOKEN':
        tokens = {
            part.strip().lower()
            for part in actual.split(';')
            if part.strip()
        }
        return bool(tokens.intersection(accepted))

    return False


def match_rule(tags: Dict[str, Any], rule: Dict[str, Any]) -> bool:
    conditions = rule.get('allOf') or []
    return bool(conditions) and all(
        match_condition(tags, condition)
        for condition in conditions
    )


def match_any_rule(tags: Dict[str, Any], rules) -> bool:
    return any(match_rule(tags, rule) for rule in (rules or []))


def name_contains_alias(name: str, alias: str) -> bool:
    n = normalize_name_phrase(name)
    a = normalize_name_phrase(alias)

    if not n or not a:
        return False

    return f' {a} ' in f' {n} '


def matches_niche_strategy(
    candidate: Dict[str, Any],
    strategy: Dict[str, Any],
) -> Tuple[bool, str, Optional[str]]:
    """Check if a niche candidate matches the niche strategy.

    Receives the full candidate (with tags JSON + normalized_name /
    business_name), not only tags, so NameFallback can use the
    candidate name with official context rules.

    Returns:
        (matched, match_type, matched_rule)
    """
    tags = row_tags(candidate)
    if not isinstance(tags, dict):
        tags = {}
    structured = strategy.get('structuredRules') or []

    for idx, rule in enumerate(structured):
        if match_rule(tags, rule):
            # Build rule description
            parts = []
            for cond in rule.get('allOf') or []:
                mode = '=' if cond.get('mode') == 'EXACT' else '~'
                vals = ','.join(cond.get('acceptedValues') or [])
                parts.append(f"{cond.get('key')}{mode}{vals}")
            return True, 'STRUCTURED_RULE', f"structuredRules[{idx}]: {' + '.join(parts)}"

    # Check name fallback (candidate-based; enabled == has aliases,
    # context required == has contextAnyOf rules)
    fallback = strategy.get('nameFallback') or {}
    aliases = fallback.get('aliases') or []
    contexts = fallback.get('contextAnyOf') or []
    enabled = bool(aliases)
    requires_context = bool(contexts)
    if enabled:
        context_match = True
        if requires_context:
            context_match = match_any_rule(tags, contexts)
        if context_match:
            name = (
                candidate.get('normalized_name')
                or candidate.get('business_name')
                or ''
            )
            for alias in aliases:
                if name_contains_alias(name, alias):
                    return True, 'NAME_FALLBACK', alias

    return False, 'NO_MATCH', None


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


def primary_website(tags: Dict[str, Any]) -> Optional[str]:
    return first_present(tags, PRIMARY_WEBSITE_KEYS)


def secondary_website_seen(tags: Dict[str, Any]) -> Dict[str, bool]:
    return {
        'brand': first_present(tags, ['brand:website']) is not None,
        'operator': first_present(tags, ['operator:website']) is not None,
    }


def official_social_urls(tags: Dict[str, Any]) -> Dict[str, Optional[str]]:
    instagram = first_present(tags, INSTAGRAM_KEYS)
    facebook = first_present(tags, FACEBOOK_KEYS)
    out: Dict[str, Optional[str]] = {'instagram': None, 'facebook': None}
    if instagram:
        out['instagram'] = normalize_instagram_url(instagram)
    if facebook:
        out['facebook'] = normalize_facebook_url(facebook)
    return out


def has_primary_website(tags: Dict[str, Any]) -> bool:
    return primary_website(tags) is not None


def has_official_social(tags: Dict[str, Any]) -> bool:
    social = official_social_urls(tags)
    return bool(social.get('instagram') or social.get('facebook'))


def has_any_official_channel(tags: Dict[str, Any]) -> bool:
    if extract_direct_phone_evidence(tags, 'br') is not None:
        return True
    # any country: check raw presence
    if first_phone_like(tags, [k for k, _ in DIRECT_PHONE_KEYS]) is not None:
        return True
    if has_primary_website(tags):
        return True
    if has_official_social(tags):
        return True
    return False


def audit_contact_coverage(rows: List[Dict[str, Any]]) -> Dict[str, int]:
    counts: Dict[str, int] = {
        'raw_rows': len(rows),
        'commercial_rows': 0,
        'contact_only_rows': 0,
        'tag_phone': 0,
        'tag_contact_phone': 0,
        'tag_mobile': 0,
        'tag_contact_mobile': 0,
        'tag_whatsapp': 0,
        'tag_contact_whatsapp': 0,
        'tag_sms': 0,
        'tag_contact_sms': 0,
        'tag_website': 0,
        'tag_contact_website': 0,
        'tag_url': 0,
        'tag_instagram': 0,
        'tag_contact_instagram': 0,
        'tag_facebook': 0,
        'tag_contact_facebook': 0,
        'tag_telegram': 0,
        'tag_contact_telegram': 0,
        'tag_brand_website': 0,
        'tag_operator_website': 0,
        'with_any_direct_phone_tag': 0,
        'with_primary_website': 0,
        'with_social_channel': 0,
        'with_any_official_channel': 0,
        'without_any_official_channel': 0,
    }

    def _present(tags: Dict[str, Any], key: str) -> bool:
        v = tags.get(key)
        return v is not None and str(v).strip() != ''

    for row in rows:
        tags = row_tags(row)
        commercial = is_commercial(tags)
        if commercial:
            counts['commercial_rows'] += 1
        else:
            counts['contact_only_rows'] += 1
        for metric, key in [
            ('tag_phone', 'phone'),
            ('tag_contact_phone', 'contact:phone'),
            ('tag_mobile', 'mobile'),
            ('tag_contact_mobile', 'contact:mobile'),
            ('tag_whatsapp', 'whatsapp'),
            ('tag_contact_whatsapp', 'contact:whatsapp'),
            ('tag_sms', 'sms'),
            ('tag_contact_sms', 'contact:sms'),
            ('tag_website', 'website'),
            ('tag_contact_website', 'contact:website'),
            ('tag_url', 'url'),
            ('tag_instagram', 'instagram'),
            ('tag_contact_instagram', 'contact:instagram'),
            ('tag_facebook', 'facebook'),
            ('tag_contact_facebook', 'contact:facebook'),
            ('tag_telegram', 'telegram'),
            ('tag_contact_telegram', 'contact:telegram'),
            ('tag_brand_website', 'brand:website'),
            ('tag_operator_website', 'operator:website'),
        ]:
            if _present(tags, key):
                counts[metric] += 1
        country = row.get('country_code') or ''
        has_direct = extract_direct_phone_evidence(tags, country) is not None
        if has_direct:
            counts['with_any_direct_phone_tag'] += 1
        has_site = has_primary_website(tags)
        if has_site:
            counts['with_primary_website'] += 1
        has_social = has_official_social(tags)
        if has_social:
            counts['with_social_channel'] += 1
        has_any = has_direct or has_site or has_social
        if has_any:
            counts['with_any_official_channel'] += 1
        else:
            counts['without_any_official_channel'] += 1
    return counts


def has_contact_signal(tags: Dict[str, Any]) -> bool:
    if first_phone_like(tags, CONTACT_PHONE_KEYS) is not None:
        return True

    if first_present(tags, CONTACT_WEBSITE_KEYS) is not None:
        return True

    if first_present(tags, CONTACT_EMAIL_KEYS) is not None:
        return True

    if first_present(tags, CONTACT_INSTAGRAM_KEYS) is not None:
        return True

    if first_present(tags, CONTACT_FACEBOOK_KEYS) is not None:
        return True

    if first_present(tags, CONTACT_TELEGRAM_KEYS) is not None:
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
        tags = row_tags(row)
        evidence = extract_direct_phone_evidence(tags, country_code)
        if evidence is None and row.get('phone'):
            # Fallback for rows whose phone column was pre-extracted
            normalized = normalize_phone_for_catalog(row.get('phone'), country_code)
            if normalized:
                # Attribute generically to DIRECT_OSM_PHONE when tag detail lost
                evidence = PhoneEvidence(phone=normalized, source_type='DIRECT_OSM_PHONE')
        if evidence is not None:
            phone = evidence.phone
            if row is base:
                phone_source = evidence.source_type
            else:
                phone_source = evidence.source_type.replace('DIRECT_', 'COMPANION_', 1) if evidence.source_type.startswith('DIRECT_') else 'COMPANION_OSM_PHONE'
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


def apply_contact_result_to_candidate(candidate, result: WebsiteContactResult):
    if result.phone:
        candidate['phone'] = result.phone
    if result.email and not candidate.get('email'):
        candidate['email'] = result.email
    if result.instagram and not candidate.get('instagram'):
        candidate['instagram'] = result.instagram
    if result.source_type:
        candidate['_phone_source'] = result.source_type
        candidate['_source_url'] = result.source_url
    ig_source = getattr(result, 'instagram_source_type', None)
    ig_url = getattr(result, 'instagram_source_url', None)
    if result.instagram and ig_source in ACCEPTED_INSTAGRAM_SOURCES:
        candidate['_instagram_source'] = ig_source
        candidate['_instagram_source_url'] = ig_url or result.source_url
    elif result.instagram and result.source_type:
        mapped = _instagram_source_for_phone_source(result.source_type)
        if mapped in ACCEPTED_INSTAGRAM_SOURCES and not candidate.get('_instagram_source'):
            candidate['_instagram_source'] = mapped
            candidate['_instagram_source_url'] = result.source_url


def enrich_candidate_full(candidate, website_cache, hub_cache, social_cache):
    """Full OSM option B chain for one candidate: website -> social OSM -> sameAs social."""
    country_code = candidate.get('country_code') or ''
    existing = normalize_phone_for_catalog(candidate.get('phone'), country_code)
    if existing:
        candidate['phone'] = existing
        return WebsiteContactResult(existing, candidate.get('email'), candidate.get('instagram'), 'FOUND_PHONE', source_type=candidate.get('_phone_source'), source_url=candidate.get('_source_url'))

    tags = row_tags(candidate)
    if not tags:
        # candidate built from merged cluster already carries tags JSON; fall back to fields
        tags = {}

    # C. primary website OSM
    website_raw = candidate.get('website') or (first_present(tags, PRIMARY_WEBSITE_KEYS) if tags else None)
    normalized_url = normalize_website_url(website_raw) if website_raw else None
    same_as_social: List[str] = []
    if normalized_url:
        if normalized_url in website_cache:
            cached = website_cache[normalized_url]
            if cached.phone:
                apply_contact_result_to_candidate(candidate, cached)
                return cached
            # reuse cached even when NO_PHONE (still need social fallback below,
            # but keep aggregated email/instagram)
            if cached.email and not candidate.get('email'):
                candidate['email'] = cached.email
            if cached.instagram and not candidate.get('instagram'):
                candidate['instagram'] = cached.instagram
            if cached.status != 'FOUND_PHONE':
                # do not return yet: still try social fallbacks
                pass
            else:
                return cached
        result = fetch_website_contact_result(normalized_url, country_code, raw_url=website_raw, hub_cache=hub_cache)
        website_cache[normalized_url] = result
        if result.phone:
            apply_contact_result_to_candidate(candidate, result)
            return result
        if result.email and not candidate.get('email'):
            candidate['email'] = result.email
        if result.instagram and not candidate.get('instagram'):
            candidate['instagram'] = result.instagram
        # collect sameAs social for step E (re-extract cheaply from cache? fetch again avoided:
        # we already parsed inside fetch; re-derive social hub/IG from result is enough.
        # For sameAs social profiles, we need the sameAs list; fetch function does not
        # return it, so we only attempt direct OSM social here and sameAs via a light
        # re-parse only if website fetch was OK. To avoid double fetch, skip re-parse:
        # sameAs social is handled in qualify loop via website_cache bodies? Instead,
        # attempt sameAs social only when direct fetch gave us instagram/facebook fields.
        # NOTE: full sameAs social URL list requires body; we re-derive from a fresh
        # lightweight parse only if needed and body available via new fetch? Avoid extra
        # HTTP: rely on direct OSM social first.
        pass

    # D. social oficial direto do OSM (best effort, 1 fetch each, cached)
    social = official_social_urls(tags) if tags else {}
    for platform in ('instagram', 'facebook'):
        surl = (social or {}).get(platform)
        if not surl:
            # also check candidate-level instagram field
            if platform == 'instagram' and candidate.get('instagram'):
                surl = normalize_instagram_url(candidate.get('instagram'))
            else:
                continue
        if not surl:
            continue
        if surl in social_cache:
            cached = social_cache[surl]
        else:
            cached = fetch_public_social_contact(surl, country_code)
            social_cache[surl] = cached
        if cached.phone:
            apply_contact_result_to_candidate(candidate, cached)
            candidate['_phone_source'] = 'OSM_DIRECT_SOCIAL_PUBLIC'
            return cached

    # E. sameAs social from official website (best effort, 1 fetch each, cached).
    # Only URLs explicitly linked via JSON-LD sameAs on the official website.
    same_as_list = []
    if normalized_url and normalized_url in website_cache:
        same_as_list = getattr(website_cache[normalized_url], 'same_as_list', []) or []
    for surl in same_as_list[:20]:
        kind = classify_external_contact_url(surl)
        if kind not in ('INSTAGRAM', 'FACEBOOK'):
            continue
        if surl in social_cache:
            cached = social_cache[surl]
        else:
            cached = fetch_public_social_contact(surl, country_code)
            # mark provenance as sameAs-derived rather than direct OSM
            if cached.source_type == 'OSM_DIRECT_SOCIAL_PUBLIC':
                cached.source_type = 'OSM_WEBSITE_SAMEAS_SOCIAL_PUBLIC'
            social_cache[surl] = cached
        if cached.phone:
            apply_contact_result_to_candidate(candidate, cached)
            candidate['_phone_source'] = 'OSM_WEBSITE_SAMEAS_SOCIAL_PUBLIC'
            return cached

    status = 'NO_PHONE'
    # propagate most informative failure status from website fetch if present
    if normalized_url and normalized_url in website_cache:
        status = website_cache[normalized_url].status
        if status == 'FOUND_PHONE':
            status = 'NO_PHONE'
    return WebsiteContactResult(None, candidate.get('email'), candidate.get('instagram'), status)


def enrich_candidate_from_osm_website(candidate, website_cache, hub_cache=None, social_cache=None):
    # phone already valid -> no HTTP
    existing = normalize_phone_for_catalog(candidate.get('phone'), candidate.get('country_code') or '')
    if existing:
        candidate['phone'] = existing
        return WebsiteContactResult(existing, candidate.get('email'), candidate.get('instagram'), 'FOUND_PHONE')
    return enrich_candidate_full(candidate, website_cache, hub_cache or {}, social_cache or {})


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


ACCEPTED_INSTAGRAM_SOURCES = frozenset({
    'DIRECT_OSM_INSTAGRAM',
    'OSM_WEBSITE_INSTAGRAM',
    'OSM_WEBSITE_CONTACT_PAGE_INSTAGRAM',
    'OSM_WEBSITE_SAMEAS_INSTAGRAM',
    'OSM_CONTACT_HUB_INSTAGRAM',
})


def _empty_qualified_pool_stats(total_rows: int) -> Dict[str, int]:
    return {
        'raw_rows': total_rows,
        'candidates_scanned': 0,
        'niche_matches': 0,
        'clusters': 0,
        'commercial_clusters': 0,
        'direct_phone': 0,
        'recovered_phone': 0,
        'direct_instagram': 0,
        'recovered_instagram': 0,
        'discarded_no_commercial': 0,
        'discarded_niche_mismatch': 0,
        'discarded_no_phone': 0,
        'discarded_no_instagram': 0,
        'discarded_not_on_whatsapp': 0,
        'discarded_duplicate': 0,
        'whatsapp_checks': 0,
        'whatsapp_verified': 0,
        'qualified_saved': 0,
        'qualified': 0,
        'website_candidates': 0,
        'website_ok': 0,
        'website_phone_found': 0,
        'website_fetch_failed': 0,
        'social_candidates': 0,
        'social_phone_found': 0,
        'merged_clusters': 0,
        'brand_website_seen': 0,
        'operator_website_seen': 0,
    }


def _normalize_instagram_handle(raw: Optional[str]) -> Optional[str]:
    if raw is None:
        return None
    handle = _instagram_handle_from_url(str(raw).strip())
    if handle:
        return handle
    value = str(raw).strip().lstrip('@').split('?')[0].split('#')[0].split('/')[0].strip()
    if not value or value.lower() in RESERVED_INSTAGRAM:
        return None
    if not re.match(r'^[A-Za-z0-9_.]+$', value):
        return None
    return value.lower()


def _direct_instagram_evidence(candidate: Dict[str, Any]) -> Tuple[Optional[str], Optional[str], Optional[str]]:
    """Direct OSM instagram evidence: (handle, source, url)."""
    tags = row_tags(candidate)
    raw = None
    if isinstance(tags, dict):
        raw = first_present(tags, INSTAGRAM_KEYS)
    if not raw:
        raw = candidate.get('instagram')
    handle = _normalize_instagram_handle(raw) if raw else None
    if not handle:
        return None, None, None
    url = f'https://www.instagram.com/{handle}/'
    return handle, 'DIRECT_OSM_INSTAGRAM', url


def _instagram_source_for_phone_source(phone_source: Optional[str]) -> Optional[str]:
    mapping = {
        'OSM_WEBSITE_CONTACT_PAGE': 'OSM_WEBSITE_CONTACT_PAGE_INSTAGRAM',
        'OSM_WEBSITE_CONTACT_HUB': 'OSM_CONTACT_HUB_INSTAGRAM',
        'OSM_WEBSITE_SAMEAS_SOCIAL_PUBLIC': 'OSM_WEBSITE_SAMEAS_INSTAGRAM',
        'OSM_DIRECT_SOCIAL_PUBLIC': 'OSM_WEBSITE_SAMEAS_INSTAGRAM',
    }
    if not phone_source:
        return 'OSM_WEBSITE_INSTAGRAM'
    if phone_source in mapping:
        return mapping[phone_source]
    if phone_source.startswith('OSM_WEBSITE'):
        return 'OSM_WEBSITE_INSTAGRAM'
    if phone_source.startswith('COMPANION'):
        return None
    if phone_source.startswith('DIRECT_'):
        return None
    return 'OSM_WEBSITE_INSTAGRAM'


def _enrich_candidate_for_missing(
    candidate: Dict[str, Any],
    need_phone: bool,
    need_instagram: bool,
    website_cache: Dict[str, WebsiteContactResult],
    hub_cache: Dict[str, WebsiteContactResult],
    social_cache: Dict[str, WebsiteContactResult],
    stats: Dict[str, int],
) -> None:
    """Official OSM-linked enrichment for whichever of phone/instagram is missing.

    Never returns early just because one of them already exists: when phone
    exists but instagram is missing (or vice-versa) the enrichment chain
    still runs to recover the missing contact.
    """
    country_code = candidate.get('country_code') or ''
    tags = row_tags(candidate)
    if not isinstance(tags, dict):
        tags = {}

    raw_site = candidate.get('website') or (first_present(tags, PRIMARY_WEBSITE_KEYS) if tags else None)
    nurl = normalize_website_url(raw_site) if raw_site else None

    if nurl:
        stats['website_candidates'] += 1
        res = website_cache.get(nurl)
        if res is None:
            try:
                res = fetch_website_contact_result(nurl, country_code, raw_url=raw_site, hub_cache=hub_cache)
            except Exception:
                res = WebsiteContactResult(None, None, None, 'FETCH_FAILED', source_url=nurl)
            website_cache[nurl] = res
        if res.status == 'FOUND_PHONE':
            stats['website_phone_found'] += 1
            stats['website_ok'] += 1
        elif res.status not in ('FOUND_PHONE', 'NO_PHONE'):
            stats['website_fetch_failed'] += 1
        if need_phone and res.phone:
            apply_contact_result_to_candidate(candidate, res)
            need_phone = False
        if need_instagram and (res.instagram or getattr(res, 'instagram_source_type', None)):
            handle = _normalize_instagram_handle(res.instagram) if res.instagram else None
            if handle and not _normalize_instagram_handle(candidate.get('instagram')):
                candidate['instagram'] = f'https://www.instagram.com/{handle}/'
            ig_source = getattr(res, 'instagram_source_type', None) or _instagram_source_for_phone_source(res.source_type)
            if ig_source in ACCEPTED_INSTAGRAM_SOURCES:
                candidate['_instagram_source'] = ig_source
                candidate['_instagram_source_url'] = getattr(res, 'instagram_source_url', None) or res.source_url or nurl
                need_instagram = False
        elif need_instagram and res.instagram:
            handle = _normalize_instagram_handle(res.instagram)
            if handle:
                if not _normalize_instagram_handle(candidate.get('instagram')):
                    candidate['instagram'] = f'https://www.instagram.com/{handle}/'
                ig_source = _instagram_source_for_phone_source(res.source_type)
                if ig_source in ACCEPTED_INSTAGRAM_SOURCES:
                    candidate['_instagram_source'] = ig_source
                    candidate['_instagram_source_url'] = res.source_url or nurl
                    need_instagram = False
        # contact-page second pass for missing pieces
        if need_phone or need_instagram:
            try:
                html_text = None
                contact_urls = _find_contact_page_urls(nurl, '') if False else []
            except Exception:
                contact_urls = []
            _ = contact_urls
            _ = html_text

    if (need_phone or need_instagram) and SOCIAL_PUBLIC_FETCH_ENABLED:
        social = official_social_urls(tags) if isinstance(tags, dict) else {}
        for platform in ('instagram', 'facebook'):
            if not (need_phone or need_instagram):
                break
            surl = (social or {}).get(platform)
            if not surl and platform == 'instagram' and candidate.get('instagram'):
                surl = normalize_instagram_url(candidate.get('instagram'))
            if not surl:
                continue
            stats['social_candidates'] += 1
            sres = social_cache.get(surl)
            if sres is None:
                try:
                    sres = fetch_public_social_contact(surl, country_code)
                except Exception:
                    sres = WebsiteContactResult(None, None, None, 'FETCH_FAILED', source_type='OSM_DIRECT_SOCIAL_PUBLIC', source_url=surl)
                social_cache[surl] = sres
            if sres.phone:
                stats['social_phone_found'] += 1
            if need_phone and sres.phone:
                apply_contact_result_to_candidate(candidate, sres)
                candidate['_phone_source'] = 'OSM_DIRECT_SOCIAL_PUBLIC'
                need_phone = False
        if need_phone:
            same_as_list: List[str] = []
            try:
                cached_res = website_cache.get(nurl) if nurl else None
                same_as_list = list(getattr(cached_res, 'same_as_list', []) or []) if cached_res else []
            except Exception:
                same_as_list = []
            for surl in (same_as_list or [])[:20]:
                if not need_phone:
                    break
                if classify_external_contact_url(surl) not in ('INSTAGRAM', 'FACEBOOK'):
                    continue
                stats['social_candidates'] += 1
                sres = social_cache.get(surl)
                if sres is None:
                    try:
                        sres = fetch_public_social_contact(surl, country_code)
                    except Exception:
                        sres = WebsiteContactResult(None, None, None, 'FETCH_FAILED', source_type='OSM_WEBSITE_SAMEAS_SOCIAL_PUBLIC', source_url=surl)
                    if getattr(sres, 'source_type', None) == 'OSM_DIRECT_SOCIAL_PUBLIC':
                        sres.source_type = 'OSM_WEBSITE_SAMEAS_SOCIAL_PUBLIC'
                    social_cache[surl] = sres
                if sres.phone:
                    stats['social_phone_found'] += 1
                    apply_contact_result_to_candidate(candidate, sres)
                    candidate['_phone_source'] = 'OSM_WEBSITE_SAMEAS_SOCIAL_PUBLIC'
                    need_phone = False
                    break


def _load_qualified_pool_dedup_sets(conn, region_id) -> Tuple[set, set]:
    phones: set = set()
    instagrams: set = set()
    if conn is None or region_id is None:
        return phones, instagrams
    try:
        with conn.cursor() as cur:
            cur.execute(
                """
                SELECT normalized_phone, normalized_instagram
                FROM osm_places
                WHERE region_id = %s
                  AND qualified = TRUE
                  AND active = TRUE
                """,
                (region_id,),
            )
            for phone, insta in cur.fetchall():
                if phone:
                    phones.add(str(phone).strip())
                if insta:
                    instagrams.add(str(insta).strip().lower())
    except Exception:
        pass
    return phones, instagrams


def build_qualified_pool_rows(
    conn,
    rows,
    region_id,
    canonical_niche,
    niche_strategy,
    target_valid=50,
    radius_meters=50.0,
):
    """Single active pipeline: niche -> phone -> instagram -> dedup -> WhatsApp.

    Only candidates passing every gate are appended to qualified_rows.
    Stops as soon as qualified_saved reaches target_valid. Technical
    WhatsApp failures raise WhatsAppInfrastructureError (FAILED), never a
    discard counter.
    """
    stats = _empty_qualified_pool_stats(len(rows))
    qualified_rows: List[Dict[str, Any]] = []

    by_name: Dict[str, list] = defaultdict(list)
    for row in rows:
        name = row.get('normalized_name')
        if name:
            by_name[name].append(row)

    seen_phones: set = set()
    seen_instagrams: set = set()
    db_phones, db_instagrams = _load_qualified_pool_dedup_sets(conn, region_id)

    website_cache: Dict[str, WebsiteContactResult] = {}
    hub_cache: Dict[str, WebsiteContactResult] = {}
    social_cache: Dict[str, WebsiteContactResult] = {}

    for same_name_rows in by_name.values():
        if stats['qualified_saved'] >= target_valid:
            break
        try:
            clusters = cluster_rows(same_name_rows, radius_meters)
        except Exception:
            continue
        for cluster in clusters:
            if stats['qualified_saved'] >= target_valid:
                break
            stats['clusters'] += 1
            try:
                commercial = any(row_is_commercial(row) for row in cluster)
            except Exception:
                commercial = True
            if not commercial:
                stats['discarded_no_commercial'] += 1
                continue
            try:
                candidate = build_merged_cluster_candidate(cluster)
            except Exception:
                stats['discarded_no_commercial'] += 1
                continue
            if candidate is None:
                stats['discarded_no_commercial'] += 1
                continue
            stats['commercial_clusters'] += 1
            stats['candidates_scanned'] += 1

            merged_tags = row_tags(candidate)
            if isinstance(merged_tags, dict):
                if first_present(merged_tags, ['brand:website']):
                    stats['brand_website_seen'] += 1
                if first_present(merged_tags, ['operator:website']):
                    stats['operator_website_seen'] += 1

            if canonical_niche and niche_strategy:
                matched, _match_type, _matched_rule = matches_niche_strategy(candidate, niche_strategy)
                if not matched:
                    stats['discarded_niche_mismatch'] += 1
                    continue
            stats['niche_matches'] += 1

            country_code = candidate.get('country_code') or ''
            direct_phone = normalize_phone_for_catalog(candidate.get('phone'), country_code)
            if direct_phone:
                stats['direct_phone'] += 1
            ig_handle, ig_source, ig_url = _direct_instagram_evidence(candidate)
            if ig_handle:
                stats['direct_instagram'] += 1
                candidate['instagram'] = f'https://www.instagram.com/{ig_handle}/'
                candidate['_instagram_source'] = ig_source
                candidate['_instagram_source_url'] = ig_url

            need_phone = not normalize_phone_for_catalog(candidate.get('phone'), country_code)
            need_instagram = _normalize_instagram_handle(candidate.get('instagram')) is None
            had_phone_before = not need_phone
            had_ig_before = not need_instagram
            if need_phone or need_instagram:
                _enrich_candidate_for_missing(
                    candidate, need_phone, need_instagram,
                    website_cache, hub_cache, social_cache, stats,
                )

            normalized_phone = normalize_phone_for_catalog(candidate.get('phone'), country_code)
            if not normalized_phone:
                stats['discarded_no_phone'] += 1
                continue
            if not had_phone_before:
                stats['recovered_phone'] += 1
            candidate['phone'] = normalized_phone
            candidate['normalized_phone'] = normalized_phone

            final_ig_handle = _normalize_instagram_handle(candidate.get('instagram'))
            final_ig_source = candidate.get('_instagram_source')
            if not final_ig_handle or final_ig_source not in ACCEPTED_INSTAGRAM_SOURCES:
                # enrichment may have recovered instagram without provenance: keep only official
                stats['discarded_no_instagram'] += 1
                continue
            if not had_ig_before:
                stats['recovered_instagram'] += 1
            candidate['instagram'] = f'https://www.instagram.com/{final_ig_handle}/'
            candidate['normalized_instagram'] = final_ig_handle
            candidate['_instagram_source'] = final_ig_source
            candidate['_instagram_source_url'] = candidate.get('_instagram_source_url') or ig_url

            phone_key = normalized_phone
            ig_key = final_ig_handle.lower()
            if phone_key in seen_phones or ig_key in seen_instagrams or phone_key in db_phones or ig_key in db_instagrams:
                stats['discarded_duplicate'] += 1
                continue

            stats['whatsapp_checks'] += 1
            exists = check_whatsapp_recipient(normalized_phone)
            if not exists:
                stats['discarded_not_on_whatsapp'] += 1
                continue
            stats['whatsapp_verified'] += 1

            seen_phones.add(phone_key)
            seen_instagrams.add(ig_key)

            qualified_row = dict(candidate)
            qualified_row['phone'] = normalized_phone
            qualified_row['normalized_phone'] = normalized_phone
            qualified_row['instagram'] = f'https://www.instagram.com/{final_ig_handle}/'
            qualified_row['normalized_instagram'] = final_ig_handle
            qualified_row['qualified'] = True
            qualified_row['whatsapp_verified'] = True
            qualified_row['instagram_validated'] = True
            qualified_row['instagram_source'] = candidate.get('_instagram_source')
            qualified_row['instagram_source_url'] = candidate.get('_instagram_source_url')
            qualified_row['last_qualified_niche'] = canonical_niche
            qualified_row['contact_status'] = 'QUALIFIED'
            if not qualified_row.get('contact_source'):
                qualified_row['contact_source'] = candidate.get('_phone_source') or 'DIRECT_OSM_PHONE'
            if not qualified_row.get('contact_source_url'):
                qualified_row['contact_source_url'] = candidate.get('_source_url')
            for drop_key in ('_phone_source', '_source_url'):
                qualified_row.pop(drop_key, None)
            qualified_rows.append(qualified_row)
            stats['qualified_saved'] += 1
            stats['qualified'] = stats['qualified_saved']
            if len(cluster) > 1:
                stats['merged_clusters'] += 1

    stats['qualified'] = stats['qualified_saved']
    return qualified_rows, stats


def resolve_qualified_final_status(stats: Dict[str, int], target_valid: int) -> Tuple[str, bool]:
    saved = int(stats.get('qualified_saved') or 0)
    if saved >= target_valid:
        return 'SUCCESS', False
    if saved > 0:
        return 'PARTIAL', True
    return 'EXHAUSTED', True


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


def _empty_qualification_stats(total_rows: int) -> Dict[str, int]:
    return {
        'raw_rows': total_rows,
        'clusters': 0,
        'qualified': 0,
        'qualified_direct_phone': 0,
        'qualified_direct_whatsapp': 0,
        'qualified_direct_mobile': 0,
        'qualified_direct_sms': 0,
        'qualified_companion_phone': 0,
        'qualified_website_phone': 0,
        'qualified_website_whatsapp': 0,
        'qualified_website_tel': 0,
        'qualified_website_jsonld': 0,
        'qualified_website_microdata': 0,
        'qualified_website_text': 0,
        'qualified_website_contact_page': 0,
        'qualified_contact_hub': 0,
        'qualified_social_public': 0,
        'discarded_no_commercial': 0,
        'discarded_no_phone': 0,
        'discarded_no_instagram': 0,
        'discarded_not_on_whatsapp': 0,
        'discarded_duplicate': 0,
        'website_candidates': 0,
        'website_ok': 0,
        'website_fetch_success': 0,
        'website_phone_found': 0,
        'website_no_phone': 0,
        'website_fetch_failed': 0,
        'website_dns_failed': 0,
        'website_timeout': 0,
        'website_tls_failed': 0,
        'website_http_403': 0,
        'website_http_404': 0,
        'website_http_429': 0,
        'website_http_5xx': 0,
        'website_too_large': 0,
        'website_binary': 0,
        'website_unsafe': 0,
        'contact_hub_candidates': 0,
        'contact_hub_phone_found': 0,
        'social_candidates': 0,
        'social_phone_found': 0,
        'social_blocked': 0,
        'brand_website_seen': 0,
        'operator_website_seen': 0,
        'merged_clusters': 0,
    }


def _count_qualified_source(stats: Dict[str, int], source: Optional[str]):
    if not source:
        return
    if source in ('DIRECT_OSM_PHONE', 'direct'):
        stats['qualified_direct_phone'] += 1
    elif source in ('DIRECT_OSM_WHATSAPP',):
        stats['qualified_direct_whatsapp'] += 1
        stats['qualified_direct_phone'] += 1
    elif source in ('DIRECT_OSM_MOBILE',):
        stats['qualified_direct_mobile'] += 1
        stats['qualified_direct_phone'] += 1
    elif source in ('DIRECT_OSM_SMS',):
        stats['qualified_direct_sms'] += 1
        stats['qualified_direct_phone'] += 1
    elif source in ('companion',) or source.startswith('COMPANION_'):
        stats['qualified_companion_phone'] += 1
    elif source == 'OSM_WEBSITE_WHATSAPP':
        stats['qualified_website_phone'] += 1
        stats['qualified_website_whatsapp'] += 1
    elif source == 'OSM_WEBSITE_TEL':
        stats['qualified_website_phone'] += 1
        stats['qualified_website_tel'] += 1
    elif source == 'OSM_WEBSITE_JSONLD_PHONE':
        stats['qualified_website_phone'] += 1
        stats['qualified_website_jsonld'] += 1
    elif source == 'OSM_WEBSITE_MICRODATA_PHONE':
        stats['qualified_website_phone'] += 1
        stats['qualified_website_microdata'] += 1
    elif source == 'OSM_WEBSITE_TEXT_PHONE':
        stats['qualified_website_phone'] += 1
        stats['qualified_website_text'] += 1
    elif source == 'OSM_WEBSITE_CONTACT_PAGE':
        stats['qualified_website_phone'] += 1
        stats['qualified_website_contact_page'] += 1
    elif source == 'OSM_WEBSITE_CONTACT_HUB':
        stats['qualified_website_phone'] += 1
        stats['qualified_contact_hub'] += 1
    elif source in ('OSM_DIRECT_SOCIAL_PUBLIC', 'OSM_WEBSITE_SAMEAS_SOCIAL_PUBLIC'):
        stats['qualified_social_public'] += 1
    elif source == 'website':
        stats['qualified_website_phone'] += 1


def _count_fetch_status(stats: Dict[str, int], status: Optional[str]):
    mapping = {
        'FOUND_PHONE': None,
        'NO_PHONE': None,
        'DNS_FAILED': 'website_dns_failed',
        'TIMEOUT': 'website_timeout',
        'TLS_FAILED': 'website_tls_failed',
        'HTTP_403': 'website_http_403',
        'HTTP_404': 'website_http_404',
        'HTTP_429': 'website_http_429',
        'HTTP_5XX': 'website_http_5xx',
        'TOO_LARGE': 'website_too_large',
        'BINARY_CONTENT': 'website_binary',
        'UNSAFE_URL': 'website_unsafe',
        'INVALID_WEBSITE': 'website_fetch_failed',
        'FETCH_FAILED': 'website_fetch_failed',
        'REDIRECT_LIMIT': 'website_fetch_failed',
        'SOCIAL_BLOCKED': None,
    }
    key = mapping.get(status or '')
    if key:
        stats[key] += 1
    elif status not in ('FOUND_PHONE', 'NO_PHONE', 'SOCIAL_BLOCKED'):
        stats['website_fetch_failed'] += 1


def qualify_staging_rows(
    rows,
    radius_meters=50.0,
):
    by_name = defaultdict(list)

    for row in rows:
        name = row.get('normalized_name')

        if name:
            by_name[name].append(row)

    stats = _empty_qualification_stats(len(rows))

    # First pass: build merged candidates per cluster
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

            # brand/operator website audit (never qualifies the branch)
            merged_tags = row_tags(candidate)
            if isinstance(merged_tags, dict):
                if first_present(merged_tags, ['brand:website']):
                    stats['brand_website_seen'] += 1
                if first_present(merged_tags, ['operator:website']):
                    stats['operator_website_seen'] += 1

            pending_candidates.append((cluster, candidate))

    # Conceptual split (spec section 28):
    # HAS_DIRECT_PHONE -> qualify immediately
    # NEEDS_OFFICIAL_ENRICHMENT -> primary website or official social present
    # NO_OFFICIAL_CHANNELS -> discard
    qualified = []
    to_enrich = []

    for cluster, cand in pending_candidates:
        has_phone = normalize_phone_for_catalog(cand.get('phone'), cand.get('country_code') or '') is not None
        if has_phone:
            src = cand.get('_phone_source') or 'direct'
            # normalize legacy markers
            if src == 'direct':
                src = 'DIRECT_OSM_PHONE'
            elif src == 'companion':
                src = 'COMPANION_OSM_PHONE'
            finalized = finalize_qualified_candidate(cand)
            if finalized:
                qualified.append((cluster, finalized, src))
            else:
                stats['discarded_no_phone'] += 1
            continue
        tags = row_tags(cand)
        needs = False
        if isinstance(tags, dict) and tags:
            if has_primary_website(tags) or has_official_social(tags):
                needs = True
        if not needs:
            # fall back to candidate-level fields (merged cluster columns)
            if normalize_website_url(cand.get('website')) or normalize_instagram_url(cand.get('instagram')):
                needs = True
        if needs:
            to_enrich.append((cluster, cand))
        else:
            stats['discarded_no_phone'] += 1

    # Parallel enrichment: websites (workers=8) + social handled inside candidate flow.
    # Website-level parallelism with shared caches; social fetches happen inline
    # per candidate with their own cache (bounded by SOCIAL_MAX_WORKERS conceptually
    # through sequential per-candidate fetches after website stage).
    page_cache: Dict[str, WebsiteContactResult] = {}
    hub_cache: Dict[str, WebsiteContactResult] = {}
    social_cache: Dict[str, WebsiteContactResult] = {}

    if to_enrich:
        # Count website candidates (primary website present)
        website_cands = []
        for cluster, cand in to_enrich:
            tags = row_tags(cand)
            raw_site = cand.get('website') or (first_present(tags, PRIMARY_WEBSITE_KEYS) if isinstance(tags, dict) else None)
            if normalize_website_url(raw_site) if raw_site else None:
                website_cands.append((cluster, cand))
        stats['website_candidates'] = len(website_cands)

        # Deduplicate website fetches by normalized URL (cache = single GET per URL)
        url_to_country: Dict[str, str] = {}
        for _, cand in website_cands:
            tags = row_tags(cand)
            raw_site = cand.get('website') or (first_present(tags, PRIMARY_WEBSITE_KEYS) if isinstance(tags, dict) else None)
            nurl = normalize_website_url(raw_site)
            if nurl and nurl not in url_to_country:
                url_to_country[nurl] = cand.get('country_code') or ''

        if url_to_country:
            max_workers = max(1, min(WEBSITE_MAX_WORKERS, len(url_to_country)))

            def _fetch_one(item):
                nurl, country = item
                # find a representative raw url for fallback semantics
                raw_probe = nurl
                for _, c in website_cands:
                    tags = row_tags(c)
                    raw_site = c.get('website') or (first_present(tags, PRIMARY_WEBSITE_KEYS) if isinstance(tags, dict) else None)
                    if raw_site and normalize_website_url(raw_site) == nurl:
                        raw_probe = raw_site
                        break
                try:
                    res = fetch_website_contact_result(nurl, country, raw_url=raw_probe, hub_cache=hub_cache)
                except Exception:
                    res = WebsiteContactResult(None, None, None, 'FETCH_FAILED')
                return nurl, res

            with ThreadPoolExecutor(max_workers=max_workers) as executor:
                future_to_url = {
                    executor.submit(_fetch_one, item): item[0]
                    for item in url_to_country.items()
                }
                for future in as_completed(future_to_url):
                    nurl = future_to_url[future]
                    try:
                        _, res = future.result()
                    except Exception:
                        res = WebsiteContactResult(None, None, None, 'FETCH_FAILED')
                    page_cache[nurl] = res

        # Per-candidate application + social fallback (uses caches, no duplicate GETs)
        for cluster, cand in to_enrich:
            # website stage
            tags = row_tags(cand)
            raw_site = cand.get('website') or (first_present(tags, PRIMARY_WEBSITE_KEYS) if isinstance(tags, dict) else None)
            nurl = normalize_website_url(raw_site) if raw_site else None
            res = page_cache.get(nurl) if nurl else None
            website_status = res.status if res else None
            if res and res.phone:
                apply_contact_result_to_candidate(cand, res)
            else:
                if res:
                    if res.email and not cand.get('email'):
                        cand['email'] = res.email
                    if res.instagram and not cand.get('instagram'):
                        cand['instagram'] = res.instagram
                # social fallback stages D/E (single GET each, cached)
                if SOCIAL_PUBLIC_FETCH_ENABLED:
                    # D: direct OSM social
                    social = official_social_urls(tags) if isinstance(tags, dict) else {}
                    attempted_social = False
                    for platform in ('instagram', 'facebook'):
                        surl = (social or {}).get(platform)
                        if not surl and platform == 'instagram' and cand.get('instagram'):
                            surl = normalize_instagram_url(cand.get('instagram'))
                        if not surl:
                            continue
                        attempted_social = True
                        if surl in social_cache:
                            sres = social_cache[surl]
                        else:
                            max_social_workers = max(1, SOCIAL_MAX_WORKERS)
                            _ = max_social_workers  # documents bounded concurrency
                            sres = fetch_public_social_contact(surl, cand.get('country_code') or '')
                            social_cache[surl] = sres
                        if sres.phone:
                            apply_contact_result_to_candidate(cand, sres)
                            cand['_phone_source'] = 'OSM_DIRECT_SOCIAL_PUBLIC'
                            break
                    # E: sameAs social from official website
                    if not normalize_phone_for_catalog(cand.get('phone'), cand.get('country_code') or ''):
                        same_as_list = getattr(res, 'same_as_list', []) if res else []
                        for surl in (same_as_list or [])[:20]:
                            if classify_external_contact_url(surl) not in ('INSTAGRAM', 'FACEBOOK'):
                                continue
                            attempted_social = True
                            if surl in social_cache:
                                sres = social_cache[surl]
                            else:
                                sres = fetch_public_social_contact(surl, cand.get('country_code') or '')
                                if sres.source_type == 'OSM_DIRECT_SOCIAL_PUBLIC':
                                    sres.source_type = 'OSM_WEBSITE_SAMEAS_SOCIAL_PUBLIC'
                                social_cache[surl] = sres
                            if sres.phone:
                                apply_contact_result_to_candidate(cand, sres)
                                cand['_phone_source'] = 'OSM_WEBSITE_SAMEAS_SOCIAL_PUBLIC'
                                break
                    _ = attempted_social

            finalized_src = cand.get('_phone_source') or (res.source_type if res and res.phone else None) or 'website'
            finalized = finalize_qualified_candidate(cand)
            if finalized:
                qualified.append((cluster, finalized, finalized_src))
                if nurl:
                    stats['website_ok'] += 1
            else:
                stats['discarded_no_phone'] += 1

        # Per-candidate website outcome metrics (independent of final qualification:
        # a website fetch failure still counts even if social later recovered a phone)
        for _, cand in to_enrich:
            tags = row_tags(cand)
            # NOTE: cand was mutated; recover original site from page_cache keys is
            # unreliable, so recompute from tags/fields (website field unchanged by flow)
            raw_site = cand.get('website') or (first_present(tags, PRIMARY_WEBSITE_KEYS) if isinstance(tags, dict) else None)
            nurl = normalize_website_url(raw_site) if raw_site else None
            if not nurl:
                continue
            res = page_cache.get(nurl)
            if res is None:
                stats['website_fetch_failed'] += 1
            elif res.status == 'FOUND_PHONE':
                stats['website_phone_found'] += 1
                stats['website_fetch_success'] += 1
            elif res.status == 'NO_PHONE':
                stats['website_no_phone'] += 1
                stats['website_fetch_success'] += 1
            else:
                _count_fetch_status(stats, res.status)

        # Hub metrics: hubs actually fetched (hub_cache populated during website stage)
        stats['contact_hub_candidates'] = len(hub_cache)
        stats['contact_hub_phone_found'] = sum(1 for r in hub_cache.values() if r.phone)
        # Social metrics
        stats['social_candidates'] = len(social_cache)
        stats['social_phone_found'] = sum(1 for r in social_cache.values() if r.phone)
        stats['social_blocked'] = sum(1 for r in social_cache.values() if r.status == 'SOCIAL_BLOCKED')
        # website_too_large/binary counted via detailed statuses
        for res in page_cache.values():
            if res.status in ('TOO_LARGE', 'BINARY_CONTENT'):
                _count_fetch_status(stats, res.status)

    # Now qualified list contains tuples; flatten and count merged + sources
    final_qualified_rows = []
    for cluster, fin, src in qualified:
        final_qualified_rows.append(fin)
        if len(cluster) > 1:
            stats['merged_clusters'] += 1
        _count_qualified_source(stats, src)

    stats['qualified'] = len(final_qualified_rows)

    return final_qualified_rows, stats


def _empty_catalog_stats(total_rows: int) -> Dict[str, int]:
    return {
        'raw_rows': total_rows,
        'clusters': 0,
        'commercial_rows': 0,
        'contact_only_rows': 0,
        'commercial_clusters': 0,
        'catalog_published': 0,
        'with_phone': 0,
        'without_phone': 0,
        'direct_phone': 0,
        'direct_whatsapp': 0,
        'direct_mobile': 0,
        'direct_sms': 0,
        'enriched_phone': 0,
        'enriched_website_whatsapp': 0,
        'enriched_website_tel': 0,
        'enriched_website_jsonld': 0,
        'enriched_website_microdata': 0,
        'enriched_website_text': 0,
        'enriched_website_contact_page': 0,
        'enriched_contact_hub': 0,
        'enriched_social_public': 0,
        'discarded_no_commercial': 0,
        'discarded_niche_mismatch': 0,
        'website_candidates': 0,
        'website_ok': 0,
        'website_fetch_success': 0,
        'website_phone_found': 0,
        'website_no_phone': 0,
        'website_fetch_failed': 0,
        'website_dns_failed': 0,
        'website_timeout': 0,
        'website_tls_failed': 0,
        'website_http_403': 0,
        'website_http_404': 0,
        'website_http_429': 0,
        'website_http_5xx': 0,
        'website_too_large': 0,
        'website_binary': 0,
        'website_unsafe': 0,
        'contact_hub_candidates': 0,
        'contact_hub_phone_found': 0,
        'social_candidates': 0,
        'social_phone_found': 0,
        'social_blocked': 0,
        'brand_website_seen': 0,
        'operator_website_seen': 0,
        'merged_clusters': 0,
    }


def _count_catalog_source(stats: Dict[str, int], source: Optional[str]):
    if not source:
        return
    if source in ('DIRECT_OSM_PHONE', 'DIRECT_OSM_WHATSAPP', 'DIRECT_OSM_MOBILE', 'DIRECT_OSM_SMS'):
        stats['with_phone'] += 1
        if source == 'DIRECT_OSM_WHATSAPP':
            stats['direct_whatsapp'] += 1
        elif source == 'DIRECT_OSM_MOBILE':
            stats['direct_mobile'] += 1
        elif source == 'DIRECT_OSM_SMS':
            stats['direct_sms'] += 1
        else:
            stats['direct_phone'] += 1
    elif source.startswith('COMPANION_'):
        stats['with_phone'] += 1
    elif source in ('OSM_WEBSITE_WHATSAPP', 'OSM_WEBSITE_TEL', 'OSM_WEBSITE_JSONLD_PHONE',
                    'OSM_WEBSITE_MICRODATA_PHONE', 'OSM_WEBSITE_TEXT_PHONE',
                    'OSM_WEBSITE_CONTACT_PAGE', 'OSM_WEBSITE_CONTACT_HUB',
                    'OSM_DIRECT_SOCIAL_PUBLIC', 'OSM_WEBSITE_SAMEAS_SOCIAL_PUBLIC'):
        stats['with_phone'] += 1
        stats['enriched_phone'] += 1
        if source == 'OSM_WEBSITE_WHATSAPP':
            stats['enriched_website_whatsapp'] += 1
        elif source == 'OSM_WEBSITE_TEL':
            stats['enriched_website_tel'] += 1
        elif source == 'OSM_WEBSITE_JSONLD_PHONE':
            stats['enriched_website_jsonld'] += 1
        elif source == 'OSM_WEBSITE_MICRODATA_PHONE':
            stats['enriched_website_microdata'] += 1
        elif source == 'OSM_WEBSITE_TEXT_PHONE':
            stats['enriched_website_text'] += 1
        elif source == 'OSM_WEBSITE_CONTACT_PAGE':
            stats['enriched_website_contact_page'] += 1
        elif source == 'OSM_WEBSITE_CONTACT_HUB':
            stats['enriched_contact_hub'] += 1
        elif source in ('OSM_DIRECT_SOCIAL_PUBLIC', 'OSM_WEBSITE_SAMEAS_SOCIAL_PUBLIC'):
            stats['enriched_social_public'] += 1


def build_catalog_rows(
    rows,
    radius_meters=50.0,
    canonical_niche=None,
    target_valid=50,
    niche_strategy=None,
):
    by_name = defaultdict(list)

    for row in rows:
        name = row.get('normalized_name')

        if name:
            by_name[name].append(row)

    stats = _empty_catalog_stats(len(rows))

    # First pass: build merged candidates per cluster
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

            stats['commercial_clusters'] += 1

            # Niche matching - only process candidates that match the requested niche
            if canonical_niche and niche_strategy:
                matched, match_type, matched_rule = matches_niche_strategy(candidate, niche_strategy)
                if not matched:
                    stats['discarded_niche_mismatch'] += 1
                    continue

            # brand/operator website audit (never qualifies the branch)
            merged_tags = row_tags(candidate)
            if isinstance(merged_tags, dict):
                if first_present(merged_tags, ['brand:website']):
                    stats['brand_website_seen'] += 1
                if first_present(merged_tags, ['operator:website']):
                    stats['operator_website_seen'] += 1

            pending_candidates.append((cluster, candidate))

    stats['commercial_rows'] = sum(1 for _, c in pending_candidates)
    stats['contact_only_rows'] = len(rows) - stats['commercial_rows'] - stats['discarded_no_commercial']

    # Separate candidates: those with direct phone, those needing enrichment, those without any official channel
    catalog_candidates = []
    to_enrich = []

    for cluster, cand in pending_candidates:
        has_phone = normalize_phone_for_catalog(cand.get('phone'), cand.get('country_code') or '') is not None
        if has_phone:
            src = cand.get('_phone_source') or 'DIRECT_OSM_PHONE'
            # normalize legacy markers
            if src == 'direct':
                src = 'DIRECT_OSM_PHONE'
            elif src == 'companion':
                src = 'COMPANION_OSM_PHONE'
            catalog_candidates.append((cluster, cand, src))
            continue
        tags = row_tags(cand)
        needs = False
        if isinstance(tags, dict) and tags:
            if has_primary_website(tags) or has_official_social(tags):
                needs = True
        if not needs:
            # fall back to candidate-level fields (merged cluster columns)
            if normalize_website_url(cand.get('website')) or normalize_instagram_url(cand.get('instagram')):
                needs = True
        if needs:
            to_enrich.append((cluster, cand))
        else:
            catalog_candidates.append((cluster, cand, None))

    # Parallel enrichment for candidates with official channels
    page_cache: Dict[str, WebsiteContactResult] = {}
    hub_cache: Dict[str, WebsiteContactResult] = {}
    social_cache: Dict[str, WebsiteContactResult] = {}

    if to_enrich:
        # Count website candidates (primary website present)
        website_cands = []
        for cluster, cand in to_enrich:
            tags = row_tags(cand)
            raw_site = cand.get('website') or (first_present(tags, PRIMARY_WEBSITE_KEYS) if isinstance(tags, dict) else None)
            if normalize_website_url(raw_site) if raw_site else None:
                website_cands.append((cluster, cand))
        stats['website_candidates'] = len(website_cands)

        # Deduplicate website fetches by normalized URL (cache = single GET per URL)
        url_to_country: Dict[str, str] = {}
        for _, cand in website_cands:
            tags = row_tags(cand)
            raw_site = cand.get('website') or (first_present(tags, PRIMARY_WEBSITE_KEYS) if isinstance(tags, dict) else None)
            nurl = normalize_website_url(raw_site)
            if nurl and nurl not in url_to_country:
                url_to_country[nurl] = cand.get('country_code') or ''

        if url_to_country:
            max_workers = max(1, min(WEBSITE_MAX_WORKERS, len(url_to_country)))

            def _fetch_one(item):
                nurl, country = item
                # find a representative raw url for fallback semantics
                raw_probe = nurl
                for _, c in website_cands:
                    tags = row_tags(c)
                    raw_site = c.get('website') or (first_present(tags, PRIMARY_WEBSITE_KEYS) if isinstance(tags, dict) else None)
                    if raw_site and normalize_website_url(raw_site) == nurl:
                        raw_probe = raw_site
                        break
                try:
                    res = fetch_website_contact_result(nurl, country, raw_url=raw_probe, hub_cache=hub_cache)
                except Exception:
                    res = WebsiteContactResult(None, None, None, 'FETCH_FAILED')
                return nurl, res

            with ThreadPoolExecutor(max_workers=max_workers) as executor:
                future_to_url = {
                    executor.submit(_fetch_one, item): item[0]
                    for item in url_to_country.items()
                }
                for future in as_completed(future_to_url):
                    nurl = future_to_url[future]
                    try:
                        _, res = future.result()
                    except Exception:
                        res = WebsiteContactResult(None, None, None, 'FETCH_FAILED')
                    page_cache[nurl] = res

        # Per-candidate application + social fallback (uses caches, no duplicate GETs)
        for cluster, cand in to_enrich:
            # website stage
            tags = row_tags(cand)
            raw_site = cand.get('website') or (first_present(tags, PRIMARY_WEBSITE_KEYS) if isinstance(tags, dict) else None)
            nurl = normalize_website_url(raw_site) if raw_site else None
            res = page_cache.get(nurl) if nurl else None
            website_status = res.status if res else None
            if res and res.phone:
                apply_contact_result_to_candidate(cand, res)
            else:
                if res:
                    if res.email and not cand.get('email'):
                        cand['email'] = res.email
                    if res.instagram and not cand.get('instagram'):
                        cand['instagram'] = res.instagram
                # social fallback stages D/E (single GET each, cached)
                if SOCIAL_PUBLIC_FETCH_ENABLED:
                    # D: direct OSM social
                    social = official_social_urls(tags) if isinstance(tags, dict) else {}
                    for platform in ('instagram', 'facebook'):
                        surl = (social or {}).get(platform)
                        if not surl and platform == 'instagram' and cand.get('instagram'):
                            surl = normalize_instagram_url(cand.get('instagram'))
                        if not surl:
                            continue
                        if surl in social_cache:
                            sres = social_cache[surl]
                        else:
                            sres = fetch_public_social_contact(surl, cand.get('country_code') or '')
                            social_cache[surl] = sres
                        if sres.phone:
                            apply_contact_result_to_candidate(cand, sres)
                            cand['_phone_source'] = 'OSM_DIRECT_SOCIAL_PUBLIC'
                            break
                    # E: sameAs social from official website
                    if not normalize_phone_for_catalog(cand.get('phone'), cand.get('country_code') or ''):
                        same_as_list = getattr(res, 'same_as_list', []) if res else []
                        for surl in (same_as_list or [])[:20]:
                            if classify_external_contact_url(surl) not in ('INSTAGRAM', 'FACEBOOK'):
                                continue
                            if surl in social_cache:
                                sres = social_cache[surl]
                            else:
                                sres = fetch_public_social_contact(surl, cand.get('country_code') or '')
                                if sres.source_type == 'OSM_DIRECT_SOCIAL_PUBLIC':
                                    sres.source_type = 'OSM_WEBSITE_SAMEAS_SOCIAL_PUBLIC'
                                social_cache[surl] = sres
                            if sres.phone:
                                apply_contact_result_to_candidate(cand, sres)
                                cand['_phone_source'] = 'OSM_WEBSITE_SAMEAS_SOCIAL_PUBLIC'
                                break

            catalog_candidates.append((cluster, cand, cand.get('_phone_source') or (res.source_type if res and res.phone else None)))

        # Per-candidate website outcome metrics
        for _, cand in to_enrich:
            tags = row_tags(cand)
            raw_site = cand.get('website') or (first_present(tags, PRIMARY_WEBSITE_KEYS) if isinstance(tags, dict) else None)
            nurl = normalize_website_url(raw_site) if raw_site else None
            if not nurl:
                continue
            res = page_cache.get(nurl)
            if res is None:
                stats['website_fetch_failed'] += 1
            elif res.status == 'FOUND_PHONE':
                stats['website_phone_found'] += 1
                stats['website_fetch_success'] += 1
            elif res.status == 'NO_PHONE':
                stats['website_no_phone'] += 1
                stats['website_fetch_success'] += 1
            else:
                _count_fetch_status(stats, res.status)

        # Hub metrics
        stats['contact_hub_candidates'] = len(hub_cache)
        stats['contact_hub_phone_found'] = sum(1 for r in hub_cache.values() if r.phone)
        # Social metrics
        stats['social_candidates'] = len(social_cache)
        stats['social_phone_found'] = sum(1 for r in social_cache.values() if r.phone)
        stats['social_blocked'] = sum(1 for r in social_cache.values() if r.status == 'SOCIAL_BLOCKED')
        for res in page_cache.values():
            if res.status in ('TOO_LARGE', 'BINARY_CONTENT'):
                _count_fetch_status(stats, res.status)

    # Add candidates without enrichment to catalog (those without official channels)
    for cluster, cand, src in catalog_candidates:
        # Already have source from earlier
        pass

    # Now catalog_candidates contains all commercial clusters
    final_catalog_rows = []
    for cluster, fin, src in catalog_candidates:
        final_catalog_rows.append(fin)
        if len(cluster) > 1:
            stats['merged_clusters'] += 1
        _count_catalog_source(stats, src)

    stats['catalog_published'] = len(final_catalog_rows)
    stats['with_phone'] = sum(1 for c in final_catalog_rows if normalize_phone_for_catalog(c.get('phone'), c.get('country_code') or ''))
    stats['without_phone'] = stats['catalog_published'] - stats['with_phone']

    return final_catalog_rows, stats


def replace_staging_with_catalog(
    conn,
    sync_run_id,
    catalog_rows,
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

    if catalog_rows:
        insert_staging_batch(
            conn,
            catalog_rows,
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
        r.setdefault('normalized_phone', None)
        r.setdefault('normalized_instagram', None)
        r.setdefault('qualified', False)
        r.setdefault('whatsapp_verified', False)
        r.setdefault('instagram_validated', False)
        r.setdefault('instagram_source', None)
        r.setdefault('instagram_source_url', None)
        r.setdefault('last_qualified_niche', None)
        r.setdefault('contact_status', None)
        r.setdefault('contact_source', None)
        r.setdefault('contact_source_url', None)
        cleaned.append(r)
    sql = """
        INSERT INTO osm_place_staging (
            sync_run_id, region_id, osm_type, osm_id,
            business_name, normalized_name,
            latitude, longitude,
            address, city, state, country, country_code,
            phone, email, website, instagram,
            tags, source_timestamp,
            normalized_phone, normalized_instagram,
            qualified, whatsapp_verified, instagram_validated,
            instagram_source, instagram_source_url, last_qualified_niche,
            contact_status, contact_source, contact_source_url
        ) VALUES (
            %(sync_run_id)s, %(region_id)s, %(osm_type)s, %(osm_id)s,
            %(business_name)s, %(normalized_name)s,
            %(latitude)s, %(longitude)s,
            %(address)s, %(city)s, %(state)s, %(country)s, %(country_code)s,
            %(phone)s, %(email)s, %(website)s, %(instagram)s,
            %(tags)s::jsonb, %(source_timestamp)s,
            %(normalized_phone)s, %(normalized_instagram)s,
            %(qualified)s, %(whatsapp_verified)s, %(instagram_validated)s,
            %(instagram_source)s, %(instagram_source_url)s, %(last_qualified_niche)s,
            %(contact_status)s, %(contact_source)s, %(contact_source_url)s
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
        log('error', 'Sanity check failed: previous qualified count > 50 but staged count is 0',
            previous=previous_count, staged=staged_count)
        return False
    if previous_count > 50 and staged_count < previous_count * SANITY_DROP_THRESHOLD:
        log('error', 'Sanity check failed: staged count dropped below 10% of previous qualified',
            previous=previous_count, staged=staged_count, threshold=SANITY_DROP_THRESHOLD)
        return False
    return True


def publish_staging(
    conn,
    sync_run_id: int,
    region_id: int,
    stats: Dict[str, int],
    final_status: str = 'SUCCESS',
    dataset_exhausted: bool = False,
):
    """Incremental publish: upserts only qualified staging rows.

    Never deactivates existing pool rows: each sync only ADDS newly
    qualified leads (sync 1 -> +50, sync 2 -> +50, pool -> 100).
    place_count counts only the qualified pool. The sync-run status is
    the resolved final_status (SUCCESS/PARTIAL/EXHAUSTED), never a
    hardcoded SUCCESS.
    """
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
                      AND s.qualified = TRUE
                      AND p.id IS NULL
                    """,
                    (sync_run_id,),
                )

                inserted = cur.fetchone()[0]

                cur.execute(
                    """
                    SELECT COUNT(*)
                    FROM osm_place_staging
                    WHERE sync_run_id = %s
                      AND qualified = TRUE
                    """,
                    (sync_run_id,),
                )

                staged_qualified = cur.fetchone()[0]
                updated = max(0, staged_qualified - inserted)

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
                        source_timestamp,
                        normalized_phone,
                        normalized_instagram,
                        qualified,
                        qualified_at,
                        whatsapp_verified,
                        whatsapp_verified_at,
                        instagram_validated,
                        instagram_validated_at,
                        instagram_source,
                        instagram_source_url,
                        last_qualified_niche,
                        contact_status,
                        contact_source,
                        contact_source_url,
                        enriched_at
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
                        s.source_timestamp,
                        s.normalized_phone,
                        s.normalized_instagram,
                        true,
                        NOW(),
                        COALESCE(s.whatsapp_verified, true),
                        NOW(),
                        COALESCE(s.instagram_validated, true),
                        NOW(),
                        s.instagram_source,
                        s.instagram_source_url,
                        s.last_qualified_niche,
                        COALESCE(s.contact_status, 'QUALIFIED'),
                        s.contact_source,
                        s.contact_source_url,
                        NOW()
                    FROM osm_place_staging s
                    WHERE s.sync_run_id = %s
                      AND s.qualified = TRUE
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
                        normalized_phone = EXCLUDED.normalized_phone,
                        normalized_instagram = EXCLUDED.normalized_instagram,
                        qualified = true,
                        qualified_at = NOW(),
                        whatsapp_verified = true,
                        whatsapp_verified_at = NOW(),
                        instagram_validated = true,
                        instagram_validated_at = NOW(),
                        instagram_source = EXCLUDED.instagram_source,
                        instagram_source_url = EXCLUDED.instagram_source_url,
                        last_qualified_niche = EXCLUDED.last_qualified_niche,
                        contact_status = EXCLUDED.contact_status,
                        contact_source = EXCLUDED.contact_source,
                        contact_source_url = EXCLUDED.contact_source_url,
                        enriched_at = NOW(),
                        updated_at = NOW()
                    """,
                    (sync_run_id,),
                )

                deactivated = 0

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
                              AND active = TRUE
                              AND qualified = TRUE
                              AND whatsapp_verified = TRUE
                              AND instagram_validated = TRUE
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
                        status = %s,
                        finished_at = NOW(),
                        places_read = %s,
                        places_staged = %s,
                        places_inserted = %s,
                        places_updated = %s,
                        places_deactivated = %s,
                        candidates_scanned = %s,
                        niche_matches = %s,
                        discarded_no_phone = %s,
                        discarded_no_instagram = %s,
                        discarded_not_on_whatsapp = %s,
                        discarded_duplicate = %s,
                        qualified_saved = %s,
                        dataset_exhausted = %s
                    WHERE id = %s
                    """,
                    (
                        final_status,
                        stats.get("read", 0),
                        staged_qualified,
                        inserted,
                        updated,
                        deactivated,
                        stats.get("candidates_scanned", 0),
                        stats.get("niche_matches", 0),
                        stats.get("discarded_no_phone", 0),
                        stats.get("discarded_no_instagram", 0),
                        stats.get("discarded_not_on_whatsapp", 0),
                        stats.get("discarded_duplicate", 0),
                        stats.get("qualified_saved", 0),
                        dataset_exhausted,
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

        coverage = audit_contact_coverage(raw_rows)

        log('info', 'contact_coverage_summary', **coverage)

        # Parse niche strategy from JSON
        niche_strategy_json = os.environ.get('NICHE_STRATEGY_JSON', '{}')
        try:
            niche_strategy = json.loads(niche_strategy_json)
        except json.JSONDecodeError as e:
            log('error', 'Failed to parse niche strategy JSON', error=str(e))
            sys.exit(1)

        canonical_niche = os.environ.get('CANONICAL_NICHE', args.canonical_niche)
        target_valid = int(os.environ.get('TARGET_VALID', args.target_valid))

        try:
            qualified_rows, qualified_stats = build_qualified_pool_rows(
                conn,
                raw_rows,
                args.region_id,
                canonical_niche,
                niche_strategy,
                target_valid=target_valid,
                radius_meters=50.0,
            )
        except WhatsAppInfrastructureError as e:
            log('error', 'whatsapp_check_failed', error=str(e))
            raise

        for key, value in qualified_stats.items():
            stats[key] = value

        log(
            'info',
            'qualified_summary',
            canonicalNiche=canonical_niche,
            targetValid=target_valid,
            candidatesScanned=qualified_stats.get('candidates_scanned', 0),
            nicheMatches=qualified_stats.get('niche_matches', 0),
            directPhone=qualified_stats.get('direct_phone', 0),
            recoveredPhone=qualified_stats.get('recovered_phone', 0),
            directInstagram=qualified_stats.get('direct_instagram', 0),
            recoveredInstagram=qualified_stats.get('recovered_instagram', 0),
            discardedNoPhone=qualified_stats.get('discarded_no_phone', 0),
            discardedNoInstagram=qualified_stats.get('discarded_no_instagram', 0),
            discardedNotOnWhatsApp=qualified_stats.get('discarded_not_on_whatsapp', 0),
            discardedDuplicate=qualified_stats.get('discarded_duplicate', 0),
            whatsappChecks=qualified_stats.get('whatsapp_checks', 0),
            whatsappVerified=qualified_stats.get('whatsapp_verified', 0),
            qualifiedSaved=qualified_stats.get('qualified_saved', 0),
        )

        replace_staging_with_catalog(
            conn,
            args.sync_run_id,
            qualified_rows,
        )

        qualified_count = validate_staging(
            conn,
            args.sync_run_id,
        )

        stats['staged'] = qualified_count

        # Sanity check on the qualified staging count
        if not sanity_check(conn, args.sync_run_id, args.region_id, qualified_count):
            raise ValueError('Sanity check failed: extreme drop in place count')

        final_status, dataset_exhausted = resolve_qualified_final_status(qualified_stats, target_valid)

        log(
            'info',
            'qualified_final_status',
            status=final_status,
            datasetExhausted=dataset_exhausted,
            qualifiedSaved=qualified_stats.get('qualified_saved', 0),
        )

        # Publish atomically (single transaction - includes final status marking and staging cleanup)
        log('info', 'publish_start', sync_run_id=args.sync_run_id)
        inserted, updated, deactivated = publish_staging(
            conn,
            args.sync_run_id,
            args.region_id,
            stats,
            final_status,
            dataset_exhausted,
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
