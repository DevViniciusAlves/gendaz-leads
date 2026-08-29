package com.gendaz.leads.service.messaging;

import com.gendaz.leads.entity.Lead;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class LogMessagingProvider implements MessagingProvider {

    private static final Logger log = LoggerFactory.getLogger(LogMessagingProvider.class);

    @Override
    public String getName() {
        return "log";
    }

    @Override
    public SendResult send(Lead lead, String message) {
        String target = lead.getInstagramUsername() != null
                ? "instagram:@" + lead.getInstagramUsername()
                : (lead.getPhone() != null ? "phone:" + lead.getPhone() : "sem-contato");
        log.info("[MENSAGEM NAO ENVIADA - provider=log] Para: {} | Lead: {} | Mensagem: {}",
                target, lead.getBusinessName(), message);
        return new SendResult(true,
                "Registrado em log. Nenhum envio real foi executado (provider de envio nao configurado).");
    }
}
