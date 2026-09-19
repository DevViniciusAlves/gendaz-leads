'use strict';

const { Pool } = require('pg');
const { encryptJson, decryptJson } = require('./cryptoStore');
const { initAuthCreds, WAProto } = require('@whiskeysockets/baileys');

// Persistencia do auth state Baileys em PostgreSQL (tabelas criadas pela migration V2/V3 do backend).
//   whatsapp_auth_sessions(session_id, payload, registered, created_at, updated_at)
//   whatsapp_auth_keys(session_id, key_type, key_hash, payload, updated_at)

function createPostgresAuthStore({ pool, sessionId, encryptionKey, logger }) {
  const db = pool || new Pool({ connectionString: process.env.WHATSAPP_DATABASE_URL });
  const log = logger || console;

  async function readSession() {
    const { rows } = await db.query(
      'SELECT payload, registered FROM whatsapp_auth_sessions WHERE session_id = $1',
      [sessionId]
    );
    if (rows.length === 0) return { creds: null, registered: false };
    try {
      const creds = decryptJson(encryptionKey, rows[0].payload);
      return { creds, registered: !!rows[0].registered };
    } catch (e) {
      log.warn && log.warn('legacy_auth_incompatible=true');
      return { creds: null, registered: false };
    }
  }

  async function writeSession(creds, registered) {
    const client = await db.connect();
    try {
      await client.query('BEGIN');
      const payload = encryptJson(encryptionKey, creds);
      await client.query(
        `INSERT INTO whatsapp_auth_sessions(session_id, payload, registered, created_at, updated_at)
         VALUES ($1, $2, $3, now(), now())
         ON CONFLICT (session_id) DO UPDATE SET payload = EXCLUDED.payload, registered = EXCLUDED.registered, updated_at = now()`,
        [sessionId, payload, !!registered]
      );
      await client.query('COMMIT');
    } catch (err) {
      try { await client.query('ROLLBACK'); } catch (_) {}
      throw err;
    } finally {
      client.release();
    }
  }

  async function saveAndFlush(creds, registered) {
    await writeSession(creds, registered);
  }

  async function clear() {
    const client = await db.connect();
    try {
      await client.query('BEGIN');
      await client.query('DELETE FROM whatsapp_auth_keys WHERE session_id = $1', [sessionId]);
      await client.query('DELETE FROM whatsapp_auth_sessions WHERE session_id = $1', [sessionId]);
      await client.query('COMMIT');
    } catch (err) {
      try { await client.query('ROLLBACK'); } catch (_) {}
      throw err;
    } finally {
      client.release();
    }
  }

  async function loadBaileysAuthState() {
    const { creds, registered } = await readSession();
    const stateCreds = creds || initAuthCreds();

    const keys = {
      get: async (type, ids) => {
        const out = {};
        for (const id of ids) {
          const { rows } = await db.query(
            'SELECT payload FROM whatsapp_auth_keys WHERE session_id = $1 AND key_type = $2 AND key_hash = $3',
            [sessionId, type, String(id)]
          );
          if (rows.length > 0) {
            try {
              let val = decryptJson(encryptionKey, rows[0].payload);
              if (type === 'app-state-sync-key' && val) {
                val = WAProto.Message.AppStateSyncKeyData.fromObject(val);
              }
              out[id] = val;
            } catch (err) {
              log.warn && log.warn(`Failed to decrypt key ${type}-${id}`);
            }
          }
        }
        return out;
      },
      set: async (data) => {
        const client = await db.connect();
        try {
          await client.query('BEGIN');
          for (const type of Object.keys(data || {})) {
            for (const id of Object.keys(data[type] || {})) {
              const value = data[type][id];
              if (value === undefined || value === null) {
                await client.query(
                  'DELETE FROM whatsapp_auth_keys WHERE session_id = $1 AND key_type = $2 AND key_hash = $3',
                  [sessionId, type, String(id)]
                );
              } else {
                const payload = encryptJson(encryptionKey, value);
                await client.query(
                  `INSERT INTO whatsapp_auth_keys(session_id, key_type, key_hash, payload, updated_at)
                   VALUES ($1, $2, $3, $4, now())
                   ON CONFLICT (session_id, key_type, key_hash) DO UPDATE SET payload = EXCLUDED.payload, updated_at = now()`,
                  [sessionId, type, String(id), payload]
                );
              }
            }
          }
          await client.query('COMMIT');
        } catch (err) {
          try { await client.query('ROLLBACK'); } catch (_) {}
          log.warn && log.warn('Failed to save keys, rolled back:', err.message);
          throw err;
        } finally {
          client.release();
        }
      },
    };

    const saveCreds = async () => {
      await saveAndFlush(stateCreds, !!stateCreds.registered);
    };

    return { state: { creds: stateCreds, keys }, saveCreds, registered, safeAuthStateObj };
  }

  // Compatibilidade de tipos após restore (Baileys espera objetos WAProto).
  // Aqui apenas garante passthrough; a conversão específica de app-state-sync-key
  // ocorre no keys.get via WAProto.Message.AppStateSyncKeyData.fromObject.
  function safeAuthStateObj(authState) {
    return authState;
  }

  return { readSession, writeSession, saveAndFlush, clear, loadBaileysAuthState, pool: db, safeAuthStateObj };
}

module.exports = { createPostgresAuthStore };