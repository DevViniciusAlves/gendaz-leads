'use strict';

// Fabrica do socket Baileys isolada do gerenciamento de sessao (facilita mock em testes).
function createSocket({ baileys, authState, logger }) {
  const { makeWASocket } = baileys;
  const sock = makeWASocket({
    auth: authState,
    logger: logger || require('pino')({ level: 'silent' }).child({}),
    printQRInTerminal: false,
    browser: ['Gendaz Leads', 'Chrome', '1.0'],
  });
  return sock;
}

module.exports = { createSocket };
