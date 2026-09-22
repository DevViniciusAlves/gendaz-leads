package com.gendaz.leads.service;

import com.gendaz.leads.domain.LeadCandidate;
import com.gendaz.leads.entity.Campaign;
import com.gendaz.leads.entity.CampaignLead;
import com.gendaz.leads.entity.Lead;
import com.gendaz.leads.entity.LeadSource;
import com.gendaz.leads.repository.CampaignLeadRepository;
import com.gendaz.leads.repository.LeadRepository;
import com.gendaz.leads.repository.LeadSourceRepository;
import com.gendaz.leads.util.Normalizer;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CampaignLeadPersistenceService {

    private final LeadRepository leadRepository;
    private final CampaignLeadRepository campaignLeadRepository;
    private final LeadSourceRepository leadSourceRepository;
    private final Normalizer normalizer;

    public CampaignLeadPersistenceService(LeadRepository leadRepository,
                                           CampaignLeadRepository campaignLeadRepository,
                                           LeadSourceRepository leadSourceRepository,
                                           Normalizer normalizer) {
        this.leadRepository = leadRepository;
        this.campaignLeadRepository = campaignLeadRepository;
        this.leadSourceRepository = leadSourceRepository;
        this.normalizer = normalizer;
    }

    @Transactional
    public Lead createLeadForCampaign(LeadCandidate candidate, Campaign campaign) {
        Lead lead = Lead.builder()
                .businessName(candidate.getBusinessName())
                .normalizedName(normalizer.normalizeName(candidate.getBusinessName()))
                .category(candidate.getCategory())
                .address(candidate.getAddress())
                .city(candidate.getCity())
                .state(candidate.getState())
                .country(candidate.getCountry())
                .phone(candidate.getPhone())
                .normalizedPhone(normalizer.normalizePhone(candidate.getPhone()))
                .email(candidate.getEmail())
                .normalizedEmail(normalizer.normalizeEmail(candidate.getEmail()))
                .website(candidate.getWebsite())
                .normalizedWebsite(normalizer.normalizeWebsite(candidate.getWebsite()))
                .instagramUsername(candidate.getInstagramUsername())
                .instagramUrl(candidate.getInstagramUrl())
                .instagramStatus(candidate.getInstagramStatus())
                .normalizedInstagram(normalizer.normalizeInstagram(
                        candidate.getInstagramUsername() != null ? candidate.getInstagramUsername() : candidate.getInstagramUrl()))
                .source(candidate.getSource())
                .sourceId(candidate.getSourceId())
                .normalizedSourceId(normalizer.normalizeSourceId(candidate.getSource(), candidate.getSourceId()))
                .doNotContact(false)
                .status("NEW")
                .currentCampaignId(campaign.getId())
                .build();
        lead = leadRepository.save(lead);

        campaignLeadRepository.save(CampaignLead.builder()
                .campaignId(campaign.getId()).leadId(lead.getId()).build());
        leadSourceRepository.save(LeadSource.builder()
                .leadId(lead.getId()).source(candidate.getSource())
                .sourceId(candidate.getSourceId()).sourceUrl(candidate.getWebsite()).build());
        return lead;
    }

    @Transactional
    public boolean linkExistingLeadToCampaign(Lead lead, Campaign campaign) {
        if (lead == null
                || lead.getId() == null
                || campaign == null
                || campaign.getId() == null) {
            return false;
        }

        if (campaignLeadRepository.existsByCampaignIdAndLeadId(
                campaign.getId(),
                lead.getId()
        )) {
            return false;
        }

        campaignLeadRepository.save(
                CampaignLead.builder()
                        .campaignId(campaign.getId())
                        .leadId(lead.getId())
                        .build()
        );

        return true;
    }
}
