'use strict';

const { config, validateConfig } = require('./config');
const { createApp } = require('./app');

async function main() {
  validateConfig();
  const { app, sessionManager, authStore } = createApp({ cfg: config });
  const server = app.listen(config.port, () => {
    // Nunca logar token, DATABASE_URL ou chave de criptografia.
    console.log(`whatsapp-service ouvindo na porta ${config.port} (sessao: ${config.sessionId}, store: ${config.authStore})`);
  });
  // Boot: tenta restaurar sessao registrada do banco/arquivo.
  // Restore não deve gerar novo QR se auth válida existe.
  await sessionManager.restoreIfRegistered();

  // Shutdown NÃO é logout: parar reconnect, bloquear novos connects,
  // flush writes, socket.end. NUNCA logout nem authStore.clear. Fechar Pool.
  let shuttingDown = false;
  async function gracefulShutdown(signal) {
    if (shuttingDown) return;
    shuttingDown = true;
    console.log(`Shutdown signal ${signal} recebido. Iniciando encerramento...`);

    // Watchdog de 10s
    const watchdog = setTimeout(() => {
      console.error('Shutdown demorou demais. Forçando saída.');
      process.exit(1);
    }, 10000);
    watchdog.unref();

    try {
      await sessionManager.shutdown();
    } catch (e) {
      console.error('Erro no shutdown do sessionManager:', e);
    }
    
    try {
      await new Promise((resolve) => server.close(resolve));
    } catch (e) {
      console.error('Erro ao fechar servidor HTTP:', e);
    }

    try {
      const pool = authStore && authStore.pool;
      if (pool && typeof pool.end === 'function') {
        await pool.end();
      }
    } catch (e) {
      console.error('Erro ao fechar pool de conexoes:', e);
    }
    
    console.log('Shutdown concluído.');
    process.exit(0);
  }
  process.on('SIGTERM', () => gracefulShutdown('SIGTERM'));
  process.on('SIGINT', () => gracefulShutdown('SIGINT'));

  return server;
}

if (require.main === module) {
  main().catch((e) => {
    console.error(`Falha ao iniciar whatsapp-service: ${e.message}`);
    process.exit(1);
  });
}

module.exports = { main };
