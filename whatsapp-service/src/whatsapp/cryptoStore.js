'use strict';

const crypto = require('crypto');
const { resolveKeyBytes } = require('../config');

// Criptografia autenticada AES-256-GCM para o auth state do Baileys.
// Formato: base64(iv 12B || authTag 16B || ciphertext).
function encryptJson(keySource, obj) {
  const key = resolveKeyBytes(keySource);
  const iv = crypto.randomBytes(12);
  const cipher = crypto.createCipheriv('aes-256-gcm', key, iv);
  const plaintext = Buffer.from(JSON.stringify(obj), 'utf8');
  const ciphertext = Buffer.concat([cipher.update(plaintext), cipher.final()]);
  const tag = cipher.getAuthTag();
  return Buffer.concat([iv, tag, ciphertext]).toString('base64');
}

function decryptJson(keySource, payload) {
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

module.exports = { encryptJson, decryptJson };
