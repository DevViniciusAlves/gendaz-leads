package com.gendaz.leads.service.messaging;

import com.gendaz.leads.entity.Lead;
import com.gendaz.leads.entity.MessageSend;
import com.gendaz.leads.entity.MessageTemplate;
import com.gendaz.leads.repository.MessageTemplateRepository;
import com.gendaz.leads.repository.MessageSendRepository;
import com.gendaz.leads.repository.LeadRepository;
import com.gendaz.leads.service.LeadService;
import com.gendaz.leads.service.DeduplicationService;
import com.gendaz.leads.service.TemplateRenderer;
import com.gendaz.leads.util.Normalizer;
import com.gendaz.leads.exception.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.*;
import org.springframework.scheduling.annotation.*;
import org.springframework.stereotype.*;
import org.springframework.transaction.annotation.*;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

@Component
public class SendQueueProcessor {

    private static final Logger log = LoggerFactory.getLogger(SendQueueProcessor.class);
    private static final int MAX_ATTEMPTS = 5;

    @Value("${app.messaging.max-concurrent-sends:1}")
    private int maxConcurrentSends;

    @Value("${app.messaging.provider:log}")
    private String providerName;

    private final MessageSendRepository messageSendRepository;
    private final LeadRepository leadRepository;
    private final MessageTemplateRepository templateRepository;
    private final TemplateRenderer templateRenderer;
    private final Map<String, MessagingProvider> messagingProviders;
    private final LeadService leadService;
    private final DeduplicationService deduplicationService;
    private final Normalizer normalizer;

    public SendQueueProcessor(MessageSendRepository messageSendRepository, LeadRepository leadRepository,
                              MessageTemplateRepository templateRepository, TemplateRenderer templateRenderer,
                              List<MessagingProvider> messagingProviders,
                              LeadService leadService, DeduplicationService deduplicationService,
                              Normalizer normalizer) {
        this.messageSendRepository = messageSendRepository;
        this.leadRepository = leadRepository;
        this.templateRepository = templateRepository;
        this.templateRenderer = templateRenderer;
        this.messagingProviders = messagingProviders.stream()
                .collect(Collectors.toMap(MessagingProvider::getName, p -> p));
        this.leadService = leadService;
        this.deduplicationService = deduplicationService;
        this.normalizer = normalizer;
    }

    @Scheduled(fixedDelayString = "${app.messaging.send-interval-seconds:30}000")
    @Transactional
    public void processQueue() {
        List<MessageSend> due = messageSendRepository.findDue(Instant.now());
        int processed = 0;
        for (MessageSend send : due) {
            if (processed >= maxConcurrentSends) break;
            try {
                processOne(send);
                processed++;
            } catch (RuntimeException e) {
                log.warn("Erro ao processar envio {}: {}", send.getId(), e.getMessage());
            }
        }
    }

    private void processOne(MessageSend send) {
        // Verificar doNotContact imediatamente antes do envio
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

        // Verificar telefone válido
        String normalizedPhone = normalizer.normalizePhone(lead.getPhone());
        if (normalizedPhone == null || normalizedPhone.isBlank()) {
            send.setStatus("FAILED");
            send.setResult("Telefone invalido ou ausente.");
            messageSendRepository.save(send);
            return;
        }

        // Renderizar snapshot da mensagem se ainda não renderizada
        if (send.getMessageTextSnapshot() == null && send.getTemplateId() != null) {
            Optional<MessageTemplate> templateOpt = templateRepository.findById(send.getTemplateId());
            if (templateOpt.isPresent()) {
                String rendered = templateRenderer.render(
                        templateOpt.get().getTemplateText(), lead, null);
                send.setMessageTextSnapshot(rendered);
            }
        }

        // Selecionar provider pelo nome
        MessagingProvider provider = messagingProviders.get(providerName);
        if (provider == null) {
            // Provider log - apenas registra
            send.setStatus("SENT");
            send.setResult("Registrado em log (provider=log).");
            messageSendRepository.save(send);
            return;
        }

        // Usar mensagem snapshot se renderizada, senão usar string vazia
        String text = send.getMessageTextSnapshot() != null ? send.getMessageTextSnapshot() : "";

        try {
            // O provider send ja trata o caso de telefone/lead
            var result = provider.send(lead, text);

            if (result.success()) {
                send.setStatus("SENT");
                send.setResult(result.detail());
                lead.setStatus("SENT");
                leadRepository.save(lead);
            } else {
                if (send.getAttempts() >= MAX_ATTEMPTS) {
                    send.setStatus("FAILED");
                } else {
                    send.setStatus("QUEUED");
                    send.setNextAttemptAt(Instant.now().plus(2L * send.getAttempts(), ChronoUnit.MINUTES));
                }
                send.setResult(result.detail());
            }
        } catch (Exception e) {
            if (send.getAttempts() >= MAX_ATTEMPTS) {
                send.setStatus("FAILED");
            } else {
                send.setStatus("QUEUED");
                send.setNextAttemptAt(Instant.now().plus(2L * send.getAttempts(), ChronoUnit.MINUTES));
            }
            send.setResult("Erro: " + e.getMessage());
        }
        messageSendRepository.save(send);
    }
}