package com.gendaz.leads.repository;

import com.gendaz.leads.entity.LeadMessage;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface LeadMessageRepository extends JpaRepository<LeadMessage, Long> {
    Optional<LeadMessage> findByLeadId(Long leadId);
    boolean existsByLeadId(Long leadId);
}
