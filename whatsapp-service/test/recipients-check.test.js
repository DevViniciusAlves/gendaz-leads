'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');

const { createSessionManager } = require('../src/whatsapp/sessionManager');
const { createApp } = require('../src/app');

function testConfig(over = {}) {
  return {
    sessionId: 'gendaz-leads',
    internalToken: 'tok',
    authStore: 'file',
    reconnectBaseDelayMs: 10,
    reconnectMaxDelayMs: 50,
    reconnectMaxAttempts: 2,
    maxTextLength: 100,
    idempotencyTtlMs: 60000,
    ...over,
  };
}

function fakeAuthStore() {
  return {
    loadBaileysAuthState: async () => { throw new Error('no baileys in test'); },
    clear: async () => {},
  };
}

test('checkRecipient: invalid recipient -> 400 invalid_recipient', async () => {
  const m = createSessionManager({ config: testConfig(), authStore: fakeAuthStore(), baileysLib: {} });
  m._injectSocket({ onWhatsApp: async () => [{ exists: true }], sendMessage: async () => ({ key: { id: 'x' } }) });
  await assert.rejects(() => m.checkRecipient({ recipient: 'abc' }),
    (e) => e.code === 'invalid_recipient' && e.httpStatus === 400);
  await assert.rejects(() => m.checkRecipient({ recipient: '123' }),
    (e) => e.code === 'invalid_recipient' && e.httpStatus === 400);
});

test('checkRecipient: session disconnected -> 409 session_not_connected', async () => {
  const m = createSessionManager({ config: testConfig(), authStore: fakeAuthStore(), baileysLib: {} });
  // sem socket injetado => NOT_CONNECTED
  await assert.rejects(() => m.checkRecipient({ recipient: '5565999999999' }),
    (e) => e.code === 'session_not_connected' && e.httpStatus === 409);
});

test('checkRecipient: onWhatsApp exists=true -> { exists: true } sem sendMessage', async () => {
  const m = createSessionManager({ config: testConfig(), authStore: fakeAuthStore(), baileysLib: {} });
  let sentCalled = false;
  m._injectSocket({
    onWhatsApp: async (jid) => {
      assert.ok(jid.includes('5565999999999'));
      return [{ jid, exists: true }];
    },
    sendMessage: async () => { sentCalled = true; return { key: { id: 'x' } }; },
  });
  const out = await m.checkRecipient({ recipient: '5565999999999' });
  assert.equal(out.recipient, '5565999999999');
  assert.equal(out.exists, true);
  assert.equal(sentCalled, false);
});

test('checkRecipient: onWhatsApp exists=false -> { exists: false } sem sendMessage', async () => {
  const m = createSessionManager({ config: testConfig(), authStore: fakeAuthStore(), baileysLib: {} });
  let sentCalled = false;
  m._injectSocket({
    onWhatsApp: async () => [{ exists: false }],
    sendMessage: async () => { sentCalled = true; return { key: { id: 'x' } }; },
  });
  const out = await m.checkRecipient({ recipient: '5565999999999' });
  assert.equal(out.exists, false);
  assert.equal(sentCalled, false);
});

test('checkRecipient: lookup indisponivel -> 502 recipient_check_unavailable', async () => {
  const m = createSessionManager({ config: testConfig(), authStore: fakeAuthStore(), baileysLib: {} });
  m._injectSocket({ sendMessage: async () => ({ key: { id: 'x' } }) });
  await assert.rejects(() => m.checkRecipient({ recipient: '5565999999999' }),
    (e) => e.code === 'recipient_check_unavailable' && e.httpStatus === 502);
});

test('checkRecipient: onWhatsApp throws -> 502 recipient_check_failed', async () => {
  const m = createSessionManager({ config: testConfig(), authStore: fakeAuthStore(), baileysLib: {} });
  m._injectSocket({
    onWhatsApp: async () => { throw new Error('lookup down'); },
    sendMessage: async () => ({ key: { id: 'x' } }),
  });
  await assert.rejects(() => m.checkRecipient({ recipient: '5565999999999' }),
    (e) => e.code === 'recipient_check_failed' && e.httpStatus === 502);
});

