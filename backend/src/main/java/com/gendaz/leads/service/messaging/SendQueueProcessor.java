package com.gendaz.leads.service.messaging;

import com.gendaz.leads.entity.Lead;
import com.gendaz.leads.entity.MessageSend;
import com.gendaz.leads.repository.MessageSendRepository;
import com.gendaz.leads.repository.LeadRepository;
import com.gendaz.leads.util.Normalizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.*;
import org.springframework.scheduling.annotation.*;
import org.springframework.stereotype.*;
import org.springframework.transaction.annotation.*;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import jakarta.persistence.EntityManager;

@Component
public class SendQueueProcessor {

    private static final Logger log = LoggerFactory.getLogger(SendQueueProcessor.class);
    private static final int MAX_ATTEMPTS = 5;

    @Value("${app.messaging.max-concurrent-sends:1}")
    private int maxConcurrentSends;

    private final MessageSendRepository messageSendRepository;
    private final LeadRepository leadRepository;
    private final MessagingProviderRouter providerRouter;
    private final Normalizer normalizer;
    private final EntityManager entityManager;

    public SendQueueProcessor(MessageSendRepository messageSendRepository, LeadRepository leadRepository,
                              MessagingProviderRouter providerRouter,
                              Normalizer normalizer, EntityManager entityManager) {
        this.messageSendRepository = messageSendRepository;
        this.leadRepository = leadRepository;
        this.providerRouter = providerRouter;
        this.normalizer = normalizer;
        this.entityManager = entityManager;
    }

    @Scheduled(fixedDelayString = "${app.messaging.send-interval-seconds:60}000")
    @Transactional
    public void processQueue() {
        int processed = 0;
        while (processed < maxConcurrentSends) {
            MessageSend send = fetchAndLockNextTask();
            if (send == null) break;
            try {
                processOne(send);
            } catch (Exception e) {
                log.warn("Erro ao processar envio {}: {}", send.getId(), e.getMessage());
            } finally {
                processed++;
            }
        }
    }

    private MessageSend fetchAndLockNextTask() {
        List<MessageSend> results = entityManager.createQuery(
            "SELECT ms FROM MessageSend ms WHERE ms.status = 'QUEUED' " +
            "AND (ms.nextAttemptAt IS NULL OR ms.nextAttemptAt <= :now) " +
            "ORDER BY ms.nextAttemptAt ASC NULLS FIRST", MessageSend.class)
            .setParameter("now", Instant.now())
            .setMaxResults(1)
            .setLockMode(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
            .setHint("jakarta.persistence.lock.timeout", -2) // SKIP LOCKED
            .getResultList();

        if (results.isEmpty()) return null;
        
        MessageSend send = results.get(0);
        send.setStatus("SENDING");
        return messageSendRepository.saveAndFlush(send);
    }

    private void processOne(MessageSend send) {
        send.setAttempts(send.getAttempts() + 1);
        send.setLastAttemptAt(Instant.now());

        Lead lead = leadRepository.findById(send.getLeadId()).orElse(null);
        if (lead == null) {
            send.setStatus("FAILED");
            send.setResult("Lead nao encontrado.");
            messageSendRepository.save(send);
            return;
        }

        if (lead.isDoNotContact()) {
            send.setStatus("SKIPPED");
            send.setResult("Lead marcado como nao prospectar (doNotContact).");
            messageSendRepository.save(send);
            return;
        }

        if (send.getMessageTextSnapshot() == null || send.getMessageTextSnapshot().isBlank()) {
            send.setStatus("FAILED");
            send.setResult("Template ou mensagem não renderizada no snapshot.");
            messageSendRepository.save(send);
            return;
        }

        if (send.getRecipientSnapshot() == null || send.getRecipientSnapshot().isBlank()) {
            send.setStatus("FAILED");
            send.setResult("Destinatário ausente no snapshot.");
            messageSendRepository.save(send);
            return;
        }

        MessagingProvider provider = providerRouter.getProvider(send.getProvider());
        if (provider == null) {
            send.setStatus("FAILED");
            send.setResult("PROVIDER_NOT_FOUND");
            messageSendRepository.save(send);
            return;
        }

        try {
            MessagingCommand command = new MessagingCommand(
                send.getId(),
                lead.getId(),
                send.getRecipientSnapshot(),
                send.getMessageTextSnapshot(),
                send.getRequestId()
            );

            MessagingSendResult result = provider.send(command);

            if (result.success()) {
                send.setStatus("SENT");
                send.setSentAt(Instant.now());
                send.setResult(result.detail());
                send.setProviderMessageId(result.providerMessageId());
                lead.setStatus("SENT");
                leadRepository.save(lead);
            } else {
                handleFailure(send, result);
            }
        } catch (Exception e) {
            handleFailure(send, new MessagingSendResult(false, FailureCategory.AMBIGUOUS, null, "UNEXPECTED_ERROR", e.getMessage()));
        }
        messageSendRepository.save(send);
    }

    private void handleFailure(MessageSend send, MessagingSendResult result) {
        send.setResult(result.detail());
        send.setErrorCode(result.errorCode());

        if (result.failureCategory() == FailureCategory.TERMINAL) {
            send.setStatus("FAILED");
            return;
        }

        if (send.getAttempts() >= MAX_ATTEMPTS) {
            send.setStatus(result.failureCategory() == FailureCategory.AMBIGUOUS ? "DELIVERY_UNKNOWN" : "FAILED");
        } else {
            send.setStatus("QUEUED");
            send.setNextAttemptAt(Instant.now().plus(2L * send.getAttempts(), ChronoUnit.MINUTES));
        }
    }
}