package com.gendaz.leads.service;

import com.gendaz.leads.dto.campaign.*;
import com.gendaz.leads.entity.Lead;
import com.gendaz.leads.entity.MessageSend;
import com.gendaz.leads.entity.MessageTemplate;
import com.gendaz.leads.entity.Campaign;
import com.gendaz.leads.repository.CampaignRepository;
import com.gendaz.leads.repository.LeadRepository;
import com.gendaz.leads.repository.MessageSendRepository;
import com.gendaz.leads.repository.MessageTemplateRepository;
import com.gendaz.leads.service.messaging.MessagingCommand;
import com.gendaz.leads.service.messaging.MessagingProviderRouter;
import com.gendaz.leads.service.provider.NicheMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class CampaignSendService {

    private final CampaignRepository campaignRepository;
    private final LeadRepository leadRepository;
    private final MessageSendRepository messageSendRepository;
    private final MessageTemplateRepository templateRepository;
    private final TemplateRenderer templateRenderer;
    private final LeadMessagingEligibilityService eligibilityService;
    private final MessagingProviderRouter providerRouter;
    private final WhatsAppRecipientNormalizer recipientNormalizer;

    @Value("${app.messaging.provider:whatsapp}")
    private String defaultProvider;

    public CampaignSendService(CampaignRepository campaignRepository,
                               LeadRepository leadRepository,
                               MessageSendRepository messageSendRepository,
                               MessageTemplateRepository templateRepository,
                               TemplateRenderer templateRenderer,
                               LeadMessagingEligibilityService eligibilityService,
                               MessagingProviderRouter providerRouter,
                               WhatsAppRecipientNormalizer recipientNormalizer) {
        this.campaignRepository = campaignRepository;
        this.leadRepository = leadRepository;
        this.messageSendRepository = messageSendRepository;
        this.templateRepository = templateRepository;
        this.templateRenderer = templateRenderer;
        this.eligibilityService = eligibilityService;
        this.providerRouter = providerRouter;
        this.recipientNormalizer = recipientNormalizer;
    }

    @Transactional(readOnly = true)
    public SendPreviewResponse generatePreview(Long campaignId, List<Long> leadIds,
                                                 Boolean allEligible) {
        Campaign campaign = campaignRepository.findById(campaignId)
                .orElseThrow(() -> new RuntimeException("Campanha nao encontrada."));

        String textToRender = templateRepository.findFirstByIsDefaultTrue()
                .map(MessageTemplate::getTemplateText)
                .orElse("");

        List<Lead> leads;
        if (leadIds != null && !leadIds.isEmpty()) {
            leads = leadRepository.findAllById(leadIds);
        } else if (allEligible != null && allEligible) {
            leads = leadRepository.findByCampaign(campaignId);
        } else {
            return new SendPreviewResponse(0, 0, new ArrayList<>(), new ArrayList<>());
        }

        List<CampaignMessagingLeadResponse> eligibleResponses = new ArrayList<>();
        List<CampaignMessagingLeadResponse> ineligibleResponses = new ArrayList<>();

        for (Lead lead : leads) {
            EligibilityResult eligibility = eligibilityService.checkEligibility(lead, campaignId);
            if (eligibility.eligible()) {
                try {
                    String rendered = templateRenderer.render(textToRender, lead, campaign);
                    String instagram = lead.getInstagramUsername() != null ? "@" + lead.getInstagramUsername() : null;
                    String cityState = lead.getCity() != null ? lead.getCity() + "/" + lead.getState() : null;
                    eligibleResponses.add(new CampaignMessagingLeadResponse(
                            lead.getId(),
                            lead.getBusinessName(),
                            lead.getCategory(),
                            lead.getInstagramUsername(),
                            lead.getInstagramUrl() != null ? lead.getInstagramUrl() : null,
                            lead.getPhone(),
                            lead.getCity(),
                            lead.getState(),
                            lead.getWebsite(),
                            lead.getOpportunityScore(),
                            lead.getDetectedSystem(),
                            lead.getStatus(),
                            eligibility.eligible(),
                            eligibility.code(),
                            eligibility.reason(),
                            eligibility.normalizedRecipient()
                    ));
                } catch (Exception e) {
                    // If rendering fails, still mark as eligible but with rendering error
                    eligibleResponses.add(new CampaignMessagingLeadResponse(
                            lead.getId(),
                            lead.getBusinessName(),
                            lead.getCategory(),
                            lead.getInstagramUsername(),
                            lead.getInstagramUrl() != null ? lead.getInstagramUrl() : null,
                            lead.getPhone(),
                            lead.getCity(),
                            lead.getState(),
                            lead.getWebsite(),
                            lead.getOpportunityScore(),
                            lead.getDetectedSystem(),
                            lead.getStatus(),
                            false,
                            "TEMPLATE_ERROR",
                            "Erro ao renderizar template: " + e.getMessage(),
                            eligibility.normalizedRecipient()
                    ));
                }
            } else {
                ineligibleResponses.add(new CampaignMessagingLeadResponse(
                        lead.getId(),
                        lead.getBusinessName(),
                        lead.getCategory(),
                        lead.getInstagramUsername(),
                        lead.getInstagramUrl() != null ? lead.getInstagramUrl() : null,
                        lead.getPhone(),
                        lead.getCity(),
                        lead.getState(),
                        lead.getWebsite(),
                        lead.getOpportunityScore(),
                        lead.getDetectedSystem(),
                        lead.getStatus(),
                        eligibility.eligible(),
                        eligibility.code(),
                        eligibility.reason(),
                        eligibility.normalizedRecipient()
                ));
            }
        }

        List<PreviewItem> previews = new ArrayList<>();
        for (CampaignMessagingLeadResponse resp : eligibleResponses) {
            String rendered = "";
            try {
                Lead lead = leadRepository.findById(resp.leadId()).orElse(null);
                if (lead != null) {
                    rendered = templateRenderer.render(textToRender, lead, campaign);
                }
            } catch (Exception e) {
                rendered = "";
            }
            previews.add(new PreviewItem(
                    resp.leadId(),
                    resp.businessName(),
                    resp.instagramUsername(),
                    resp.phone(),
                    resp.city() != null ? resp.city() + "/" + resp.state() : null,
                    rendered
            ));
        }

        int ineligibleCount = ineligibleResponses.size();

        return new SendPreviewResponse(
                eligibleResponses.size(),
                ineligibleCount,
                previews,
                ineligibleResponses.stream()
                        .map(r -> r.businessName() + " - " + r.ineligibilityCode() + ": " + r.ineligibilityReason())
                        .collect(Collectors.toList())
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
            EligibilityResult eligibility = eligibilityService.checkEligibility(lead, campaignId);
            if (!eligibility.eligible()) {
                skippedResults.add(new MessageSkippedResult(
                        lead.getId(),
                        lead.getBusinessName(),
                        eligibility.code() + ": " + eligibility.reason()
                ));
                continue;
            }

            String normalizedRecipient = eligibility.normalizedRecipient();
            if (normalizedRecipient == null || normalizedRecipient.isBlank()) {
                skippedResults.add(new MessageSkippedResult(
                        lead.getId(),
                        lead.getBusinessName(),
                        "NO_PHONE: Sem telefone utilizavel."
                ));
                continue;
            }

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
                    .recipientSnapshot(normalizedRecipient)
                    .queuedAt(Instant.now())
                    .build();

            messageSendRepository.save(send);
            sentResults.add(new MessageSentResult(
                    send.getId(),
                    lead.getBusinessName(),
                    normalizedRecipient,
                    "QUEUED"
            ));
        }

        return new SendResultEnqueue(sentResults, skippedResults);
    }
}