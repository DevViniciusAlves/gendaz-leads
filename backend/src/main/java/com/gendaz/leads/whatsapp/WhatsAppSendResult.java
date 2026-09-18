package com.gendaz.leads.whatsapp;

/**
 * Resultado de um envio individual.
 * {@code sent} significa que o Baileys aceitou/enviou a operacao,
 * nao necessariamente que o destinatario leu.
 */
public record WhatsAppSendResult(boolean sent, String messageId, String requestId, boolean deduplicated) {
}
