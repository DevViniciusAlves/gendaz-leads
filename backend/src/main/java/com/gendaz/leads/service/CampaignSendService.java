package com.gendaz.leads.service;

import com.gendaz.leads.dto.campaign.*;
import com.gendaz.leads.entity.Lead;
import com.gendaz.leads.entity.MessageTemplate;
import com.gendaz.leads.entity.Campaign;
import com.gendaz.leads.entity.MessageSend;
import com.gendaz.leads.repository.MessageTemplateRepository;
import com.gendaz.leads.repository.MessageSendRepository;
import com.gendaz.leads.repository.CampaignRepository;
import com.gendaz.leads.repository.LeadRepository;
import com.gendaz.leads.util.Normalizer;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.util.*;
import java.util.stream.*;

@Service
public class CampaignSendService {

    private final CampaignRepository campaignRepository;
    private final LeadRepository leadRepository;
    private final MessageSendRepository messageSendRepository;
    private final MessageTemplateRepository templateRepository;
    private final TemplateRenderer templateRenderer;
    private final DeduplicationService deduplicationService;
    private final Normalizer normalizer;

    public CampaignSendService(CampaignRepository campaignRepository, LeadRepository leadRepository,
                               MessageSendRepository messageSendRepository,
                               MessageTemplateRepository templateRepository,
                               TemplateRenderer templateRenderer,
                               DeduplicationService deduplicationService,
                               Normalizer normalizer) {
        this.campaignRepository = campaignRepository;
        this.leadRepository = leadRepository;
        this.messageSendRepository = messageSendRepository;
        this.templateRepository = templateRepository;
        this.templateRenderer = templateRenderer;
        this.deduplicationService = deduplicationService;
        this.normalizer = normalizer;
    }

    /**
     * Verifica se um lead é elegível para envio via WhatsApp.
     * Retorna o lead se elegível, ou lança RuntimeException se não.
     */
    public Lead checkEligibility(Long leadId) {
        Lead lead = leadRepository.findById(leadId).orElseThrow(
                () -> new RuntimeException("Lead nao encontrado."));

        // Verificar doNotContact
        if (lead.isDoNotContact()) {
            throw new RuntimeException("Este lead nao prospectar (doNotContact).");
        }

        // Verificar telefone utilizável
        String normalizedPhone = normalizer.normalizePhone(lead.getPhone());
        if (normalizedPhone == null || normalizedPhone.isBlank()) {
            throw new RuntimeException("Lead nao tem telefone utilizavel.");
        }

        return lead;
    }

    /**
     * Gera previews para os leads selecionados usando o template da campanha.
     */
    @Transactional(readOnly = true)
    public SendPreviewResponse generatePreview(Long campaignId, List<Long> leadIds,
                                                 String templateText, Boolean allEligible) {
        Campaign campaign = campaignRepository.findById(campaignId)
                .orElseThrow(() -> new RuntimeException("Campanha nao encontrada."));

        // Se não passou leadIds específicos, pegar todos os leads da campanha
        List<Lead> leads;
        if (leadIds != null && !leadIds.isEmpty()) {
            leads = leadRepository.findAllById(leadIds);
        } else if (allEligible != null && allEligible) {
            leads = leadRepository.findByCampaign(campaignId);
        } else {
            // Se nada, retornar preview vazio
            return new SendPreviewResponse(0, 0, new ArrayList<>(), new ArrayList<>());
        }

        // Filtrar leads elegíveis
        List<Lead> eligible = new ArrayList<>();
        List<Lead> ineligible = new ArrayList<>();
        List<String> ineligibilityReasons = new ArrayList<>();

        for (Lead lead : leads) {
            try {
                checkEligibility(lead.getId());
                eligible.add(lead);
            } catch (RuntimeException e) {
                ineligible.add(lead);
                ineligibilityReasons.add(lead.getBusinessName() + " - " + e.getMessage());
            }
        }

        // Gerar previews
        List<PreviewItem> previews = new ArrayList<>();
        for (Lead lead : eligible) {
            String rendered = templateRenderer.render(
                    templateText != null ? templateText : "",
                    lead, campaign);
            previews.add(new PreviewItem(
                    lead.getId(),
                    lead.getBusinessName(),
                    lead.getInstagramUsername() != null ? "@" + lead.getInstagramUsername() : null,
                    lead.getPhone(),
                    lead.getCity() != null ? lead.getCity() + "/" + lead.getState() : null,
                    rendered
            ));
        }

        return new SendPreviewResponse(
                eligible.size(),
                ineligible.size(),
                previews,
                ineligibilityReasons
        );
    }

