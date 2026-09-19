package com.gendaz.leads.service.messaging;

import com.gendaz.leads.entity.MessageSend;
import com.gendaz.leads.repository.MessageSendRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

@Service
public class MessageSendClaimService {

    private final EntityManager entityManager;
    private final MessageSendRepository messageSendRepository;

    public MessageSendClaimService(EntityManager entityManager,
                                   MessageSendRepository messageSendRepository) {
        this.entityManager = entityManager;
        this.messageSendRepository = messageSendRepository;
    }

    @Transactional
    public MessageSend claimNextDue() {
        List<MessageSend> results = entityManager.createQuery(
                        "SELECT ms FROM MessageSend ms WHERE ms.status = 'QUEUED' " +
                                "AND (ms.nextAttemptAt IS NULL OR ms.nextAttemptAt <= :now) " +
                                "ORDER BY ms.nextAttemptAt ASC NULLS FIRST, ms.id ASC", MessageSend.class)
                .setParameter("now", Instant.now())
                .setMaxResults(1)
                .setLockMode(LockModeType.PESSIMISTIC_WRITE)
                .setHint("jakarta.persistence.lock.timeout", -2) // SKIP LOCKED
                .getResultList();

        if (results.isEmpty()) return null;

        MessageSend send = results.get(0);
        send.setStatus("SENDING");
        send.setAttempts(send.getAttempts() + 1);
        send.setLastAttemptAt(Instant.now());
        return messageSendRepository.saveAndFlush(send);
    }
}
