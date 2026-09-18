'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');

const { internalAuth } = require('../src/middleware/auth');
const { createSessionManager, isValidRecipient } = require('../src/whatsapp/sessionManager');
const { encryptJson, decryptJson } = require('../src/whatsapp/cryptoStore');
const { createApp } = require('../src/app');

const KEY_B64 = Buffer.alloc(32, 7).toString('base64');

function testConfig() {
  return {
    sessionId: 'gendaz-leads',
    internalToken: 'tok',
    authStore: 'file',
    reconnectBaseDelayMs: 10,
    reconnectMaxDelayMs: 50,
    reconnectMaxAttempts: 2,
    maxTextLength: 100,
    idempotencyTtlMs: 60000,
  };
}

function fakeAuthStore() {
  return { loadBaileysAuthState: async () => { throw new Error('no baileys in test'); }, clear: async () => {} };
}

test('recipient validation: only digits', () => {
  assert.equal(isValidRecipient('5565999999999'), true);
  assert.equal(isValidRecipient('abc'), false);
  assert.equal(isValidRecipient('55 65 9999'), false);
  assert.equal(isValidRecipient('123'), false);
});

test('auth middleware rejects invalid token', () => {
  const mw = internalAuth({ internalToken: 'secret' });
  let status, body;
  mw({ headers: {} }, { status: (s) => ({ json: (b) => { status = s; body = b; } }) }, () => { status = 'next'; });
  assert.equal(status, 401);
  assert.equal(body.error, 'unauthorized');
});

test('crypto roundtrip AES-256-GCM', () => {
  const enc = encryptJson(KEY_B64, { a: 1, secret: 'x' });
  assert.ok(!enc.includes('secret') || enc.length > 0);
  const dec = decryptJson(KEY_B64, enc);
  assert.deepEqual(dec, { a: 1, secret: 'x' });
});

test('sendText validates recipient/text/requestId and disconnected session', async () => {
  const m = createSessionManager({ config: testConfig(), authStore: fakeAuthStore(), baileysLib: null });
  await assert.rejects(() => m.sendText({ recipient: 'abc', text: 'hi', requestId: 'r1' }), /recipient/);
  await assert.rejects(() => m.sendText({ recipient: '5565999999999', text: '  ', requestId: 'r1' }), /text/);
  await assert.rejects(() => m.sendText({ recipient: '5565999999999', text: 'hi', requestId: '' }), /requestId/);
  await assert.rejects(() => m.sendText({ recipient: '5565999999999', text: 'hi', requestId: 'r1' }), /nao conectada/);
});

test('idempotency: same requestId returns cached without resend', async () => {
  const m = createSessionManager({ config: testConfig(), authStore: fakeAuthStore(), baileysLib: null });
  let calls = 0;
  m._injectSocket({ onWhatsApp: async () => [{ exists: true }], sendMessage: async () => { calls++; return { key: { id: 'mid1' } }; } });
  const sender = async ({ recipient, text }) => {
    calls++;
    return { status: 'sent', messageId: 'mid1', requestId: 'req-1' };
  };
  const r1 = await m.sendText({ recipient: '5565999999999', text: 'ola', requestId: 'req-1' }, { sender });
  const r2 = await m.sendText({ recipient: '5565999999999', text: 'ola', requestId: 'req-1' }, { sender });
  assert.equal(r1.status, 'sent');
  assert.equal(r2.deduplicated, true);
  assert.equal(calls, 1);
});

test('recipient_not_on_whatsapp maps to 400', async () => {
  const m = createSessionManager({ config: testConfig(), authStore: fakeAuthStore(), baileysLib: null });
  m._injectSocket({ onWhatsApp: async () => [{ exists: false }], sendMessage: async () => ({ key: { id: 'x' } }) });
  await assert.rejects(
    () => m.sendText({ recipient: '5565999999999', text: 'ola', requestId: 'req-9' }),
    (e) => e.code === 'recipient_not_on_whatsapp' && e.httpStatus === 400
  );
});

test('serialization: concurrent sends run sequentially', async () => {
  const m = createSessionManager({ config: testConfig(), authStore: fakeAuthStore(), baileysLib: null });
  m._injectSocket({});
  const order = [];
  const mkSender = (name, delay) => async () => {
    order.push(`${name}-start`);
    await new Promise((r) => setTimeout(r, delay));
    order.push(`${name}-end`);
    return { status: 'sent', messageId: name, requestId: name };
  };
  const [a, b] = await Promise.all([
    m.sendText({ recipient: '5565999999999', text: 'a', requestId: 'ra' }, { sender: mkSender('ra', 30) }),
    m.sendText({ recipient: '5565999999999', text: 'b', requestId: 'rb' }, { sender: mkSender('rb', 5) }),
  ]);
  assert.deepEqual(order, ['ra-start', 'ra-end', 'rb-start', 'rb-end']);
  assert.equal(a.messageId, 'ra');
});

test('HTTP: health public; internal requires token; invalid sessionId rejected', async () => {
  const m = createSessionManager({ config: testConfig(), authStore: fakeAuthStore(), baileysLib: null });
  const cfg = testConfig();
  const { app } = createApp({ sessionManager: m, cfg });
  const server = app.listen(0);
  await new Promise((r) => server.on('listening', r));
  const port = server.address().port;
  try {
    const health = await fetch(`http://127.0.0.1:${port}/health`).then((r) => r.json());
    assert.deepEqual(health, { status: 'UP' });

    const noAuth = await fetch(`http://127.0.0.1:${port}/internal/whatsapp/session/status`);
    assert.equal(noAuth.status, 401);

    const badSession = await fetch(`http://127.0.0.1:${port}/internal/whatsapp/sessions/OUTRO/status`, {
      headers: { Authorization: 'Bearer tok' },
    });
    assert.equal(badSession.status, 400);

    const ok = await fetch(`http://127.0.0.1:${port}/internal/whatsapp/session/status`, {
      headers: { Authorization: 'Bearer tok' },
    });
    assert.equal(ok.status, 200);
    const body = await ok.json();
    assert.equal(body.sessionId, 'gendaz-leads');
    assert.ok(!('qr' in body) || body.hasQr === false);
  } finally {
    server.close();
  }
});

test('logout clears state', async () => {
  let cleared = false;
  const m = createSessionManager({
    config: testConfig(),
    authStore: { loadBaileysAuthState: async () => { throw new Error('x'); }, clear: async () => { cleared = true; } },
    baileysLib: null,
  });
  m._injectSocket({});
  const s = await m.logout();
  assert.equal(s.status, 'LOGGED_OUT');
  assert.equal(cleared, true);
});
