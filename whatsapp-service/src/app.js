'use strict';

const express = require('express');
const { config } = require('./config');
const { internalAuth } = require('./middleware/auth');
const { healthRouter } = require('./routes/health');
const { sessionsRouter } = require('./routes/sessions');
const { messagesRouter } = require('./routes/messages');
const { createSessionManager } = require('./whatsapp/sessionManager');
const { createAuthStore } = require('./whatsapp/authStoreFactory');

function createApp({ sessionManager, cfg } = {}) {
  const effectiveConfig = cfg || config;
  const app = express();
  app.disable('x-powered-by');
  app.use(express.json({ limit: '64kb' }));

  const auth = internalAuth(effectiveConfig);
  // Só cria authStore real quando o caller não injetou um sessionManager (testes).
  let store = null;
  let manager = sessionManager;
  if (!manager) {
    store = createAuthStore({ config: effectiveConfig });
    manager = createSessionManager({ config: effectiveConfig, authStore: store });
  }

  app.use(healthRouter());
  app.use(sessionsRouter({ sessionManager: manager, config: effectiveConfig, auth }));
  app.use(messagesRouter({ sessionManager: manager, config: effectiveConfig, auth }));

  // 404 padrao sem vazar detalhes.
  app.use((req, res) => res.status(404).json({ error: 'not_found' }));

  return { app, sessionManager: manager, authStore: store };
}

module.exports = { createApp };
