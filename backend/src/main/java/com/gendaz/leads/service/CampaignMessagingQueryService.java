package com.gendaz.leads.service;

import com.gendaz.leads.dto.campaign.CampaignMessagingLeadResponse;
import com.gendaz.leads.entity.Campaign;
import com.gendaz.leads.entity.Lead;
import com.gendaz.leads.entity.LeadAnalysis;
import com.gendaz.leads.entity.MessageSend;
import com.gendaz.leads.repository.LeadAnalysisRepository;
import com.gendaz.leads.repository.LeadRepository;
import com.gendaz.leads.repository.MessageSendRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class CampaignMessagingQueryService {

    private final CampaignService campaignService;
    private final LeadRepository leadRepository;
    private final LeadAnalysisRepository leadAnalysisRepository;
    private final MessageSendRepository messageSendRepository;
    private final LeadMessagingEligibilityService eligibilityService;

    public CampaignMessagingQueryService(CampaignService campaignService,
                                        LeadRepository leadRepository,
                                        LeadAnalysisRepository leadAnalysisRepository,
                                        MessageSendRepository messageSendRepository,
                                        LeadMessagingEligibilityService eligibilityService) {
        this.campaignService = campaignService;
        this.leadRepository = leadRepository;
        this.leadAnalysisRepository = leadAnalysisRepository;
        this.messageSendRepository = messageSendRepository;
        this.eligibilityService = eligibilityService;
    }

    @Transactional(readOnly = true)
    public List<CampaignMessagingLeadResponse> listMessagingLeads(Long campaignId) {
        Campaign campaign = campaignService.requireOwnedCampaign(campaignId);

        List<Lead> leads = leadRepository.findByCampaign(campaign.getId());
        if (leads.isEmpty()) return List.of();

        List<Long> leadIds = leads.stream().map(Lead::getId).toList();

        // Batch: analyses
        Map<Long, LeadAnalysis> analysisByLead = new HashMap<>();
        for (LeadAnalysis a : leadAnalysisRepository.findByLeadIdIn(leadIds)) {
            analysisByLead.put(a.getLeadId(), a);
        }

        // Batch: message sends of this campaign, latest per lead
        Map<Long, MessageSend> latestSendByLead = new HashMap<>();
        for (MessageSend s : messageSendRepository.findByCampaignId(campaign.getId())) {
            MessageSend current = latestSendByLead.get(s.getLeadId());
            if (current == null || compareSends(s, current) > 0) {
                latestSendByLead.put(s.getLeadId(), s);
            }
        }
        Map<Long, List<MessageSend>> sendsByLead = eligibilityService.loadSendsByLead(leadIds);

        List<CampaignMessagingLeadResponse> response = new ArrayList<>();
        for (Lead lead : leads) {
            MessageSend latestSend = latestSendByLead.get(lead.getId());
            LeadAnalysis analysis = analysisByLead.get(lead.getId());

            EligibilityResult eligibility = eligibilityService.checkEligibility(
                    lead, campaign.getId(), sendsByLead.getOrDefault(lead.getId(), List.of()));

            response.add(new CampaignMessagingLeadResponse(
                    lead.getId(),
                    lead.getBusinessName(),
                    lead.getCategory(),
                    lead.getInstagramUsername(),
                    lead.getInstagramUrl(),
                    lead.getPhone(),
                    lead.getEmail(),
                    lead.getCity(),
                    lead.getState(),
                    lead.getCountry(),
                    lead.getWebsite(),
                    analysis != null ? analysis.getOpportunityScore() : null,
                    analysis != null ? analysis.getDetectedSystem() : null,
                    lead.getStatus(),
                    eligibility.eligible(),
                    eligibility.code(),
                    eligibility.reason(),
                    latestSend != null ? latestSend.getId() : null,
                    latestSend != null ? latestSend.getStatus() : null,
                    latestSend != null ? latestSend.getAttempts() : 0,
                    latestSend != null ? latestSend.getQueuedAt() : null,
                    latestSend != null ? latestSend.getSentAt() : null,
                    latestSend != null ? latestSend.getErrorCode() : null
            ));
        }
        return response;
    }

    private int compareSends(MessageSend a, MessageSend b) {
        if (a.getCreatedAt() != null && b.getCreatedAt() != null) {
            int c = a.getCreatedAt().compareTo(b.getCreatedAt());
            if (c != 0) return c;
        }
        return Long.compare(
                a.getId() != null ? a.getId() : 0L,
                b.getId() != null ? b.getId() : 0L);
    }
}
