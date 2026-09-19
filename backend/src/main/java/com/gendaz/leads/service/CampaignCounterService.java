package com.gendaz.leads.service;

import com.gendaz.leads.entity.Campaign;
import com.gendaz.leads.entity.Lead;
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

    private final CampaignRepository campaignRepository;
    private final LeadRepository leadRepository;

    public CampaignCounterService(CampaignRepository campaignRepository, LeadRepository leadRepository) {
        this.campaignRepository = campaignRepository;
        this.leadRepository = leadRepository;
    }

    @Transactional
    public void recount(Long campaignId) {
        if (campaignId == null) return;
        Campaign campaign = campaignRepository.findById(campaignId).orElse(null);
        if (campaign == null) return;
        List<Lead> leads = leadRepository.findByCampaign(campaignId);
        int approved = 0, sent = 0, replied = 0, interested = 0, converted = 0, blocked = 0, messages = 0;
        for (Lead l : leads) {
            if (l.isDoNotContact()) blocked++;
            if (MESSAGE_READY_LIKE.contains(l.getStatus())) messages++;
            if (APPROVED_LIKE.contains(l.getStatus())) approved++;
            if (SENT_LIKE.contains(l.getStatus())) sent++;
            if (REPLIED_LIKE.contains(l.getStatus())) replied++;
            if (INTERESTED_LIKE.contains(l.getStatus())) interested++;
            if ("CONVERTED".equals(l.getStatus())) converted++;
        }
        campaign.setApprovedCount(approved);
        campaign.setSentCount(sent);
        campaign.setRepliedCount(replied);
        campaign.setInterestedCount(interested);
        campaign.setConvertedCount(converted);
        campaign.setBlockedCount(blocked);
        campaign.setMessageCount(messages);
        campaignRepository.save(campaign);
    }
}
