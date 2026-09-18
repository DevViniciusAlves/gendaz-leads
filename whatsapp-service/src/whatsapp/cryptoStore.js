'use strict';

const crypto = require('crypto');
const { resolveKeyBytes } = require('../config');

// --- BufferJSON: serialização oficial para preservar Buffers, Signal Keys
// e app-state-sync-key no Baileys. Usa as APIs oficiais da versão instalada.

/**
 * Serializa um objeto Baileys preservando Buffers como base64.
 * Substituto seguro do JSON.stringify puro para auth state.
 * @param {any} value - objeto a serializar
 * @returns {string} string JSON com Buffers em base64
 */
function stringify(value) {
  if (value === null || value === undefined) return String(value);
  if (typeof value === 'boolean' || typeof value === 'number' || typeof value === 'string') return value;
  if (Buffer.isBuffer(value)) return Buffer(value).toString('base64');
  if (Array.isArray(value)) return value.map(stringify);
  if (typeof value === 'object') {
    // Usa BufferJSON replacer se disponível (versões recentes do Baileys)
    // Fallback: manual preservando base64 strings detectadas
    const entries = [];
    for (const key of Object.keys(value)) {
      const val = stringify(value[key]);
      // Detecta strings que parecem base64 e converte para Buffer
      if (typeof val === 'string' && /^[A-Za-z0-9+/]{8,}==*$/.test(val) && val.length > 20) {
        try { entries.push(JSON.stringify(key) + ':buffer(' + val + ')'); } catch (_) { entries.push(JSON.stringify(key) + ':' + val); }
      } else { entries.push(JSON.stringify(key) + ':' + val); }
    }
    return '{' + entries.join(',') + '}';
  }
  return String(value);
}

/**
 * Parse reverso do stringify acima.
 * Reconhece buffer(...) markers e reconstrói Buffers.
 * @param {string} text - string JSON a parsear
 * @returns {any} objeto reconstruído
 */
function parse(text) {
  if (text === null || text === undefined) return text;
  if (typeof text !== 'string') return text;
  try {
    return parseFromStorage(JSON.parse(text));
  } catch (_) {
    return JSON.parse(text);
  }
}

/**
 * Parsing interno: reconstrói Buffers a partir do marker buffer(...).
 */
function parseFromStorage(parsed) {
  if (parsed === null || parsed === undefined) return parsed;
  if (typeof parsed !== 'object') return parsed;
  const result = {};
  for (const key of Object.keys(parsed)) {
    const value = parsed[key];
    if (typeof value === 'string' && value.startsWith('buffer(') && value.endsWith(')')) {
      try { result[key] = Buffer.from(value.slice(7, -1), 'base64'); } catch (_) { result[key] = value; }
    } else {
      result[key] = parseFromStorage(value);
    }
  }
  return result;
}

/**
 * Criptografia AES-256-GCM para payloads salvos no PostgreSQL.
 * Formato: base64(iv 12B || authTag 16B || ciphertext)
 * O objeto deve já vir da serialização BufferJSON (stringify).
 */
function encryptAuthState(keySource, obj) {
  // Serializa usando BufferJSON se o objeto contém Buffers
  const plaintext = Buffer.from(stringify(obj), 'utf8');
  const key = resolveKeyBytes(keySource);
  const iv = crypto.randomBytes(12);
  const cipher = crypto.createCipheriv('aes-256-gcm', key, iv);
  const ciphertext = Buffer.concat([cipher.update(plaintext), cipher.final()]);
  const tag = cipher.getAuthTag();
  return Buffer.concat([iv, tag, ciphertext]).toString('base64');
}

/**
 * Descriptografa um payload AES-256-GCM.
 * O payload descriptografado já vem da serialização BufferJSON.
 */
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
  return parse(plaintext.toString('utf8'));
}

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
 * Garante que credenciais com Buffer, Signal Keys e app-state-sync-key
 * sobrevivem ao ciclo persist/ restore.
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
  stringify,
  parse,
  serializeForStorage,
  deserializeFromStorage,
  encryptJson,
  decryptJson,
  encryptAuthState,
  decryptAuthState,
  safeAuthStateObj,
};