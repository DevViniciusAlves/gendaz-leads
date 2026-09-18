'use strict';

const express = require('express');

function healthRouter() {
  const router = express.Router();
  // Saude simples: nunca expor sessao, auth state ou segredo.
  router.get('/health', (req, res) => res.json({ status: 'UP' }));
  return router;
}

module.exports = { healthRouter };
