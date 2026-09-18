'use strict';

const { Pool } = require('pg');
const { encryptJson, decryptJson } = require('./cryptoStore');
const { safeAuthStateObj } = require('./cryptoStore');

// Persistencia do auth state Baileys em PostgreSQL (tabelas criadas pela migration V2/V3 do backend).
//   whatsapp_auth_sessions(session_id, payload, registered, created_at, updated_at)
//   whatsapp_auth_keys(session_id, key_type, key_hash, payload, updated_at)

// Todo payload sensivel e armazenado criptografado (AES-256-GCM). Nunca logar conteudo descriptografado.

function createPostgresAuthStore({ pool, sessionId, encryptionKey }) {
  const db = pool || new Pool({ connectionString: process.env.WHATSAPP_DATABASE_URL });

  async function readSession() {
    const { rows } = await db.query(
      'SELECT payload, registered FROM whatsapp_auth_sessions WHERE session_id = $1',
      [sessionId]
    );
    if (rows.length === 0) return { creds: null, registered: false };
    try {
      const creds = decryptJson(encryptionKey, rows[0].payload);
      return { creds: safeAuthStateObj(creds), registered: !!rows[0].registered };
    } catch (e) {
      throw new Error(`Falha ao descriptografar sessao ${sessionId}: ${e.message}`);
    }
  }

  async function writeSession(creds, registered) {
    const payload = encryptJson(encryptionKey, creds);
    await db.query(
      `INSERT INTO whatsapp_auth_sessions(session_id, payload, registered, created_at, updated_at)
       VALUES ($1, $2, $3, now(), now())
       ON CONFLICT (session_id) DO UPDATE SET payload = EXCLUDED.payload, registered = EXCLUDED.registered, updated_at = now()`,
      [sessionId, payload, !!registered]
    );
  }

  async function readKeys() {
    const { rows } = await db.query(
      'SELECT key_type, key_hash, payload FROM whatsapp_auth_keys WHERE session_id = $1',
      [sessionId]
    );
    const keys = {};
    for (const row of rows) {
      try {
        const value = decryptJson(encryptionKey, row.payload);
        if (!keys[row.key_type]) keys[row.key_type] = {};
        keys[row.key_type][row.key_hash] = value;
      } catch (_) {
        // chave corrompida: ignora individualmente para nao derrubar o restore
      }
    }
    return keys;
  }

  async function writeKey(type, id, value) {
    if (value === undefined || value === null) {
      await db.query(
        'DELETE FROM whatsapp_auth_keys WHERE session_id = $1 AND key_type = $2 AND key_hash = $3',
        [sessionId, type, String(id)]
      );
      return;
    }
    const payload = encryptJson(encryptionKey, value);
    await db.query(
      `INSERT INTO whatsapp_auth_keys(session_id, key_type, key_hash, payload, updated_at)
       VALUES ($1, $2, $3, $4, now())
       ON CONFLICT (session_id, key_type, key_hash) DO UPDATE SET payload = EXCLUDED.payload, updated_at = now()`,
      [sessionId, type, String(id), payload]
    );
  }

  async function clear() {
    await db.query('DELETE FROM whatsapp_auth_keys WHERE session_id = $1', [sessionId]);
    await db.query('DELETE FROM whatsapp_auth_sessions WHERE session_id = $1', [sessionId]);
  }

  // Adaptador para o formato esperado pelo Baileys: { state: {creds, keys}, saveCreds }
  // Usa safeAuthStateObj para preservar Buffers, Signal Keys e app-state-sync-key.
  async function loadBaileysAuthState() {
    const { creds, registered } = await readSession();
    const storedKeys = await readKeys();
    const { initAuthCreds } = require('@whiskeysockets/baileys');
    const stateCreds = creds || initAuthCreds();

    // Aplica safeAuthStateObj para garantir compatibilidade de tipos (Buffers, Signal Keys, app-state-sync-key)
    const adaptedCreds = safeAuthStateObj(stateCreds);

    const keys = {
      get: (type, ids) => {
        const out = {};
        const store = storedKeys[type] || {};
        for (const id of ids) {
          if (store[id] !== undefined) out[id] = store[id];
        }
        return out;
      },
      set: async (data) => {
        for (const type of Object.keys(data || {})) {
          for (const id of Object.keys(data[type] || {})) {
            const value = data[type][id];
            await writeKey(type, id, value);
            if (!storedKeys[type]) storedKeys[type] = {};
            if (value === undefined || value === null) delete storedKeys[type][id];
            else storedKeys[type][id] = value;
          }
        }
      },
    };

    const saveCreds = async () => {
      await writeSession(adaptedCreds, !!adaptedCreds.registered);
    };

    return { state: { creds: adaptedCreds, keys }, saveCreds, registered };
  }

  return { readSession, writeSession, readKeys, writeKey, clear, loadBaileysAuthState, pool: db };
}

module.exports = { createPostgresAuthStore };