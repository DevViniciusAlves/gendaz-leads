'use strict';

const crypto = require('crypto');
const { resolveKeyBytes } = require('../config');

const { BufferJSON } = require('@whiskeysockets/baileys');

/**
 * Criptografia AES-256-GCM para payloads salvos no PostgreSQL.
 * Formato: base64(iv 12B || authTag 16B || ciphertext)
 * O objeto deve já vir da serialização BufferJSON (stringify).
 */
function encryptAuthState(keySource, obj) {
  // Serializa usando BufferJSON se o objeto contém Buffers
  const plaintext = Buffer.from(JSON.stringify(obj, BufferJSON.replacer), 'utf8');
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
  return JSON.parse(plaintext.toString('utf8'), BufferJSON.reviver);
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

module.exports = {
  encryptJson,
  decryptJson,
  encryptAuthState,
  decryptAuthState
};