'use strict';

const express = require('express');

const VALID_SESSION_PATTERN = /^[a-z0-9][a-z0-9-_]{0,63}$/i;

function sessionsRouter({ sessionManager, config, auth }) {
  const router = express.Router();
  router.use(auth);

  function checkSessionParam(req, res, next) {
    if (!req.params.sessionId) return next();
    if (req.params.sessionId !== config.sessionId || !VALID_SESSION_PATTERN.test(req.params.sessionId)) {
      return res.status(400).json({ error: 'invalid_session_id' });
    }
    return next();
  }

  async function handleConnect(req, res) {
    try {
      const status = await sessionManager.connect();
      return res.json({ sessionId: config.sessionId, ...status });
    } catch (e) {
      return res.status(502).json({ error: 'connect_failed' });
    }
  }

  async function handleStatus(req, res) {
    return res.json({ sessionId: config.sessionId, ...sessionManager.publicStatus() });
  }

  async function handleQr(req, res) {
    const { qr, updatedAt, hasQr } = sessionManager.getQr();
    if (!hasQr) return res.status(404).json({ error: 'qr_not_available', hasQr: false });
    return res.json({ qr, updatedAt });
  }

  async function handleLogout(req, res) {
    const status = await sessionManager.logout();
    return res.json({ sessionId: config.sessionId, ...status });
  }

  async function handleReset(req, res) {
    const status = await sessionManager.resetSession();
    return res.json({ sessionId: config.sessionId, ...status });
  }

  // Rotas canonicas (sessao unica, sem companyId na URL).
  router.post('/internal/whatsapp/session/connect', handleConnect);
  router.get('/internal/whatsapp/session/status', handleStatus);
  router.get('/internal/whatsapp/session/qr', handleQr);
  router.post('/internal/whatsapp/session/logout', handleLogout);
  router.post('/internal/whatsapp/session/reset', handleReset);

  // Alias compativel com arquitetura anterior /sessions/{sessionId}/...
  router.post('/internal/whatsapp/sessions/:sessionId/connect', checkSessionParam, handleConnect);
  router.get('/internal/whatsapp/sessions/:sessionId/status', checkSessionParam, handleStatus);
  router.get('/internal/whatsapp/sessions/:sessionId/qr', checkSessionParam, handleQr);
  router.post('/internal/whatsapp/sessions/:sessionId/logout', checkSessionParam, handleLogout);
  router.post('/internal/whatsapp/sessions/:sessionId/reset', checkSessionParam, handleReset);

  return router;
}

module.exports = { sessionsRouter };
