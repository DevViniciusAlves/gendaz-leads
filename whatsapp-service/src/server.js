'use strict';

const { config, validateConfig } = require('./config');
const { createApp } = require('./app');

async function main() {
  validateConfig();
  const { app, sessionManager } = createApp({ cfg: config });
  const server = app.listen(config.port, () => {
    // Nunca logar token, DATABASE_URL ou chave de criptografia.
    console.log(`whatsapp-service ouvindo na porta ${config.port} (sessao: ${config.sessionId}, store: ${config.authStore})`);
  });
  // Boot: tenta restaurar sessao registrada do banco/arquivo.
  try {
    await sessionManager.restoreIfRegistered();
  } catch (_) {}
  return server;
}

if (require.main === module) {
  main().catch((e) => {
    console.error(`Falha ao iniciar whatsapp-service: ${e.message}`);
    process.exit(1);
  });
}

module.exports = { main };
