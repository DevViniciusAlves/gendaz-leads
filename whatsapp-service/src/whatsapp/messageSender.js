'use strict';

// Envio de texto de baixo nivel (1 destinatario, 1 mensagem). Chamado pelo SessionManager.
async function sendTextMessage(sock, { recipient, text }) {
  const jid = `${recipient}@s.whatsapp.net`;
  const sent = await sock.sendMessage(jid, { text });
  return { status: 'sent', messageId: sent?.key?.id || null };
}

module.exports = { sendTextMessage };
