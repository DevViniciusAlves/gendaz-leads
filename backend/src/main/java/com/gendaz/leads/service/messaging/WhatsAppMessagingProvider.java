package com.gendaz.leads.service.messaging;

import com.gendaz.leads.entity.Lead;
import com.gendaz.leads.exception.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import com.gendaz.leads.whatsapp.WhatsAppService;
import com.gendaz.leads.whatsapp.WhatsAppSessionStatus;
import com.gendaz.leads.whatsapp.WhatsAppSendResult;

import java.util.regex.Pattern;

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
    public SendResult send(Lead lead, String message) {
        // Normalizar telefone do lead
        String phone = lead.getPhone();
        if (phone == null || phone.isBlank()) {
            String target = lead.getInstagramUsername() != null
                    ? "instagram:@" + lead.getInstagramUsername()
                    : "sem-contato";
            log.info("[MENSAGEM NAO ENVIADA - provider=whatsapp] Para: {} | Lead: {} | Motivo: telefone ausente",
                    target, lead.getBusinessName());
            return new SendResult(false, "Telefone ausente lead.");
        }

        // Normalizar: remover tudo não-digito, garantir formato BR
        String digits = phone.replaceAll("\\D", "");
        // Se tem 11 dígitos e começa com 9, provavelmente é 9XXXX-XXXX (celular BR sem DDI)
        // Se tem 10 dígitos, pode ser fixo ou celular sem o 9
        // Para WhatsApp, geralmente esperamos 11 dígitos (com 9) ou 12 com 55 DDI
        // A normalização completa fica por conta do WhatsAppService.normalizeRecipient()
        // que valida 8-15 dígitos e retorna apenas números.

        try {
            WhatsAppSendResult result = whatsAppService.sendText(
                    digits, message, lead.getBusinessName() + "-" + lead.getId());

            if (result.sent()) {
                String detail = "Enviado via WhatsApp. requestId=" + result.requestId();
                if (result.messageId() != null && !result.messageId().isBlank()) {
                    detail += ", messageId=" + result.messageId();
                }
                log.info("[MENSAGEM ENVIADA - provider=whatsapp] Lead: {} | requestId: {}", lead.getBusinessName(), result.requestId());
                return new SendResult(true, detail);
            } else {
                String detail = "Falha no envio via WhatsApp. sent=" + result.sent() + ", requestId=" + result.requestId();
                log.warn("[MENSAGEM NAO ENVIADA - provider=whatsapp] Lead: {} | {}", lead.getBusinessName(), "sent=" + result.sent() + ", requestId=" + result.requestId());
                return new SendResult(false, detail);
            }
        } catch (ApiException e) {
            String detail = "Erro ao enviar via WhatsApp: " + e.getMessage();
            log.warn("[MENSAGEM NAO ENVIADA - provider=whatsapp excecao] Lead: {} | {}", lead.getBusinessName(), e.getMessage());
            return new SendResult(false, detail);
        } catch (Exception e) {
            String detail = "Erro inesperado ao enviar via WhatsApp: " + e.getMessage();
            log.error("[MENSAGEM NAO ENVIADA - provider=whatsapp erro] Lead: {} | {}", lead.getBusinessName(), e.getMessage(), e);
            return new SendResult(false, detail);
        }
    }
}