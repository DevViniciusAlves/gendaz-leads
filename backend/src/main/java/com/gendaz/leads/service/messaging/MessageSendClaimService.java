package com.gendaz.leads.service.messaging;

import com.gendaz.leads.entity.MessageSend;
import com.gendaz.leads.repository.MessageSendRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;

@Service
public class MessageSendClaimService {

    private final MessageSendRepository messageSendRepository;

    public MessageSendClaimService(MessageSendRepository messageSendRepository) {
        this.messageSendRepository = messageSendRepository;
    }

    @Transactional
    public MessageSend claimNextDue() {
        Optional<MessageSend> optionalSend = messageSendRepository.claimNextDueNative(Instant.now());

        if (optionalSend.isEmpty()) return null;

        MessageSend send = optionalSend.get();
        send.setStatus("SENDING");
        send.setAttempts(send.getAttempts() + 1);
        send.setLastAttemptAt(Instant.now());
        return messageSendRepository.saveAndFlush(send);
    }
}
