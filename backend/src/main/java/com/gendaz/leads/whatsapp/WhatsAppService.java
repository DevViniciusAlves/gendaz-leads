package com.gendaz.leads.whatsapp;

import com.gendaz.leads.exception.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * Camada de dominio do WhatsApp no Spring. Normaliza entradas, gera requestId
 * para idempotencia e delega o transporte ao {@link WhatsAppServiceProvider}.
 * Regras comerciais (campanhas, fila, delays) ficam fora daqui.
 */
@Service
public class WhatsAppService {

    private static final Logger log = LoggerFactory.getLogger(WhatsAppService.class);
    private static final int MAX_TEXT_LENGTH = 4000;

    private final WhatsAppServiceProvider provider;

    public WhatsAppService(WhatsAppServiceProvider provider) {
        this.provider = provider;
    }

    public WhatsAppSessionStatus connect() {
        WhatsAppSessionStatus status = provider.connect();
        log.info("WhatsApp connect solicitado. status={}", status.status());
        return status;
    }

    public WhatsAppSessionStatus status() {
        return provider.status();
    }

    public WhatsAppQr qr() {
        return provider.qr();
    }

    public WhatsAppSessionStatus disconnect() {
        WhatsAppSessionStatus status = provider.logout();
        log.info("WhatsApp disconnect solicitado. status={}", status.status());
        return status;
    }

    public WhatsAppSendResult sendText(String recipient, String text, String requestId) {
        String normalized = normalizeRecipient(recipient);
        String cleanText = validateText(text);
        String key = (requestId == null || requestId.isBlank())
                ? UUID.randomUUID().toString()
                : requestId.trim();
        if (key.length() > 120) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST_ID",
                    "requestId excede 120 caracteres.");
        }
        WhatsAppSendResult result = provider.sendText(normalized, cleanText, key);
        // Nunca logar telefone integral nem texto integral.
        log.info("WhatsApp send: requestId={} sent={} deduplicated={}", key, result.sent(), result.deduplicated());
        return result;
    }

    static String normalizeRecipient(String recipient) {
        if (recipient == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_RECIPIENT",
                    "Destinatario obrigatorio (somente digitos, 8-15).");
        }
        String digits = recipient.replaceAll("\\D", "");
        if (!digits.matches("[0-9]{8,15}")) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_RECIPIENT",
                    "Destinatario invalido (somente digitos, 8-15).");
        }
        return digits;
    }

    static String validateText(String text) {
        if (text == null || text.trim().isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_TEXT",
                    "Texto obrigatorio e nao vazio.");
        }
        if (text.length() > MAX_TEXT_LENGTH) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "TEXT_TOO_LONG",
                    "Texto excede o limite de " + MAX_TEXT_LENGTH + " caracteres.");
        }
        return text;
    }
}
