'use strict';

const { maskPhone } = require('./phoneMask');

// Estados publicos da sessao. Nunca expor objetos internos do Baileys.
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
  };

  // Idempotencia imediata em memoria (protecao tecnica; fonte duravel fica no Spring).
  const seenRequests = new Map(); // requestId -> { result, expiresAt }
  // Serializacao de envio: promise chain.
  let sendChain = Promise.resolve();

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
    // Nunca logar conteudo do QR.
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
    const exp = cfg.reconnectBaseDelayMs * 2 ** Math.min(attempt, 6);
    return Math.min(exp, cfg.reconnectMaxDelayMs);
  }

  function scheduleReconnect() {
    if (state.reconnectAttempts >= cfg.reconnectMaxAttempts) {
      setStatus(STATES.ERROR, 'max_reconnect_attempts_reached');
      return;
    }
    const delay = backoffDelay(state.reconnectAttempts);
    state.reconnectAttempts += 1;
    clearReconnectTimer();
    state.reconnectTimer = setTimeout(() => {
      state.reconnectTimer = null;
      connectInternal({ reason: 'reconnect' }).catch(() => {});
    }, delay);
    if (state.status !== STATES.QR_REQUIRED) setStatus(STATES.DISCONNECTED);
  }

  async function attachSocket(sock) {
    state.sock = sock;
    const { DisconnectReason } = baileys || {};
    sock.ev.on('creds.update', async () => {
      try {
        if (sock.authState && sock.authState.saveCreds) await sock.authState.saveCreds();
      } catch (e) {
        log.warn && log.warn('Falha ao salvar credenciais.');
      }
    });
    sock.ev.on('connection.update', async (update) => {
      const { connection, lastDisconnect, qr } = update || {};
      if (qr) setQr(qr);
      if (connection === 'connecting') {
        if (state.status !== STATES.QR_REQUIRED) setStatus(STATES.CONNECTING);
      } else if (connection === 'open') {
        setQr(null);
        state.reconnectAttempts = 0;
        clearReconnectTimer();
        setStatus(STATES.CONNECTED);
      } else if (connection === 'close') {
        const statusCode = lastDisconnect?.error?.output?.statusCode;
        setQr(null);
        const loggedOut = DisconnectReason && statusCode === DisconnectReason.loggedOut;
        if (loggedOut) {
          // Logout definitivo: limpar auth state.
          try { await authStore.clear(); } catch (_) {}
          state.reconnectAttempts = 0;
          setStatus(STATES.LOGGED_OUT);
        } else {
          scheduleReconnect();
        }
      }
    });
  }

  async function connectInternal({ reason } = {}) {
    if (state.connecting) return publicStatus();
    if (!baileys) throw new Error('Baileys indisponivel.');
    state.connecting = true;
    clearReconnectTimer();
    setStatus(STATES.CONNECTING);
    try {
      const { state: authState, saveCreds, registered } = await authStore.loadBaileysAuthState();
      // Tentativa de restore silencioso quando registrada: mesmo fluxo de socket.
      const { createSocket } = socketFactory || require('./socketFactory');
      let loggerLib = null;
      try { loggerLib = require('pino')({ level: 'silent' }); } catch (_) { loggerLib = null; }
      const sock = createSocket({ baileys, authState, logger: loggerLib });
      sock.authState = { saveCreds };
      if (sock.ev && typeof sock.ev.on === 'function') {
        sock.ev.on('creds.update', saveCreds);
      }
      await attachSocket(sock);
      if (registered) log.info && log.info('Sessao registrada encontrada; tentando restaurar.');
      return publicStatus();
    } catch (e) {
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
    try {
      if (state.sock && typeof state.sock.logout === 'function') {
        await state.sock.logout().catch(() => {});
      }
    } finally {
      try { await authStore.clear(); } catch (_) {}
      try { if (state.sock && typeof state.sock.end === 'function') state.sock.end(); } catch (_) {}
      state.sock = null;
      setQr(null);
      state.reconnectAttempts = 0;
      setStatus(STATES.LOGGED_OUT);
    }
    return publicStatus();
  }

  function getQr() {
    if (!state.qr) return { qr: null, updatedAt: null, hasQr: false };
    return { qr: state.qr, updatedAt: state.qrUpdatedAt, hasQr: true };
  }

  function pruneSeen() {
    const now = Date.now();
    for (const [k, v] of seenRequests) {
      if (v.expiresAt <= now) seenRequests.delete(k);
    }
  }

  function enqueueSend(fn) {
    const run = sendChain.then(fn, fn);
    // Evita que uma falha quebre a cadeia para os proximos.
    sendChain = run.catch(() => {});
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
    const cached = seenRequests.get(requestId);
    if (cached) return { ...cached.result, deduplicated: true };

    if (state.status !== STATES.CONNECTED || !state.sock) {
      const err = new Error('Sessao WhatsApp nao conectada.');
      err.code = 'session_not_connected';
      err.httpStatus = 409;
      throw err;
    }

    const doSend = sender || defaultSender;
    return enqueueSend(async () => {
      // Re-checa idempotencia dentro da fila (concorrencia).
      const cachedInside = seenRequests.get(requestId);
      if (cachedInside) return { ...cachedInside.result, deduplicated: true };
      const result = await doSend({ recipient, text, requestId });
      seenRequests.set(requestId, { result, expiresAt: Date.now() + cfg.idempotencyTtlMs });
      return { ...result, deduplicated: false };
    });
  }

  async function defaultSender({ recipient, text }) {
    const jid = `${recipient}@s.whatsapp.net`;
    // Verificacao de conta WhatsApp quando suportado.
    try {
      if (typeof state.sock.onWhatsApp === 'function') {
        const check = await state.sock.onWhatsApp(jid);
        const exists = Array.isArray(check) ? check.some((c) => c && (c.exists || c.jid)) : !!check;
        if (!exists) {
          const err = new Error('Destinatario nao possui WhatsApp.');
          err.code = 'recipient_not_on_whatsapp';
          err.httpStatus = 400;
          throw err;
        }
      }
    } catch (e) {
      if (e && e.httpStatus === 400) throw e;
      // Falha na verificacao nao deve bloquear envio: segue best-effort.
    }
    const sent = await state.sock.sendMessage(jid, { text });
    // 'sent' = Baileys aceitou/enviou a operacao (nao significa leitura).
    const messageId = sent?.key?.id || null;
    return { status: 'sent', messageId, requestId: undefined };
  }

  // Hooks para testes (injetar socket fake / inspecionar sem expor segredos).
  function _injectSocket(sock) {
    state.sock = sock;
    setStatus(STATES.CONNECTED);
  }
  function _setStatusForTest(s) { setStatus(s); }
  function _debug() { return { status: state.status, hasQr: !!state.qr, seen: seenRequests.size }; }

  return {
    STATES,
    connect,
    connectInternal,
    logout,
    getQr,
    publicStatus,
    sendText,
    restoreIfRegistered: async () => {
      // No boot: se registrada, tentar restaurar.
      try {
        const loaded = await authStore.loadBaileysAuthState();
        if (loaded && loaded.registered && baileys) {
          await connectInternal({ reason: 'boot-restore' });
        }
      } catch (_) {}
      return publicStatus();
    },
    _injectSocket,
    _setStatusForTest,
    _debug,
  };
}

module.exports = { createSessionManager, STATES, isValidRecipient, isValidRequestId };
