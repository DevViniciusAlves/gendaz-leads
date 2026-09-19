package com.gendaz.leads.repository;

import com.gendaz.leads.entity.MessageSend;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface MessageSendRepository extends JpaRepository<MessageSend, Long> {

    Page<MessageSend> findByStatusOrderByNextAttemptAtAsc(String status, Pageable pageable);

    List<MessageSend> findByLeadId(Long leadId);

    List<MessageSend> findByLeadIdIn(java.util.Collection<Long> leadIds);

    List<MessageSend> findByCampaignId(Long campaignId);

    long countByCampaignIdAndStatus(Long campaignId, String status);

    @Query("SELECT COUNT(ms) FROM MessageSend ms WHERE ms.campaignId = :campaignId")
    long countByCampaignId(@Param("campaignId") Long campaignId);

    @Query("SELECT ms FROM MessageSend ms WHERE ms.status = 'QUEUED' " +
            "AND (ms.nextAttemptAt IS NULL OR ms.nextAttemptAt <= :now) " +
            "ORDER BY ms.nextAttemptAt ASC NULLS FIRST")
    List<MessageSend> findDue(@Param("now") Instant now);

    boolean existsByLeadIdAndStatus(Long leadId, String status);

    boolean existsByRequestId(String requestId);
}