test('sendText continua funcionando com checkRecipient (exists=true envia)', async () => {
  const m = createSessionManager({ config: testConfig(), authStore: fakeAuthStore(), baileysLib: {} });
  m._injectSocket({
    onWhatsApp: async () => [{ exists: true }],
    sendMessage: async () => ({ key: { id: 'mid-ok' } }),
  });
  const ok = await m.sendText({ recipient: '5565999999999', text: 'oi', requestId: 'chk-1' });
  assert.equal(ok.status, 'sent');
  assert.equal(ok.messageId, 'mid-ok');
});

test('HTTP recipients/check: sem auth bloqueado; com auth 200 true/false; 400/409/502', async () => {
  const m = createSessionManager({ config: testConfig(), authStore: fakeAuthStore(), baileysLib: {} });
  m._injectSocket({
    onWhatsApp: async (jid) => (jid.startsWith('5565999999999') ? [{ exists: true }] : [{ exists: false }]),
    sendMessage: async () => ({ key: { id: 'x' } }),
  });
  const cfg = testConfig();
  const { app } = createApp({ sessionManager: m, cfg });
  const server = app.listen(0);
  await new Promise((r) => server.on('listening', r));
  const port = server.address().port;
  try {
    const noAuth = await fetch(`http://127.0.0.1:${port}/internal/whatsapp/session/recipients/check`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ recipient: '5565999999999' }),
    });
    assert.equal(noAuth.status, 401);

    const okTrue = await fetch(`http://127.0.0.1:${port}/internal/whatsapp/session/recipients/check`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', Authorization: 'Bearer tok' },
      body: JSON.stringify({ recipient: '5565999999999' }),
    });
    assert.equal(okTrue.status, 200);
    const bodyTrue = await okTrue.json();
    assert.equal(bodyTrue.recipient, '5565999999999');
    assert.equal(bodyTrue.exists, true);

    const okFalse = await fetch(`http://127.0.0.1:${port}/internal/whatsapp/session/recipients/check`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', Authorization: 'Bearer tok' },
      body: JSON.stringify({ recipient: '5511999999999' }),
    });
    assert.equal(okFalse.status, 200);
    assert.equal((await okFalse.json()).exists, false);

    const bad = await fetch(`http://127.0.0.1:${port}/internal/whatsapp/session/recipients/check`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', Authorization: 'Bearer tok' },
      body: JSON.stringify({ recipient: 'abc' }),
    });
    assert.equal(bad.status, 400);
    assert.equal((await bad.json()).error, 'invalid_recipient');
  } finally {
    server.close();
  }

  // 409 quando desconectado
  const m2 = createSessionManager({ config: testConfig(), authStore: fakeAuthStore(), baileysLib: {} });
  const { app: app2 } = createApp({ sessionManager: m2, cfg });
  const server2 = app2.listen(0);
  await new Promise((r) => server2.on('listening', r));
  try {
    const port2 = server2.address().port;
    const disc = await fetch(`http://127.0.0.1:${port2}/internal/whatsapp/session/recipients/check`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', Authorization: 'Bearer tok' },
      body: JSON.stringify({ recipient: '5565999999999' }),
    });
    assert.equal(disc.status, 409);
    assert.equal((await disc.json()).error, 'session_not_connected');
  } finally {
    server2.close();
  }

  // 502 quando lookup quebra
  const m3 = createSessionManager({ config: testConfig(), authStore: fakeAuthStore(), baileysLib: {} });
  m3._injectSocket({
    onWhatsApp: async () => { throw new Error('down'); },
    sendMessage: async () => ({ key: { id: 'x' } }),
  });
  const { app: app3 } = createApp({ sessionManager: m3, cfg });
  const server3 = app3.listen(0);
  await new Promise((r) => server3.on('listening', r));
  try {
    const port3 = server3.address().port;
    const fail = await fetch(`http://127.0.0.1:${port3}/internal/whatsapp/session/recipients/check`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', Authorization: 'Bearer tok' },
      body: JSON.stringify({ recipient: '5565999999999' }),
    });
    assert.equal(fail.status, 502);
    assert.equal((await fail.json()).error, 'recipient_check_failed');
  } finally {
    server3.close();
  }
});
