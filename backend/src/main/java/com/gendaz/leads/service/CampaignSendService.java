package com.gendaz.leads.service;

import com.gendaz.leads.dto.campaign.*;
import com.gendaz.leads.entity.Campaign;
import com.gendaz.leads.entity.Lead;
import com.gendaz.leads.entity.LeadEvent;
import com.gendaz.leads.entity.MessageSend;
import com.gendaz.leads.entity.MessageTemplate;
import com.gendaz.leads.exception.ApiException;
import com.gendaz.leads.repository.CampaignLeadRepository;
import com.gendaz.leads.repository.LeadEventRepository;
import com.gendaz.leads.repository.LeadRepository;
import com.gendaz.leads.repository.MessageSendRepository;
import com.gendaz.leads.repository.MessageTemplateRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;

@Service
public class CampaignSendService {

    private final CampaignService campaignService;
    private final LeadRepository leadRepository;
    private final CampaignLeadRepository campaignLeadRepository;
    private final MessageSendRepository messageSendRepository;
    private final MessageTemplateRepository templateRepository;
    private final LeadEventRepository leadEventRepository;
    private final TemplateRenderer templateRenderer;
    private final LeadMessagingEligibilityService eligibilityService;

    @Value("${app.messaging.provider:whatsapp}")
    private String defaultProvider;

    public CampaignSendService(CampaignService campaignService,
                               LeadRepository leadRepository,
                               CampaignLeadRepository campaignLeadRepository,
                               MessageSendRepository messageSendRepository,
                               MessageTemplateRepository templateRepository,
                               LeadEventRepository leadEventRepository,
                               TemplateRenderer templateRenderer,
                               LeadMessagingEligibilityService eligibilityService) {
        this.campaignService = campaignService;
        this.leadRepository = leadRepository;
        this.campaignLeadRepository = campaignLeadRepository;
        this.messageSendRepository = messageSendRepository;
        this.templateRepository = templateRepository;
        this.leadEventRepository = leadEventRepository;
        this.templateRenderer = templateRenderer;
        this.eligibilityService = eligibilityService;
    }

