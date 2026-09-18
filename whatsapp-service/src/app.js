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
  const manager = sessionManager || createSessionManager({
    config: effectiveConfig,
    authStore: createAuthStore({ config: effectiveConfig }),
  });

  app.use(healthRouter());
  app.use(sessionsRouter({ sessionManager: manager, config: effectiveConfig, auth }));
  app.use(messagesRouter({ sessionManager: manager, config: effectiveConfig, auth }));

  // 404 padrao sem vazar detalhes.
  app.use((req, res) => res.status(404).json({ error: 'not_found' }));

  return { app, sessionManager: manager };
}

module.exports = { createApp };
