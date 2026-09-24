import unittest

import whatsapp_sync_preflight as preflight


class FakeClock:
    def __init__(self):
        self.now = 1000.0
        self.sleeps = []

    def time(self):
        return self.now

    def sleep(self, seconds):
        self.sleeps.append(seconds)
        self.now += seconds


def scripted(responses):
    state = {'i': 0}

    def get():
        idx = state['i']
        state['i'] += 1
        value = responses[min(idx, len(responses) - 1)]
        if isinstance(value, Exception):
            raise value
        return value

    return get


class TestWhatsAppPreflight(unittest.TestCase):
    def test_connected_passes_immediately(self):
        clock = FakeClock()
        status = preflight.run_preflight(
            'http://wpp:3000', 'tok',
            max_wait_s=90, poll_interval_s=5,
            sleep_fn=clock.sleep, now_fn=clock.time,
            get_status_fn=scripted(['CONNECTED']),
            connect_fn=lambda: 'CONNECTED',
        )
        self.assertEqual(status, 'CONNECTED')
        self.assertEqual(clock.sleeps, [])

    def test_not_connected_triggers_connect_then_passes(self):
        clock = FakeClock()
        connects = []
        status = preflight.run_preflight(
            'http://wpp:3000', 'tok',
            max_wait_s=90, poll_interval_s=5,
            sleep_fn=clock.sleep, now_fn=clock.time,
            get_status_fn=scripted(['NOT_CONNECTED', 'CONNECTING', 'CONNECTED']),
            connect_fn=lambda: connects.append(1) or 'CONNECTING',
        )
        self.assertEqual(status, 'CONNECTED')
        self.assertEqual(len(connects), 1)

    def test_connecting_reaches_connected_without_extra_connect(self):
        clock = FakeClock()
        connects = []
        status = preflight.run_preflight(
            'http://wpp:3000', 'tok',
            max_wait_s=90, poll_interval_s=5,
            sleep_fn=clock.sleep, now_fn=clock.time,
            get_status_fn=scripted(['CONNECTING', 'CONNECTED']),
            connect_fn=lambda: connects.append(1) or 'CONNECTING',
        )
        self.assertEqual(status, 'CONNECTED')
        self.assertEqual(len(connects), 1)

    def test_qr_required_fails_fast(self):
        clock = FakeClock()
        with self.assertRaises(preflight.PreflightError) as ctx:
            preflight.run_preflight(
                'http://wpp:3000', 'tok',
                sleep_fn=clock.sleep, now_fn=clock.time,
                get_status_fn=scripted(['QR_REQUIRED']),
                connect_fn=lambda: 'QR_REQUIRED',
            )
        self.assertIn('QR_REQUIRED', str(ctx.exception))
        self.assertEqual(clock.sleeps, [])

    def test_logged_out_fails_fast(self):
        with self.assertRaises(preflight.PreflightError) as ctx:
            preflight.run_preflight(
                'http://wpp:3000', 'tok',
                sleep_fn=FakeClock().sleep, now_fn=FakeClock().time,
                get_status_fn=scripted(['LOGGED_OUT']),
                connect_fn=lambda: 'LOGGED_OUT',
            )
        self.assertIn('LOGGED_OUT', str(ctx.exception))

    def test_error_fails_fast(self):
        with self.assertRaises(preflight.PreflightError) as ctx:
            preflight.run_preflight(
                'http://wpp:3000', 'tok',
                sleep_fn=FakeClock().sleep, now_fn=FakeClock().time,
                get_status_fn=scripted(['ERROR']),
                connect_fn=lambda: 'ERROR',
            )
        self.assertIn('ERROR', str(ctx.exception))

    def test_timeout_fails(self):
        clock = FakeClock()
        with self.assertRaises(preflight.PreflightError) as ctx:
            preflight.run_preflight(
                'http://wpp:3000', 'tok',
                max_wait_s=10, poll_interval_s=5,
                sleep_fn=clock.sleep, now_fn=clock.time,
                get_status_fn=scripted(['CONNECTING']),
                connect_fn=lambda: 'CONNECTING',
            )
        self.assertIn('did not reach CONNECTED', str(ctx.exception))

    def test_transport_retries_until_deadline(self):
        clock = FakeClock()
        status = preflight.run_preflight(
            'http://wpp:3000', 'tok',
            max_wait_s=30, poll_interval_s=5,
            sleep_fn=clock.sleep, now_fn=clock.time,
            get_status_fn=scripted([
                preflight.PreflightError('unreachable'),
                preflight.PreflightError('unreachable'),
                'CONNECTED',
            ]),
            connect_fn=lambda: 'CONNECTED',
        )
        self.assertEqual(status, 'CONNECTED')
        self.assertTrue(len(clock.sleeps) >= 2)


if __name__ == '__main__':
    unittest.main()
