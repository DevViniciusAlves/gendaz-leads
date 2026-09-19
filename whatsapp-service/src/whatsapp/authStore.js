'use strict';

const fs = require('fs/promises');
const path = require('path');

// Store local em arquivo (desenvolvimento). Em producao usar postgresAuthStore.
// Arquivos contem credenciais: nunca logar conteudo.
async function ensureDir(dir) {
  await fs.mkdir(dir, { recursive: true });
}

function filePaths(baseDir) {
  return {
    creds: path.join(baseDir, 'creds.json'),
    keys: path.join(baseDir, 'keys.json'),
  };
}

async function readJsonSafe(file, reviver) {
  try {
    const raw = await fs.readFile(file, 'utf8');
    return JSON.parse(raw, reviver);
  } catch (_) {
    return null;
  }
}

function createFileAuthStore({ baseDir, sessionId }) {
  const dir = path.join(baseDir, sessionId);
  const { creds: credsFile, keys: keysFile } = filePaths(dir);

  async function loadBaileysAuthState() {
    await ensureDir(dir);
    const { initAuthCreds, WAProto, BufferJSON } = require('@whiskeysockets/baileys');
    let creds = await readJsonSafe(credsFile, BufferJSON.reviver);
    if (!creds) creds = initAuthCreds();
    let storedKeys = (await readJsonSafe(keysFile, BufferJSON.reviver)) || {};

    const keys = {
      get: (type, ids) => {
        const out = {};
        const store = storedKeys[type] || {};
        for (const id of ids) {
          if (store[id] !== undefined) {
            let val = store[id];
            // Preservar AppStateSyncKeyData como objeto WAProto após restore.
            if (type === 'app-state-sync-key' && val) {
              try {
                val = WAProto.Message.AppStateSyncKeyData.fromObject(val);
              } catch (_) {}
            }
            out[id] = val;
          }
        }
        return out;
      },
      set: async (data) => {
        for (const type of Object.keys(data || {})) {
          for (const id of Object.keys(data[type] || {})) {
            const value = data[type][id];
            if (!storedKeys[type]) storedKeys[type] = {};
            if (value === undefined || value === null) delete storedKeys[type][id];
            else storedKeys[type][id] = value;
          }
        }
        await fs.writeFile(keysFile, JSON.stringify(storedKeys, BufferJSON.replacer), 'utf8');
      },
    };

    const saveCreds = async () => {
      await fs.writeFile(credsFile, JSON.stringify(creds, BufferJSON.replacer), 'utf8');
    };

    function safeAuthStateObj(authState) {
      return authState;
    }

    return { state: { creds, keys }, saveCreds, registered: !!creds.registered, safeAuthStateObj };
  }

  async function clear() {
    const { rm } = require('fs/promises');
    await rm(dir, { recursive: true, force: true });
  }

  function safeAuthStateObj(authState) {
    return authState;
  }

  return { loadBaileysAuthState, clear, safeAuthStateObj };
}

module.exports = { createFileAuthStore };
