package com.gendaz.leads.repository;

import com.gendaz.leads.entity.CampaignLead;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CampaignLeadRepository extends JpaRepository<CampaignLead, Long> {
    boolean existsByCampaignIdAndLeadId(Long campaignId, Long leadId);
    void deleteByCampaignIdAndLeadId(Long campaignId, Long leadId);
    long countByCampaignId(Long campaignId);
}
