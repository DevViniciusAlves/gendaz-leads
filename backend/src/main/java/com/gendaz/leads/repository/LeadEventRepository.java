package com.gendaz.leads.repository;

import com.gendaz.leads.entity.LeadEvent;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LeadEventRepository extends JpaRepository<LeadEvent, Long> {
    Page<LeadEvent> findByLeadIdOrderByCreatedAtDesc(Long leadId, Pageable pageable);
    Page<LeadEvent> findByCampaignIdOrderByCreatedAtDesc(Long campaignId, Pageable pageable);
}
