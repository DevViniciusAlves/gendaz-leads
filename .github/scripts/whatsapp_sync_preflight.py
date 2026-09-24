#!/usr/bin/env python3
"""WhatsApp session preflight for the OSM catalog sync workflow.

Ensures the whatsapp-service session is CONNECTED before the workflow
downloads the Geofabrik PBF. Read-only plus an explicit connect trigger:

    GET /internal/whatsapp/session/status
    CONNECTED -> PASS
    NOT_CONNECTED / DISCONNECTED / CONNECTING
        -> POST /internal/whatsapp/session/connect -> poll up to 90s
    QR_REQUIRED / LOGGED_OUT / ERROR -> clear FAIL

Never resets the session, never clears credentials, never sends messages.
Transport/cold-start failures are retried until the deadline.
"""

import json
import os
import sys
import time
import urllib.error
import urllib.request


class PreflightError(RuntimeError):
    pass


CONNECTABLE = ('NOT_CONNECTED', 'DISCONNECTED', 'CONNECTING')
TERMINAL_BAD = ('QR_REQUIRED', 'LOGGED_OUT', 'ERROR')


def _http(method, base_url, token, path, timeout):
    req = urllib.request.Request(
        base_url.rstrip('/') + path,
        data=b'{}' if method == 'POST' else None,
        headers={
            'Authorization': f'Bearer {token}',
            'Content-Type': 'application/json',
            'Accept': 'application/json',
        },
        method=method,
    )
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            return json.loads(resp.read().decode('utf-8'))
    except urllib.error.HTTPError as e:
        raise PreflightError(f'WhatsApp service HTTP {e.code} on {path}')
    except Exception as e:
        raise PreflightError(f'WhatsApp service unreachable on {path}: {e}')


def get_status(base_url, token, timeout=10):
    data = _http('GET', base_url, token, '/internal/whatsapp/session/status', timeout)
    status = (data or {}).get('status')
    if not status:
        raise PreflightError('WhatsApp status response without status')
    return status


def trigger_connect(base_url, token, timeout=10):
    data = _http('POST', base_url, token, '/internal/whatsapp/session/connect', timeout)
    return (data or {}).get('status')


def run_preflight(
    base_url,
    token,
    timeout=10,
    max_wait_s=90,
    poll_interval_s=5,
    sleep_fn=None,
    now_fn=None,
    get_status_fn=None,
    connect_fn=None,
):
    """Return 'CONNECTED' or raise PreflightError with a clear reason."""
    sleep = sleep_fn or time.sleep
    now = now_fn or time.time
    get_fn = get_status_fn or (lambda: get_status(base_url, token, timeout))
    connect = connect_fn or (lambda: trigger_connect(base_url, token, timeout))

    deadline = now() + max_wait_s
    attempted_connect = False

    while True:
        try:
            status = get_fn()
        except PreflightError:
            if now() >= deadline:
                raise
            sleep(poll_interval_s)
            continue

        if status == 'CONNECTED':
            return status
        if status in TERMINAL_BAD:
            raise PreflightError(
                f'WhatsApp session not usable: {status}. '
                'Reconnect manually and re-run the sync.'
            )
        if not attempted_connect and status in CONNECTABLE:
            try:
                connect()
            except PreflightError:
                pass
            attempted_connect = True
        if now() >= deadline:
            raise PreflightError(
                f'WhatsApp session did not reach CONNECTED in {max_wait_s}s '
                f'(last status: {status}).'
            )
        sleep(poll_interval_s)


def main():
    base_url = os.environ.get('OSM_SYNC_WHATSAPP_SERVICE_URL', '').strip()
    token = os.environ.get('OSM_SYNC_WHATSAPP_INTERNAL_TOKEN', '').strip()
    if not base_url or not token:
        print('[osm-sync] preflight_failed error=whatsapp_not_configured', file=sys.stderr)
        return 1
    try:
        status = run_preflight(base_url, token)
    except PreflightError as e:
        print(f'[osm-sync] preflight_failed error={e}', file=sys.stderr)
        return 1
    print(f'[osm-sync] preflight_ok status={status}')
    return 0


if __name__ == '__main__':
    sys.exit(main())
