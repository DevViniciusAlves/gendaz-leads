package com.gendaz.leads.service;

import com.gendaz.leads.dto.campaign.MessageSentResult;
import com.gendaz.leads.entity.*;
import com.gendaz.leads.exception.ApiException;
import com.gendaz.leads.repository.*;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CampaignMessageEnqueueTransactionService {

    private final MessageSendRepository messageSendRepository;
    private final LeadEventRepository leadEventRepository;

    public CampaignMessageEnqueueTransactionService(MessageSendRepository messageSendRepository,
                                                    LeadEventRepository leadEventRepository) {
        this.messageSendRepository = messageSendRepository;
        this.leadEventRepository = leadEventRepository;
    }

    @Transactional
    public MessageSentResult enqueue(MessageSend send, Lead lead, Campaign campaign) {
        // Advisory lock por recipient
        messageSendRepository.acquireLock(send.getRecipientSnapshot());

        try {
            messageSendRepository.saveAndFlush(send);
        } catch (DataIntegrityViolationException e) {
            throw new ApiException(HttpStatus.CONFLICT, "ALREADY_QUEUED_OR_SENT",
                    "Lead já possui envio na fila ou concluído.");
        }

        leadEventRepository.save(LeadEvent.builder()
                .leadId(lead.getId())
                .campaignId(campaign.getId())
                .eventType("message_queued")
                .eventMetadata("messageSendId=" + send.getId() + ";status=QUEUED")
                .build());

        return new MessageSentResult(
                send.getId(),
                lead.getBusinessName(),
                send.getRecipientSnapshot(),
                "QUEUED"
        );
    }

}
