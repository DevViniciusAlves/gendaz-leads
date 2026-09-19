package com.gendaz.leads.service;

import com.gendaz.leads.entity.Campaign;
import com.gendaz.leads.repository.CampaignLeadRepository;
import com.gendaz.leads.repository.CampaignRepository;
import com.gendaz.leads.repository.LeadRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class CampaignCounterService {

    private static final List<String> MESSAGE_READY_LIKE = List.of(
            "MESSAGE_READY", "APPROVED", "SENT", "REPLIED", "INTERESTED", "SCHEDULED", "CONVERTED");
    private static final List<String> APPROVED_LIKE = List.of(
            "APPROVED", "SENT", "REPLIED", "INTERESTED", "SCHEDULED", "CONVERTED");
    private static final List<String> SENT_LIKE = List.of(
            "SENT", "REPLIED", "INTERESTED", "SCHEDULED", "CONVERTED");
    private static final List<String> REPLIED_LIKE = List.of(
            "REPLIED", "INTERESTED", "SCHEDULED", "CONVERTED");
    private static final List<String> INTERESTED_LIKE = List.of(
            "INTERESTED", "SCHEDULED", "CONVERTED");
    private static final List<String> ANALYZED_LIKE = List.of(
            "ANALYZED", "MESSAGE_READY", "APPROVED", "SENT", "REPLIED", "INTERESTED", "SCHEDULED", "CONVERTED");

    private final CampaignRepository campaignRepository;
    private final LeadRepository leadRepository;
    private final CampaignLeadRepository campaignLeadRepository;

    public CampaignCounterService(CampaignRepository campaignRepository, 
                                  LeadRepository leadRepository,
                                  CampaignLeadRepository campaignLeadRepository) {
        this.campaignRepository = campaignRepository;
        this.leadRepository = leadRepository;
        this.campaignLeadRepository = campaignLeadRepository;
    }

    @Transactional
    public void recount(Long campaignId) {
        if (campaignId == null) return;
        Campaign campaign = campaignRepository.findById(campaignId).orElse(null);
        if (campaign == null) return;
        
        campaign.setDiscoveredCount((int) campaignLeadRepository.countByCampaignId(campaignId));
        campaign.setAnalyzedCount((int) leadRepository.countByCurrentCampaignIdWithAnalysis(campaignId));
        campaign.setMessageCount((int) leadRepository.countByCurrentCampaignIdAndStatusIn(campaignId, MESSAGE_READY_LIKE));
        campaign.setApprovedCount((int) leadRepository.countByCurrentCampaignIdAndStatusIn(campaignId, APPROVED_LIKE));
        campaign.setSentCount((int) leadRepository.countByCurrentCampaignIdAndStatusIn(campaignId, SENT_LIKE));
        campaign.setRepliedCount((int) leadRepository.countByCurrentCampaignIdAndStatusIn(campaignId, REPLIED_LIKE));
        campaign.setInterestedCount((int) leadRepository.countByCurrentCampaignIdAndStatusIn(campaignId, INTERESTED_LIKE));
        campaign.setConvertedCount((int) leadRepository.countByCurrentCampaignIdAndStatusIn(campaignId, List.of("CONVERTED")));
        campaign.setBlockedCount((int) leadRepository.countByCurrentCampaignIdAndDoNotContactTrue(campaignId));
        
        campaignRepository.save(campaign);
    }
}
