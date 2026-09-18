package com.gendaz.leads.whatsapp;

/**
 * Contrato do provedor WhatsApp (implementado via HTTP interno ao whatsapp-service Node).
 * O Spring usa sempre a sessao propria do Gendaz Leads; o browser nunca chama o Node direto.
 */
public interface WhatsAppServiceProvider {

    String getName();

    WhatsAppSessionStatus connect();

    WhatsAppSessionStatus status();

    WhatsAppQr qr();

    WhatsAppSessionStatus logout();

    WhatsAppSendResult sendText(String recipient, String text, String requestId);
}
