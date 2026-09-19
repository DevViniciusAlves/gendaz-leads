package com.gendaz.leads.service.messaging;

import com.gendaz.leads.entity.Lead;
import com.gendaz.leads.entity.MessageSend;
import com.gendaz.leads.repository.LeadRepository;
import com.gendaz.leads.repository.MessageSendRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Set;

import com.gendaz.leads.whatsapp.WhatsAppService;
import com.gendaz.leads.whatsapp.WhatsAppSessionStatus;

@Component
public class SendQueueProcessor {

    private static final Logger log = LoggerFactory.getLogger(SendQueueProcessor.class);

    static final int MAX_ATTEMPTS = 5;

    private static final Set<String> TERMINAL_CODES = Set.of(
            "RECIPIENT_NOT_ON_WHATSAPP",
            "WHATSAPP_INVALID_RECIPIENT", "INVALID_RECIPIENT",
            "WHATSAPP_INVALID_TEXT", "INVALID_TEXT",
            "WHATSAPP_TEXT_TOO_LONG", "TEXT_TOO_LONG",
            "WHATSAPP_INVALID_REQUEST_ID", "INVALID_REQUEST_ID",
            "INVALID_TEMPLATE", "WHATSAPP_INVALID_TEMPLATE");

    private static final Set<String> TRANSIENT_CODES = Set.of(
            "WHATSAPP_NOT_CONNECTED",
            "WHATSAPP_RECIPIENT_CHECK_FAILED",
            "WHATSAPP_SERVICE_CONNECT_FAILED",
            "WHATSAPP_SERVICE_UNREACHABLE",
            "WHATSAPP_AUTH_MISMATCH");

    private static final Set<String> AMBIGUOUS_CODES = Set.of(
            "WHATSAPP_READ_TIMEOUT",
            "WHATSAPP_BAD_RESPONSE",
            "WHATSAPP_SEND_FAILED",
            "WHATSAPP_API_FAIL",
            "UNEXPECTED",
            "UNEXPECTED_ERROR");

    private final MessageSendClaimService claimService;
    private final MessageSendCompletionService completionService;
    private final MessageSendRepository messageSendRepository;
    private final LeadRepository leadRepository;
    private final MessagingProviderRouter providerRouter;
    private final MessagingScheduleProperties scheduleProperties;
    private final WhatsAppService whatsAppService;

    public SendQueueProcessor(MessageSendClaimService claimService,
                              MessageSendCompletionService completionService,
                              MessageSendRepository messageSendRepository,
                              LeadRepository leadRepository,
                              MessagingProviderRouter providerRouter,
                              MessagingScheduleProperties scheduleProperties,
                              WhatsAppService whatsAppService) {
        this.claimService = claimService;
        this.completionService = completionService;
        this.messageSendRepository = messageSendRepository;
        this.leadRepository = leadRepository;
        this.providerRouter = providerRouter;
        this.scheduleProperties = scheduleProperties;
        this.whatsAppService = whatsAppService;
    }

    @Scheduled(fixedDelayString = "#{@messagingScheduleProperties.delayMillis}")
    public void processQueue() {
        // Um por tick: no maximo um MessageSend por execucao.
        int max = Math.max(1, scheduleProperties.getMaxConcurrentSends());
        int processed = 0;
        while (processed < max) {
            MessageSend claimed;
            try {
                claimed = claimService.claimNextDue(); // COMMIT curto
            } catch (Exception e) {
                log.warn("Falha ao reivindicar envio: {}", e.getMessage());
                break;
            }
            if (claimed == null) break;
            try {
                processOneOutsideTransaction(claimed);
            } catch (Exception e) {
                log.warn("Erro ao processar envio {}: {}", claimed.getId(), e.getMessage());
            } finally {
                processed++;
            }
        }
    }

