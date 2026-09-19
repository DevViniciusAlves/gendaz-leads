package com.gendaz.leads.service.messaging;

import com.gendaz.leads.exception.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import com.gendaz.leads.whatsapp.WhatsAppService;
import com.gendaz.leads.whatsapp.WhatsAppSendResult;

import java.util.Set;

@Component("whatsapp")
public class WhatsAppMessagingProvider implements MessagingProvider {

    private static final Logger log = LoggerFactory.getLogger(WhatsAppMessagingProvider.class);

    private static final Set<String> TERMINAL_CODES = Set.of(
            "RECIPIENT_NOT_ON_WHATSAPP",
            "WHATSAPP_INVALID_RECIPIENT",
            "WHATSAPP_INVALID_TEXT",
            "WHATSAPP_TEXT_TOO_LONG",
            "WHATSAPP_INVALID_REQUEST_ID",
            "INVALID_TEMPLATE");

    private static final Set<String> TRANSIENT_CODES = Set.of(
            "WHATSAPP_NOT_CONNECTED",
            "WHATSAPP_RECIPIENT_CHECK_FAILED",
            "WHATSAPP_SERVICE_CONNECT_FAILED");

    private final WhatsAppService whatsAppService;

    @Autowired
    public WhatsAppMessagingProvider(WhatsAppService whatsAppService) {
        this.whatsAppService = whatsAppService;
    }

    @Override
    public String getName() {
        return "whatsapp";
    }

    @Override
    public MessagingSendResult send(MessagingCommand command) {
        String phone = command.recipient();

        try {
            WhatsAppSendResult result = whatsAppService.sendText(
                    phone, command.message(), command.requestId());

            if (result.sent()) {
                String detail = "Enviado via WhatsApp. requestId=" + result.requestId();
                return new MessagingSendResult(true, FailureCategory.NONE, result.messageId(), null, detail);
            }
            String detail = "Falha no envio via WhatsApp. sent=" + result.sent() + ", requestId=" + result.requestId();
            return new MessagingSendResult(false, FailureCategory.AMBIGUOUS, null, "WHATSAPP_SEND_FAILED", detail);
        } catch (ApiException e) {
            String code = e.getCode() != null ? e.getCode() : "WHATSAPP_SEND_FAILED";
            String detail = "Erro ao enviar via WhatsApp: " + e.getMessage();
            return new MessagingSendResult(false, classifyByCode(code), null, code, detail);
        } catch (Exception e) {
            String detail = "Erro inesperado ao enviar via WhatsApp: " + e.getMessage();
            return new MessagingSendResult(false, FailureCategory.AMBIGUOUS, null, "UNEXPECTED", detail);
        }
    }

    static FailureCategory classifyByCode(String code) {
        if (TERMINAL_CODES.contains(code)) return FailureCategory.TERMINAL;
        if (TRANSIENT_CODES.contains(code)) return FailureCategory.TRANSIENT;
        // WHATSAPP_READ_TIMEOUT, WHATSAPP_BAD_RESPONSE, WHATSAPP_SEND_FAILED e
        // desconhecidos apos POST: AMBIGUOUS (status do envio desconhecido).
        return FailureCategory.AMBIGUOUS;
    }
}
