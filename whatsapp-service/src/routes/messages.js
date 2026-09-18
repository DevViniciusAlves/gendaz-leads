'use strict';

const express = require('express');

function messagesRouter({ sessionManager, config, auth }) {
  const router = express.Router();
  router.use(auth);

  async function handleSend(req, res) {
    const { recipient, text, requestId } = req.body || {};
    try {
      const normalizedRecipient = String(recipient ?? '').replace(/\D/g, '');
      const result = await sessionManager.sendText({
        recipient: normalizedRecipient,
        text,
        requestId,
      });
      return res.json({
        status: result.status,
        messageId: result.messageId || null,
        requestId,
        deduplicated: !!result.deduplicated,
      });
    } catch (e) {
      const httpStatus = e.httpStatus || 502;
      const code = e.code || 'send_failed';
      return res.status(httpStatus).json({ error: code });
    }
  }

  // 1 destinatario, 1 mensagem, 1 requestId. Sem bulk.
  router.post('/internal/whatsapp/session/messages/text', handleSend);
  router.post('/internal/whatsapp/sessions/:sessionId/messages/text', (req, res, next) => {
    if (req.params.sessionId !== config.sessionId) {
      return res.status(400).json({ error: 'invalid_session_id' });
    }
    return handleSend(req, res);
  });

  return router;
}

module.exports = { messagesRouter };
