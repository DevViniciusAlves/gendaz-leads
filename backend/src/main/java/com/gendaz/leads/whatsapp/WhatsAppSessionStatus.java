package com.gendaz.leads.whatsapp;

/**
 * Estado publico da sessao WhatsApp (espelho dos estados do whatsapp-service).
 * Nunca contem JID, credentials ou chaves.
 */
public record WhatsAppSessionStatus(String status, boolean hasQr, String lastError) {

    public static WhatsAppSessionStatus of(String status, boolean hasQr, String lastError) {
        return new WhatsAppSessionStatus(status, hasQr, lastError);
    }
}
