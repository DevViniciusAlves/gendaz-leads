package com.gendaz.leads.repository;

import com.gendaz.leads.entity.LeadAnalysis;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LeadAnalysisRepository extends JpaRepository<LeadAnalysis, Long> {
    boolean existsByLeadId(Long leadId);
    void deleteByLeadId(Long leadId);
    java.util.Optional<LeadAnalysis> findByLeadId(Long leadId);
    java.util.List<LeadAnalysis> findByLeadIdIn(java.util.Collection<Long> leadIds);
}
