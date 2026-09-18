'use strict';

require('dotenv').config();

function intEnv(name, fallback) {
  const raw = process.env[name];
  if (raw === undefined || raw === '') return fallback;
  const n = Number(raw);
  return Number.isFinite(n) ? n : fallback;
}

function strEnv(name, fallback = '') {
  const raw = process.env[name];
  return raw === undefined || raw === '' ? fallback : raw;
}

// Identificador de sessao estavel e centralizado (uso pessoal, sessao unica).
const SESSION_ID = strEnv('WHATSAPP_SESSION_ID', 'gendaz-leads');

const config = {
  port: intEnv('PORT', 3001),
  sessionId: SESSION_ID,
  internalToken: strEnv('WHATSAPP_INTERNAL_TOKEN', ''),
  authStore: strEnv('WHATSAPP_AUTH_STORE', 'file'), // file | postgres
  databaseUrl: strEnv('WHATSAPP_DATABASE_URL', ''),
  authDataDir: strEnv('WHATSAPP_AUTH_DATA_DIR', './data/auth'),
  encryptionKey: strEnv('WHATSAPP_AUTH_ENCRYPTION_KEY', ''),
  reconnectBaseDelayMs: intEnv('WHATSAPP_RECONNECT_BASE_DELAY_MS', 2000),
  reconnectMaxDelayMs: intEnv('WHATSAPP_RECONNECT_MAX_DELAY_MS', 60000),
  reconnectMaxAttempts: intEnv('WHATSAPP_RECONNECT_MAX_ATTEMPTS', 10),
  maxTextLength: intEnv('WHATSAPP_MAX_TEXT_LENGTH', 4000),
  idempotencyTtlMs: intEnv('WHATSAPP_IDEMPOTENCY_TTL_MS', 10 * 60 * 1000),
};

function validateConfig() {
  // Fail closed: sem token interno, o servico nao deve operar endpoints internos.
  if (!config.internalToken) {
    throw new Error('WHATSAPP_INTERNAL_TOKEN nao configurado. Recusando iniciar (fail closed).');
  }
  if (config.authStore === 'postgres') {
    if (!config.databaseUrl) {
      throw new Error('WHATSAPP_AUTH_STORE=postgres exige WHATSAPP_DATABASE_URL.');
    }
    if (!config.encryptionKey) {
      throw new Error('WHATSAPP_AUTH_STORE=postgres exige WHATSAPP_AUTH_ENCRYPTION_KEY.');
    }
    assertKeyLength(config.encryptionKey);
  }
}

function resolveKeyBytes(key) {
  // Aceita base64 (44 chars p/ 32 bytes), hex (64 chars) ou string bruta de 32 bytes.
  const trimmed = String(key).trim();
  try {
    if (/^[0-9a-fA-F]{64}$/.test(trimmed)) {
      return Buffer.from(trimmed, 'hex');
    }
    const b64 = Buffer.from(trimmed, 'base64');
    if (b64.length === 32) return b64;
  } catch (_) {
    // cai para o tratamento abaixo
  }
  const raw = Buffer.from(trimmed, 'utf8');
  if (raw.length === 32) return raw;
  throw new Error('WHATSAPP_AUTH_ENCRYPTION_KEY deve representar 32 bytes (base64 de 32 bytes, hex de 64 chars ou string de 32 bytes).');
}

function assertKeyLength(key) {
  resolveKeyBytes(key);
}

module.exports = { config, validateConfig, resolveKeyBytes };