    @Transactional(readOnly = true)
    public SendPreviewResponse generatePreview(Long campaignId, List<Long> leadIds,
                                              Boolean allEligible) {
        Campaign campaign = campaignService.requireOwnedCampaign(campaignId);

        MessageTemplate template = templateRepository.findFirstByIsDefaultTrue()
                .orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST, "NO_DEFAULT_TEMPLATE",
                        "Nenhum template padrão encontrado."));

        List<Lead> leads = resolveLeads(campaign, leadIds, allEligible);
        if (leads.isEmpty()) {
            return new SendPreviewResponse(0, 0, List.of(), List.of());
        }

        List<Long> ids = leads.stream().map(Lead::getId).toList();
        Map<Long, List<MessageSend>> sendsByLead = eligibilityService.loadSendsByLead(ids);

        List<PreviewItem> previews = new ArrayList<>();
        List<IneligibleLeadResponse> ineligible = new ArrayList<>();

        for (Lead lead : leads) {
            if (!isMemberOfCampaign(lead, campaign.getId())) {
                ineligible.add(new IneligibleLeadResponse(
                        lead.getId(), lead.getBusinessName(), "WRONG_CAMPAIGN",
                        "Lead não pertence à campanha."));
                continue;
            }
            EligibilityResult eligibility = eligibilityService.checkEligibility(
                    lead, campaign.getId(), sendsByLead.getOrDefault(lead.getId(), List.of()));
            if (!eligibility.eligible()) {
                ineligible.add(new IneligibleLeadResponse(
                        lead.getId(), lead.getBusinessName(), eligibility.code(), eligibility.reason()));
                continue;
            }
            // Template error must fail preview (not swallowed).
            String rendered = templateRenderer.render(template.getTemplateText(), lead, campaign);
            previews.add(new PreviewItem(lead.getId(), lead.getBusinessName(), rendered));
        }

        return new SendPreviewResponse(previews.size(), ineligible.size(), previews, ineligible);
    }

    @Transactional
    public SendResultEnqueue enqueueMessages(Long campaignId, List<Long> leadIds, Long templateId, Boolean allEligible) {
        Campaign campaign = campaignService.requireOwnedCampaign(campaignId);

        MessageTemplate template;
        if (templateId != null) {
            template = templateRepository.findById(templateId)
                    .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "TEMPLATE_NOT_FOUND",
                            "Template não encontrado."));
        } else {
            template = templateRepository.findFirstByIsDefaultTrue()
                    .orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST, "NO_DEFAULT_TEMPLATE",
                            "Nenhum template padrão encontrado."));
        }

        List<Lead> leads = resolveLeads(campaign, leadIds, allEligible);
        if (leads.isEmpty()) {
            return new SendResultEnqueue(new ArrayList<>(), new ArrayList<>());
        }

        List<Long> ids = leads.stream().map(Lead::getId).toList();
        Map<Long, List<MessageSend>> sendsByLead = eligibilityService.loadSendsByLead(ids);

        List<MessageSentResult> sentResults = new ArrayList<>();
        List<MessageSkippedResult> skippedResults = new ArrayList<>();

        for (Lead lead : leads) {
            if (!isMemberOfCampaign(lead, campaign.getId())) {
                skippedResults.add(new MessageSkippedResult(
                        lead.getId(), lead.getBusinessName(), "WRONG_CAMPAIGN: Lead não pertence à campanha."));
                continue;
            }
            EligibilityResult eligibility = eligibilityService.checkEligibility(
                    lead, campaign.getId(), sendsByLead.getOrDefault(lead.getId(), List.of()));
            if (!eligibility.eligible()) {
                skippedResults.add(new MessageSkippedResult(
                        lead.getId(), lead.getBusinessName(),
                        eligibility.code() + ": " + eligibility.reason()));
                continue;
            }

            String normalizedRecipient = eligibility.normalizedRecipient();
            if (normalizedRecipient == null || normalizedRecipient.isBlank()) {
                skippedResults.add(new MessageSkippedResult(
                        lead.getId(), lead.getBusinessName(), "NO_PHONE: Sem telefone utilizável."));
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

            try {
                messageSendRepository.saveAndFlush(send);
            } catch (DataIntegrityViolationException e) {
                throw new ApiException(HttpStatus.CONFLICT, "ALREADY_QUEUED_OR_SENT",
                        "Lead já possui envio na fila ou concluído.");
            }

            // Evento sem mensagem integral ou telefone no metadata.
            leadEventRepository.save(LeadEvent.builder()
                    .leadId(lead.getId())
                    .campaignId(campaign.getId())
                    .eventType("message_queued")
                    .eventMetadata("messageSendId=" + send.getId() + ";status=QUEUED")
                    .build());

            sentResults.add(new MessageSentResult(
                    send.getId(),
                    lead.getBusinessName(),
                    normalizedRecipient,
                    "QUEUED"
            ));
        }

        return new SendResultEnqueue(sentResults, skippedResults);
    }

    private List<Lead> resolveLeads(Campaign campaign, List<Long> leadIds, Boolean allEligible) {
        if (leadIds != null && !leadIds.isEmpty()) {
            List<Lead> found = leadRepository.findAllById(leadIds);
            // Preserva ordem solicitada
            Map<Long, Lead> byId = new HashMap<>();
            for (Lead l : found) byId.put(l.getId(), l);
            List<Lead> ordered = new ArrayList<>();
            for (Long id : leadIds) {
                Lead l = byId.get(id);
                if (l != null) ordered.add(l);
            }
            return ordered;
        }
        if (allEligible != null && allEligible) {
            return leadRepository.findByCampaign(campaign.getId());
        }
        return List.of();
    }

    private boolean isMemberOfCampaign(Lead lead, Long campaignId) {
        if (lead.getCurrentCampaignId() != null && lead.getCurrentCampaignId().equals(campaignId)) return true;
        return campaignLeadRepository.existsByCampaignIdAndLeadId(campaignId, lead.getId());
    }
}
