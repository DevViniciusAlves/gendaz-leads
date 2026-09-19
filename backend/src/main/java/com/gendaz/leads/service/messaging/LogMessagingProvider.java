package com.gendaz.leads.service.messaging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component("log")
public class LogMessagingProvider implements MessagingProvider {

    private static final Logger log = LoggerFactory.getLogger(LogMessagingProvider.class);

    @Override
    public String getName() {
        return "log";
    }

    @Override
    public MessagingSendResult send(MessagingCommand command) {
        // Privacidade: nunca logar command.message() integral.
        int chars = command.message() != null ? command.message().length() : 0;
        boolean hasRequestId = command.requestId() != null && !command.requestId().isBlank();
        log.info("[LOG_PROVIDER] messageSendId={} leadId={} chars={} requestIdPresent={}",
                command.messageSendId(), command.leadId(), chars, hasRequestId);
        return new MessagingSendResult(true, FailureCategory.NONE, "log-id-" + System.currentTimeMillis(), null, "Log provider simulated success");
    }
}
