package com.gendaz.leads.service;

import com.gendaz.leads.dto.campaign.MessageSentResult;
import com.gendaz.leads.dto.campaign.MessageSkippedResult;
import com.gendaz.leads.dto.campaign.PreviewItem;
import com.gendaz.leads.dto.campaign.SendPreviewResponse;
import com.gendaz.leads.dto.campaign.SendResultEnqueue;
import com.gendaz.leads.entity.Campaign;
import com.gendaz.leads.entity.Lead;
import com.gendaz.leads.entity.MessageSend;
import com.gendaz.leads.entity.MessageTemplate;
import com.gendaz.leads.repository.CampaignRepository;
import com.gendaz.leads.repository.LeadRepository;
import com.gendaz.leads.repository.MessageSendRepository;
import com.gendaz.leads.repository.MessageTemplateRepository;
import com.gendaz.leads.util.Normalizer;
import com.gendaz.leads.exception.ApiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
public class CampaignSendService {

    private final CampaignRepository campaignRepository;
    private final LeadRepository leadRepository;
    private final MessageSendRepository messageSendRepository;
    private final MessageTemplateRepository templateRepository;
    private final TemplateRenderer templateRenderer;
    private final Normalizer normalizer;

    @Value("${app.messaging.provider:whatsapp}")
    private String defaultProvider;

    public CampaignSendService(CampaignRepository campaignRepository,
                               LeadRepository leadRepository,
                               MessageSendRepository messageSendRepository,
                               MessageTemplateRepository templateRepository,
                               TemplateRenderer templateRenderer,
                               Normalizer normalizer) {
        this.campaignRepository = campaignRepository;
        this.leadRepository = leadRepository;
        this.messageSendRepository = messageSendRepository;
        this.templateRepository = templateRepository;
        this.templateRenderer = templateRenderer;
        this.normalizer = normalizer;
    }

    public Lead checkEligibility(Lead lead, Long campaignId) {
        if (!campaignId.equals(lead.getCurrentCampaignId())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "LEAD_WRONG_CAMPAIGN", "Lead não pertence a esta campanha.");
        }
        if (lead.isDoNotContact()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "LEAD_DNC", "Este lead nao prospectar (doNotContact).");
        }
        String normalizedPhone = normalizer.normalizePhone(lead.getPhone());
        if (normalizedPhone == null || normalizedPhone.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "LEAD_NO_PHONE", "Lead nao tem telefone utilizavel.");
        }
        return lead;
    }

    @Transactional(readOnly = true)
    public SendPreviewResponse generatePreview(Long campaignId, List<Long> leadIds,
                                                 String templateText, Boolean allEligible) {
        Campaign campaign = campaignRepository.findById(campaignId)
                .orElseThrow(() -> new RuntimeException("Campanha nao encontrada."));

        String textToRender = templateText;
        if (textToRender == null || textToRender.isBlank()) {
            textToRender = templateRepository.findFirstByIsDefaultTrue()
                .map(MessageTemplate::getTemplateText)
                .orElse("");
        }

        List<Lead> leads;
        if (leadIds != null && !leadIds.isEmpty()) {
            leads = leadRepository.findAllById(leadIds);
        } else if (allEligible != null && allEligible) {
            leads = leadRepository.findByCampaign(campaignId);
        } else {
            return new SendPreviewResponse(0, 0, new ArrayList<>(), new ArrayList<>());
        }

        List<Lead> eligible = new ArrayList<>();
        List<Lead> ineligible = new ArrayList<>();
        List<String> ineligibilityReasons = new ArrayList<>();

        for (Lead lead : leads) {
            try {
                checkEligibility(lead, campaignId);
                eligible.add(lead);
            } catch (ApiException e) {
                ineligible.add(lead);
                ineligibilityReasons.add(lead.getBusinessName() + " - " + e.getMessage());
            }
        }

        List<PreviewItem> previews = new ArrayList<>();
        for (Lead lead : eligible) {
            String rendered = templateRenderer.render(textToRender, lead, campaign);
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

    @Transactional
    public SendResultEnqueue enqueueMessages(Long campaignId, List<Long> leadIds, Long templateId, Boolean allEligible) {
        Campaign campaign = campaignRepository.findById(campaignId)
                .orElseThrow(() -> new RuntimeException("Campanha nao encontrada."));

        MessageTemplate template;
        if (templateId != null) {
            template = templateRepository.findById(templateId)
                .orElseThrow(() -> new RuntimeException("Template nao encontrado."));
        } else {
            template = templateRepository.findFirstByIsDefaultTrue()
                .orElseThrow(() -> new RuntimeException("Nenhum template padrao encontrado."));
        }

        List<Lead> leads;
        if (leadIds != null && !leadIds.isEmpty()) {
            leads = leadRepository.findAllById(leadIds);
        } else if (allEligible != null && allEligible) {
            leads = leadRepository.findByCampaign(campaignId);
        } else {
            return new SendResultEnqueue(new ArrayList<>(), new ArrayList<>());
        }

        List<MessageSentResult> sentResults = new ArrayList<>();
        List<MessageSkippedResult> skippedResults = new ArrayList<>();

        for (Lead lead : leads) {
            try {
                checkEligibility(lead, campaignId);
                String normalizedPhone = normalizer.normalizePhone(lead.getPhone());

                boolean alreadySent = messageSendRepository.existsByLeadIdAndStatus(lead.getId(), "SENT");
                if (alreadySent) {
                    skippedResults.add(new MessageSkippedResult(lead.getId(), lead.getBusinessName(), "Ja enviado anteriormente."));
                    continue;
                }

                String rendered = templateRenderer.render(template.getTemplateText(), lead, campaign);
                String requestId = UUID.randomUUID().toString();

                MessageSend send = MessageSend.builder()
                        .leadId(lead.getId())
                        .campaignId(campaign.getId())
                        .provider(defaultProvider)
                        .status("QUEUED")
                        .attempts(0)
                        .requestId(requestId)
                        .templateId(template.getId())
                        .messageTextSnapshot(rendered)
                        .recipientSnapshot(normalizedPhone)
                        .queuedAt(Instant.now())
                        .build();

                messageSendRepository.save(send);
                sentResults.add(new MessageSentResult(
                        send.getId(),
                        lead.getBusinessName(),
                        lead.getPhone(),
                        "QUEUED"
                ));

            } catch (Exception e) {
                skippedResults.add(new MessageSkippedResult(
                        lead.getId(),
                        lead.getBusinessName(),
                        e.getMessage()
                ));
            }
        }

        return new SendResultEnqueue(sentResults, skippedResults);
    }
}