    /**
     * Envia mensagens para leads elegíveis.
     * Cria MessageSend com snapshot da mensagem.
     */
    @Transactional
    public SendResultEnqueue enqueueMessages(Long campaignId, List<Long> leadIds,
                                              Long templateId) {
        Campaign campaign = campaignRepository.findById(campaignId)
                .orElseThrow(() -> new RuntimeException("Campanha nao encontrada."));

        // Carregar template se fornecido
        MessageTemplate template = templateId != null ?
                templateRepository.findById(templateId).orElseThrow(
                        () -> new RuntimeException("Template nao encontrado.")) : null;

        // Se não template nem texto fornecido, error
        if (templateId == null) {
            throw new RuntimeException("Nenhum template configurado para envio.");
        }

        // Processar leads
        List<Lead> leads = leadIds != null && !leadIds.isEmpty()
                ? leadRepository.findAllById(leadIds)
                : leadRepository.findByCampaign(campaignId);

        List<MessageSentResult> sentResults = new ArrayList<>();
        List<MessageSkippedResult> skippedResults = new ArrayList<>();

        for (Lead lead : leads) {
            try {
                // Verificar elegibilidade
                checkEligibility(lead.getId());

                // Verificar se já tem envio SENT para este lead (idempotência por lead)
                boolean alreadySent = messageSendRepository.existsByLeadIdAndStatus(lead.getId(), "SENT");
                if (alreadySent) {
                    skippedResults.add(new MessageSkippedResult(
                            lead.getId(),
                            lead.getBusinessName(),
                            "Ja enviado anteriormente."
                    ));
                    continue;
                }

                // Renderizar snapshot da mensagem
                String rendered = templateRenderer.render(
                        template.getTemplateText(), lead, campaign);

                // Gerar requestId estável (UUID determinístico baseado em lead + campanha)
                String requestId = "gendaz-" + campaignId + "-" + lead.getId();

                // Verificar se já existe MessageSend com este requestId (evita duplicidade)
                boolean existsByRequestId = messageSendRepository.existsByRequestId(requestId);
                if (existsByRequestId) {
                    skippedResults.add(new MessageSkippedResult(
                            lead.getId(),
                            lead.getBusinessName(),
                            "Ja existe envio com esse requestId."
                    ));
                    continue;
                }

                // Criar MessageSend com snapshot
                MessageSend send = MessageSend.builder()
                        .leadId(lead.getId())
                        .campaignId(campaign.getId())
                        .provider("whatsapp")
                        .status("QUEUED")
                        .attempts(0)
                        .requestId(requestId)
                        .templateId(template.getId())
                        .messageTextSnapshot(rendered)
                        .build();

                messageSendRepository.save(send);
                sentResults.add(new MessageSentResult(
                        send.getId(),
                        lead.getBusinessName(),
                        lead.getPhone(),
                        "QUEUED"
                ));

            } catch (RuntimeException e) {
                // Lead não elegível - adicionar ao skipped
                skippedResults.add(new MessageSkippedResult(
                        lead.getId(),
                        lead.getBusinessName(),
                        e.getMessage()
                ));
            }
        }

        return new SendResultEnqueue(
                sentResults,
                skippedResults
        );
    }

    private String generateRequestId(Long leadId, Long campaignId) {
        // UUID determinístico baseado em leadId + campaignId
        return "gendaz-" + campaignId + "-" + leadId;
    }
}