    void processOneOutsideTransaction(MessageSend claimed) {
        Long sendId = claimed.getId();

        // DNC apos claim: provider NAO chamado.
        Lead lead = leadRepository.findById(claimed.getLeadId()).orElse(null);
        if (lead == null) {
            completionService.completeTerminalFailure(sendId, "LEAD_NOT_FOUND", "Lead nao encontrado.");
            return;
        }
        if (lead.isDoNotContact()) {
            completionService.completeSkipped(sendId, "DO_NOT_CONTACT");
            return;
        }

        // Snapshot faltando: falha sem reconstruir.
        MessageSend fresh = messageSendRepository.findById(sendId).orElse(null);
        if (fresh == null) return;
        if (fresh.getMessageTextSnapshot() == null || fresh.getMessageTextSnapshot().isBlank()) {
            completionService.completeTerminalFailure(sendId, "MISSING_MESSAGE_SNAPSHOT",
                    "Snapshot da mensagem ausente; sem reconstrucao.");
            return;
        }
        if (fresh.getRecipientSnapshot() == null || fresh.getRecipientSnapshot().isBlank()) {
            completionService.completeTerminalFailure(sendId, "MISSING_RECIPIENT_SNAPSHOT",
                    "Snapshot do destinatario ausente; sem reconstrucao.");
            return;
        }

        // Provider: usa send.provider, nao env atual para registros existentes.
        MessagingProvider provider = providerRouter.getProvider(fresh.getProvider());
        if (provider == null) {
            completionService.completeTerminalFailure(sendId, "PROVIDER_NOT_FOUND",
                    "Provider inexistente: " + fresh.getProvider());
            return;
        }

        // Guard: WhatsApp status must be CONNECTED to send.
        if ("whatsapp".equals(fresh.getProvider()) && whatsAppService != null) {
            WhatsAppSessionStatus ws = whatsAppService.status();
            if (ws.status() != null && !ws.status().equals("CONNECTED")) {
                completionService.completeDeliveryUnknown(sendId, "WHATSAPP_NOT_CONNECTED",
                        "WhatsApp status is " + ws.status() + "; send blocked.");
                return;
            }
        }

        MessagingSendResult result;
        try {
            MessagingCommand command = new MessagingCommand(
                    fresh.getId(),
                    lead.getId(),
                    fresh.getRecipientSnapshot(),
                    fresh.getMessageTextSnapshot(),
                    fresh.getRequestId());
            result = provider.send(command);
        } catch (Exception e) {
            result = new MessagingSendResult(false, FailureCategory.AMBIGUOUS, null,
                    "UNEXPECTED_ERROR", e.getMessage());
        }

        if (result.success()) {
            completionService.completeSuccess(sendId, result.providerMessageId());
            return;
        }
        handleFailure(fresh, result);
    }

    private void handleFailure(MessageSend send, MessagingSendResult result) {
        String code = result.errorCode() != null ? result.errorCode() : "UNKNOWN";
        String detail = result.detail() != null ? result.detail() : code;
        FailureCategory category = categorize(code, result.failureCategory());

        switch (category) {
            case TERMINAL -> completionService.completeTerminalFailure(send.getId(), code, detail);
            case PROVIDER_NOT_FOUND -> completionService.completeTerminalFailure(
                    send.getId(), "PROVIDER_NOT_FOUND", detail);
            case AMBIGUOUS ->
                // AMBIGUOUS -> DELIVERY_UNKNOWN IMEDIATAMENTE. Zero retry automatico.
                    completionService.completeDeliveryUnknown(send.getId(), code, detail);
            case TRANSIENT -> {
                if (send.getAttempts() >= MAX_ATTEMPTS) {
                    completionService.completeMaxAttemptsFailed(send.getId(), code, detail);
                } else {
                    Instant next = Instant.now().plus(2L * Math.max(1, send.getAttempts()), ChronoUnit.MINUTES);
                    completionService.completeTransientRetry(send.getId(), code, detail, next);
                }
            }
            default -> completionService.completeDeliveryUnknown(send.getId(), code, detail);
        }
    }

    static FailureCategory categorize(String errorCode, FailureCategory providerCategory) {
        if (errorCode == null) return FailureCategory.AMBIGUOUS;
        if (TERMINAL_CODES.contains(errorCode)) return FailureCategory.TERMINAL;
        if (TRANSIENT_CODES.contains(errorCode)) return FailureCategory.TRANSIENT;
        if (AMBIGUOUS_CODES.contains(errorCode)) return FailureCategory.AMBIGUOUS;
        if ("PROVIDER_NOT_FOUND".equals(errorCode)) return FailureCategory.PROVIDER_NOT_FOUND;
        // Codigo desconhecido apos POST: trata como ambiguo (seguro contra duplo envio).
        return FailureCategory.AMBIGUOUS;
    }
}
