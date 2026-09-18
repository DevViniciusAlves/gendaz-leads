'use strict';

function createAuthStore({ config: cfg, postgresFactory, fileFactory } = {}) {
  if (cfg.authStore === 'postgres') {
    const { createPostgresAuthStore } = require('./postgresAuthStore');
    const factory = postgresFactory || createPostgresAuthStore;
    return factory({ sessionId: cfg.sessionId, encryptionKey: cfg.encryptionKey });
  }
  const { createFileAuthStore } = require('./authStore');
  const factory = fileFactory || createFileAuthStore;
  return factory({ baseDir: cfg.authDataDir, sessionId: cfg.sessionId });
}

module.exports = { createAuthStore };
