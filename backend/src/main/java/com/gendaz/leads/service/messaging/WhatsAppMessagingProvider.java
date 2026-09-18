package com.gendaz.leads.service.messaging;

import com.gendaz.leads.exception.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import com.gendaz.leads.whatsapp.WhatsAppService;
import com.gendaz.leads.whatsapp.WhatsAppSendResult;

@Component("whatsapp")
public class WhatsAppMessagingProvider implements MessagingProvider {

    private static final Logger log = LoggerFactory.getLogger(WhatsAppMessagingProvider.class);

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
            } else {
                String detail = "Falha no envio via WhatsApp. sent=" + result.sent() + ", requestId=" + result.requestId();
                return new MessagingSendResult(false, FailureCategory.AMBIGUOUS, null, "WHATSAPP_API_FAIL", detail);
            }
        } catch (ApiException e) {
            String detail = "Erro ao enviar via WhatsApp: " + e.getMessage();
            FailureCategory cat = FailureCategory.AMBIGUOUS;
            if (e.getStatus() != null && e.getStatus().is4xxClientError()) {
                cat = FailureCategory.TERMINAL;
            }
            return new MessagingSendResult(false, cat, null, e.getCode(), detail);
        } catch (Exception e) {
            String detail = "Erro inesperado ao enviar via WhatsApp: " + e.getMessage();
            return new MessagingSendResult(false, FailureCategory.AMBIGUOUS, null, "UNEXPECTED", detail);
        }
    }
}