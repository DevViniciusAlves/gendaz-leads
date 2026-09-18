'use strict';

const crypto = require('crypto');
const { resolveKeyBytes } = require('../config');

// --- Serialização que preserva Buffers como base64 (usado pelo Baileys) ---
// JSON.stringify puro NÃO deve ser usado para auth state do Baileys pois perde Buffers,
// Signal Keys e app-state-sync-key.

function bufferToString(buf) {
  if (buf == null) return null;
  if (typeof buf === 'string') return buf;
  if (Buffer.isBuffer(buf)) return buf.toString('base64');
  return String(buf);
}

function stringToBuffer(str) {
  if (str == null) return null;
  if (typeof str === 'string') return Buffer.from(str, 'base64');
  return Buffer.from(str);
}

// Criptografia AES-256-GCM para payloads salvos no PostgreSQL.
// Formato: base64(iv 12B || authTag 16B || ciphertext)
function encryptAuthState(keySource, obj) {
  const key = resolveKeyBytes(keySource);
  const iv = crypto.randomBytes(12);
  const cipher = crypto.createCipheriv('aes-256-gcm', key, iv);
  const plaintext = Buffer.from(JSON.stringify(obj), 'utf8');
  const ciphertext = Buffer.concat([cipher.update(plaintext), cipher.final()]);
  const tag = cipher.getAuthTag();
  return Buffer.concat([iv, tag, ciphertext]).toString('base64');
}

function decryptAuthState(keySource, payload) {
  const key = resolveKeyBytes(keySource);
  const buf = Buffer.from(String(payload), 'base64');
  if (buf.length < 12 + 16 + 1) throw new Error('payload criptografado invalido');
  const iv = buf.subarray(0, 12);
  const tag = buf.subarray(12, 28);
  const ciphertext = buf.subarray(28);
  const decipher = crypto.createDecipheriv('aes-256-gcm', key, iv);
  decipher.setAuthTag(tag);
  const plaintext = Buffer.concat([decipher.update(ciphertext), decipher.final()]);
  return JSON.parse(plaintext.toString('utf8'));
}

// --- Funções de compatibilidade para testes (JSON direto) ---
// Estas usam JSON.stringify/parse direto. Para auth state do Baileys,
// use encryptAuthState/decryptAuthState ou serializeForStorage/deserializeFromStorage.

/**
 * Criptografa um objeto JSON usando AES-256-GCM.
 * @param {string} keySource - chave de 32 bytes (base64/hex/string)
 * @param {any} obj - objeto a ser criptografado
 * @returns {string} payload criptografado em base64
 */
function encryptJson(keySource, obj) {
  return encryptAuthState(keySource, obj);
}

/**
 * Descriptografa um payload AES-256-GCM.
 * @param {string} keySource - chave de 32 bytes
 * @param {string} payload - payload base64 retornado por encryptJson
 * @returns {any} objeto descriptografado
 */
function decryptJson(keySource, payload) {
  return decryptAuthState(keySource, payload);
}

/**
 * Serializa um objeto preservando Buffers como base64.
 * Útil para auth state do Baileys que contém Signal Keys, protobuf, etc.
 * NÃO use JSON.stringify puro para esse caso.
 */
function serializeForStorage(obj) {
  if (obj === null || obj === undefined) return String(obj);
  if (typeof obj === 'boolean' || typeof obj === 'number' || typeof obj === 'string') return obj;
  if (Buffer.isBuffer(obj)) return Buffer(obj).toString('base64');
  if (Array.isArray(obj)) return '[' + obj.map(serializeForStorage).join(',') + ']';
  if (typeof obj === 'object') {
    const entries = [];
    for (const key of Object.keys(obj)) {
      const val = serializeForStorage(obj[key]);
      entries.push(JSON.stringify(key) + ':' + val);
    }
    return '{' + entries.join(',') + '}';
  }
  return String(obj);
}

/**
 * Faz o parse da serialização preserve-Base64 do serializeForStorage.
 */
function deserializeFromStorage(text) {
  if (text === null || text === undefined) return text;
  if (typeof text !== 'string') return text;
  try {
    return parseFromStorage(JSON.parse(text));
  } catch (_) {
    return JSON.parse(text);
  }
}

/**
 * Parsing interno do formato serializeForStorage.
 */
function parseFromStorage(parsed) {
  if (parsed === null || parsed === undefined) return parsed;
  if (typeof parsed !== 'object') return parsed;
  const result = {};
  for (const key of Object.keys(parsed)) {
    const value = parsed[key];
    if (typeof value === 'string' && /^[A-Za-z0-9+/]{8,}==*$/.test(value) && value.length > 20) {
      try { result[key] = Buffer.from(value, 'base64'); } catch (_) { result[key] = value; }
    } else {
      result[key] = parseFromStorage(value);
    }
  }
  return result;
}

/**
 * Garante que credenciais com Buffer, Signal Keys e app-state-sync-key
 * sobrevivam ao ciclo persist/ restore.
 */
function safeAuthStateObj(raw) {
  if (!raw) return raw;
  const out = { ...raw };
  if (!out.keys) out.keys = {};
  if (!out.creds) out.creds = {};
  if (out['app-state-sync-key'] === undefined) {
    out['app-state-sync-key'] = {};
  }
  return out;
}

module.exports = {
  encryptJson,
  decryptJson,
  encryptAuthState,
  decryptAuthState,
  serializeForStorage,
  deserializeFromStorage,
  parseFromStorage,
  safeAuthStateObj,
  bufferToString,
  stringToBuffer,
};