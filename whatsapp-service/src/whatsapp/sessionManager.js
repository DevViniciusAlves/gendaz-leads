'use strict';

const STATES = Object.freeze({
  NOT_CONNECTED: 'NOT_CONNECTED',
  CONNECTING: 'CONNECTING',
  QR_REQUIRED: 'QR_REQUIRED',
  CONNECTED: 'CONNECTED',
  DISCONNECTED: 'DISCONNECTED',
  LOGGED_OUT: 'LOGGED_OUT',
  ERROR: 'ERROR',
});

function isValidRecipient(recipient) {
  return typeof recipient === 'string' && /^[0-9]{8,15}$/.test(recipient);
}

function isValidRequestId(requestId) {
  return typeof requestId === 'string' && requestId.trim().length > 0 && requestId.length <= 120;
}

/**
 * Gerenciador de sessão WhatsApp (sessão única).
 * Frontend -> Spring -> HTTP interno -> Node -> Baileys.
 */
function createSessionManager({ config: cfg, authStore, socketFactory, baileysLib, logger } = {}) {
  const log = logger || console;
  const baileys = baileysLib || (() => { try { return require('@whiskeysockets/baileys'); } catch (_) { return null; } })();

  const state = {
    status: STATES.NOT_CONNECTED,
    qr: null,
    qrUpdatedAt: null,
    lastError: null,
    reconnectAttempts: 0,
    reconnectTimer: null,
    sock: null,
    connecting: false,
    socketGeneration: 0,
    shuttingDown: false,
    credsWriteQueue: [],
    credsWriteInProgress: false,
    seenRequests: new Map(),
    sendChain: Promise.resolve(),
  };

  function publicStatus() {
    return {
      sessionId: cfg.sessionId,
      status: state.status,
      hasQr: !!state.qr,
      lastError: state.lastError,
      reconnectAttempts: state.reconnectAttempts,
    };
  }

  function setStatus(next, err) {
    state.status = next;
    state.lastError = err || null;
  }

  function setQr(qr) {
    if (qr) {
      state.qr = qr;
      state.qrUpdatedAt = new Date().toISOString();
      setStatus(STATES.QR_REQUIRED);
    } else {
      state.qr = null;
      state.qrUpdatedAt = null;
    }
  }

  function clearReconnectTimer() {
    if (state.reconnectTimer) {
      clearTimeout(state.reconnectTimer);
      state.reconnectTimer = null;
    }
  }

  function backoffDelay(attempt) {
    const exp = cfg.reconnectBaseDelayMs * 2 ** Math.min(Math.max(attempt, 1) - 1, 10);
    return Math.min(exp, cfg.reconnectMaxDelayMs);
  }

  function scheduleReconnect() {
    if (state.shuttingDown) return false;
    if (state.reconnectTimer) return false;
    const max = Number(cfg.reconnectMaxAttempts) || 0;
    if (max > 0 && state.reconnectAttempts >= max) {
      setStatus(STATES.DISCONNECTED, state.lastError || 'reconnect_max_attempts');
      return false;
    }
    state.reconnectAttempts += 1;
    const delay = backoffDelay(state.reconnectAttempts);
    state.reconnectTimer = setTimeout(() => {
      state.reconnectTimer = null;
      connectInternal({ reason: 'reconnect' }).catch((e) => {
        log.warn && log.warn('Reconexao falhou:', e.message);
      });
    }, delay);
    return true;
  }

  // Write queue serial para creds.update. Uma falha rejeita apenas aquele write,
  // sem quebrar permanentemente writes posteriores.
  function enqueueCredsWrite(fn) {
    return new Promise((resolve, reject) => {
      state.credsWriteQueue.push({ fn, resolve, reject });
      process.nextTick(() => dispatchCredsWrites());
    });
  }

  async function dispatchCredsWrites() {
    if (state.credsWriteInProgress) return;
    const next = state.credsWriteQueue.shift();
    if (!next) {
      state.credsWriteInProgress = false;
      return;
    }
    state.credsWriteInProgress = true;
    try {
      await next.fn();
      next.resolve({ ok: true });
    } catch (e) {
      log.warn && log.warn('Falha ao persistir credenciais via write queue:', e.message);
      next.reject(e);
    } finally {
      state.credsWriteInProgress = false;
      if (state.credsWriteQueue.length > 0) {
        process.nextTick(() => dispatchCredsWrites());
      }
    }
  }

  function flushCredsWrites() {
    return new Promise((resolve) => {
      const check = () => {
        if (state.credsWriteQueue.length === 0 && !state.credsWriteInProgress) {
          resolve({ ok: true });
        } else {
          setTimeout(check, 10);
        }
      };
      // Garante que a dispatch loop esteja rodando.
      process.nextTick(() => dispatchCredsWrites());
      check();
    });
  }

  function attachSocket(sock, localGeneration) {
    state.sock = sock;
    const { DisconnectReason } = baileys || {};

    // EXATAMENTE UM listener creds.update — via queue, sem catch interno.
    sock.ev.on('creds.update', () => {
      enqueueCredsWrite(() => sock.authState.saveCreds());
    });

    sock.ev.on('connection.update', async (update) => {
      // Socket antigo não pode alterar estado novo.
      if (localGeneration !== state.socketGeneration) return;
      if (state.shuttingDown) return;
      const { connection, lastDisconnect, qr } = update || {};
      if (qr) setQr(qr);
      if (connection === 'connecting') {
        if (state.status !== STATES.QR_REQUIRED) setStatus(STATES.CONNECTING);
      } else if (connection === 'open') {
        // Ordem: 1 limpar QR; 2 limpar timer; 3 zerar attempts; 4 registered=true;
        // 5 await saveCreds pela queue; 6 somente depois CONNECTED.
        setQr(null);
        clearReconnectTimer();
        state.reconnectAttempts = 0;
        try {
          if (sock.authState && sock.authState.creds) {
            sock.authState.creds.registered = true;
          }
          if (sock.authState && typeof sock.authState.saveCreds === 'function') {
            await enqueueCredsWrite(() => sock.authState.saveCreds());
          }
        } catch (e) {
          // Persistência falhou: NÃO fingir CONNECTED.
          log.warn && log.warn('Falha ao persistir creds no open; nao marcando CONNECTED:', e.message);
          setStatus(STATES.ERROR, 'persist_failed');
          scheduleReconnect();
          return;
        }
        if (localGeneration !== state.socketGeneration) return;
        setStatus(STATES.CONNECTED);
      } else if (connection === 'close') {
        const statusCode = lastDisconnect?.error?.output?.statusCode;
        const boomCode = lastDisconnect?.error?.output?.statusCode
          ?? lastDisconnect?.error?.statusCode
          ?? lastDisconnect?.statusCode;
        const code = statusCode ?? boomCode;
        setQr(null);
        const loggedOut = DisconnectReason && code === DisconnectReason.loggedOut;
        const forbidden = DisconnectReason && code === DisconnectReason.forbidden;
        const replaced = DisconnectReason && code === DisconnectReason.connectionReplaced;
        if (loggedOut) {
          await flushCredsWrites(); // propagate errors for observability
          await authStore.clear(); // propagate errors for observability
          state.reconnectAttempts = 0;
          setStatus(STATES.LOGGED_OUT);
          return;
        }
        if (forbidden) {
          // Sem loop, sem apagar auth cegamente.
          state.reconnectAttempts = 0;
          setStatus(STATES.ERROR, 'forbidden');
          return;
        }
        if (replaced) {
          // Sem reconnect; encerra socket local.
          if (state.sock && typeof state.sock.end === 'function') {
            await state.sock.end();
          }
          state.reconnectAttempts = 0;
          setStatus(STATES.DISCONNECTED, 'connection_replaced');
          return;
        }
        setStatus(STATES.DISCONNECTED, code ? `disconnect_${code}` : 'disconnected');
        scheduleReconnect();
      }
    });
  }

  async function connectInternal({ reason } = {}) {
    if (state.shuttingDown) {
      throw new Error('Servico em shutdown; novos connects bloqueados.');
    }
    if (state.connecting) return publicStatus();
    if (!baileys) throw new Error('Baileys indisponivel.');
    state.connecting = true;
    clearReconnectTimer();
    setStatus(STATES.CONNECTING);
    // Cada novo socket: generation++.
    state.socketGeneration += 1;
    const localGeneration = state.socketGeneration;
    try {
      const { state: authState, saveCreds, registered } = await authStore.loadBaileysAuthState();
      const safeCreds = authStore.safeAuthStateObj ? authStore.safeAuthStateObj(authState) : authState;
      const { createSocket } = socketFactory || require('./socketFactory');
      let loggerLib = null;
      try { loggerLib = require('pino')({ level: 'silent' }); } catch (_) { loggerLib = null; }
      const sock = createSocket({ baileys, authState: safeCreds, logger: loggerLib });
      sock.authState = { saveCreds, creds: safeCreds.creds };
      await attachSocket(sock, localGeneration);
      if (registered) log.info && log.info('Sessao registrada encontrada; tentando restaurar.');
      return publicStatus();
    } catch (e) {
      if (localGeneration !== state.socketGeneration) throw e;
      setStatus(STATES.ERROR, 'connect_failed');
      scheduleReconnect();
      throw e;
    } finally {
      state.connecting = false;
    }
  }

  async function connect() {
    state.reconnectAttempts = 0;
    return connectInternal({ reason: 'manual' });
  }

  async function logout() {
    clearReconnectTimer();
    // Invalida geração: sockets antigos não alteram mais o estado.
    state.socketGeneration += 1;
    if (state.sock && typeof state.sock.logout === 'function') {
      await state.sock.logout(); // propagate errors for observability
    }
    await flushCredsWrites(); // propagate errors for observability
    await authStore.clear(); // propagate errors for observability
    if (state.sock && typeof state.sock.end === 'function') {
      await state.sock.end();
    }
    state.sock = null;
    setQr(null);
    state.reconnectAttempts = 0;
    setStatus(STATES.LOGGED_OUT);
    return publicStatus();
  }

  async function shutdown() {
    // Shutdown NÃO é logout: nunca chama logout nem authStore.clear.
    state.shuttingDown = true;
    clearReconnectTimer();
    state.socketGeneration += 1;
    await flushCredsWrites(); // propagate errors for observability
    if (state.sock && typeof state.sock.end === 'function') {
      await state.sock.end(); // propagate errors for observability
    }
    return publicStatus();
  }

  function getQr() {
    if (!state.qr) return { qr: null, updatedAt: null, hasQr: false };
    return { qr: state.qr, updatedAt: state.qrUpdatedAt, hasQr: true };
  }

  function pruneSeen() {
    const now = Date.now();
    for (const [k, v] of state.seenRequests) {
      if (v.expiresAt <= now) state.seenRequests.delete(k);
    }
  }

  function enqueueSend(fn) {
    const run = state.sendChain.then(fn, fn);
    state.sendChain = run.catch((e) => {
      // Log send chain errors for observability but don't break the chain
      log.warn && log.warn('Send chain error:', e.message);
    });
    return run;
  }

  async function sendText({ recipient, text, requestId }, { sender } = {}) {
    if (!isValidRecipient(recipient)) {
      const err = new Error('recipient invalido: somente digitos (8-15).');
      err.code = 'invalid_recipient';
      err.httpStatus = 400;
      throw err;
    }
    if (typeof text !== 'string' || text.trim().length === 0) {
      const err = new Error('text obrigatorio e nao vazio.');
      err.code = 'invalid_text';
      err.httpStatus = 400;
      throw err;
    }
    if (text.length > cfg.maxTextLength) {
      const err = new Error(`text excede limite de ${cfg.maxTextLength} caracteres.`);
      err.code = 'text_too_long';
      err.httpStatus = 400;
      throw err;
    }
    if (!isValidRequestId(requestId)) {
      const err = new Error('requestId obrigatorio.');
      err.code = 'invalid_request_id';
      err.httpStatus = 400;
      throw err;
    }
    pruneSeen();
    const cached = state.seenRequests.get(requestId);
    if (cached) return { ...cached.result, deduplicated: true };

    if (state.status !== STATES.CONNECTED || !state.sock) {
      const err = new Error('Sessao WhatsApp nao conectada.');
      err.code = 'session_not_connected';
      err.httpStatus = 409;
      throw err;
    }

    const doSend = sender || defaultSender;
    return enqueueSend(async () => {
      const cachedInside = state.seenRequests.get(requestId);
      if (cachedInside) return { ...cachedInside.result, deduplicated: true };
      const result = await doSend({ recipient, text, requestId });
      state.seenRequests.set(requestId, { result, expiresAt: Date.now() + cfg.idempotencyTtlMs });
      return { ...result, deduplicated: false };
    });
  }

  async function defaultSender({ recipient, text }) {
    const jid = `${recipient}@s.whatsapp.net`;
    if (typeof state.sock.onWhatsApp === 'function') {
      let check;
      try {
        check = await state.sock.onWhatsApp(jid);
      } catch (e) {
        // Falha técnica no lookup: não continuar para sendMessage.
        const err = new Error('Falha tecnica ao verificar destinatario.');
        err.code = 'recipient_check_failed';
        err.httpStatus = 502;
        err.cause = e;
        throw err;
      }
      const exists = Array.isArray(check) ? check.some((c) => c && c.exists === true) : false;
      if (!exists) {
        const err = new Error('Destinatario nao possui WhatsApp.');
        err.code = 'recipient_not_on_whatsapp';
        err.httpStatus = 400;
        throw err;
      }
    }
    const sent = await state.sock.sendMessage(jid, { text });
    const messageId = sent?.key?.id || null;
    return { status: 'sent', messageId, requestId: undefined };
  }

  function _injectSocket(sock) {
    state.sock = sock;
    setStatus(STATES.CONNECTED);
  }
  function _setStatusForTest(s) { setStatus(s); }
  function _debug() {
    return {
      status: state.status,
      hasQr: !!state.qr,
      seen: state.seenRequests.size,
      generation: state.socketGeneration,
      reconnectAttempts: state.reconnectAttempts,
      queue: state.credsWriteQueue.length,
      shuttingDown: state.shuttingDown,
    };
  }

  return {
    STATES,
    connect,
    connectInternal,
    logout,
    shutdown,
    getQr,
    publicStatus,
    sendText,
    enqueueCredsWrite,
    flushCredsWrites,
    backoffDelay,
    scheduleReconnect,
    restoreIfRegistered: async () => {
      try {
        const loaded = await authStore.loadBaileysAuthState();
        if (loaded && loaded.registered) {
          if (!baileys) {
            log.error && log.error('Baileys indisponivel no boot-restore.');
            return publicStatus();
          }
          if (state.shuttingDown) {
            log.info && log.info('Servico em shutdown; abortando restore.');
            return publicStatus();
          }
          log.info && log.info('Sessao registrada encontrada; tentando restaurar.');
          await connectInternal({ reason: 'boot-restore' });
        }
      } catch (e) {
        log.error && log.error('restore_failed=true', { errorType: e.name, safeMessage: e.message });
        setStatus(STATES.ERROR, 'restore_failed');
      }
      return publicStatus();
    },
    _injectSocket,
    _setStatusForTest,
    _debug,
    isValidRecipient,
    isValidRequestId,
    _state: state,
  };
}

module.exports = { createSessionManager, STATES, isValidRecipient, isValidRequestId };
