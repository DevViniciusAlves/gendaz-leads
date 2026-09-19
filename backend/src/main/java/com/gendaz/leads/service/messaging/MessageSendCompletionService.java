package com.gendaz.leads.service.messaging;

import com.gendaz.leads.entity.Lead;
import com.gendaz.leads.entity.LeadEvent;
import com.gendaz.leads.entity.MessageSend;
import com.gendaz.leads.repository.LeadEventRepository;
import com.gendaz.leads.repository.LeadRepository;
import com.gendaz.leads.repository.MessageSendRepository;
import com.gendaz.leads.service.CampaignCounterService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

@Service
public class MessageSendCompletionService {

    private static final Logger log = LoggerFactory.getLogger(MessageSendCompletionService.class);

    private final MessageSendRepository messageSendRepository;
    private final LeadRepository leadRepository;
    private final LeadEventRepository leadEventRepository;
    private final CampaignCounterService counterService;

    public MessageSendCompletionService(MessageSendRepository messageSendRepository,
                                        LeadRepository leadRepository,
                                        LeadEventRepository leadEventRepository,
                                        CampaignCounterService counterService) {
        this.messageSendRepository = messageSendRepository;
        this.leadRepository = leadRepository;
        this.leadEventRepository = leadEventRepository;
        this.counterService = counterService;
    }

    @Transactional
    public void completeSuccess(Long sendId, String providerMessageId) {
        MessageSend send = messageSendRepository.findById(sendId).orElse(null);
        if (send == null) return;
        send.setStatus("SENT");
        send.setProviderMessageId(providerMessageId);
        send.setSentAt(Instant.now());
        send.setErrorCode(null);
        send.setResult("SENT");
        messageSendRepository.save(send);

        Lead lead = leadRepository.findById(send.getLeadId()).orElse(null);
        if (lead != null) {
            lead.setStatus("SENT");
            leadRepository.save(lead);
        }

        leadEventRepository.save(LeadEvent.builder()
                .leadId(send.getLeadId())
                .campaignId(send.getCampaignId())
                .eventType("message_sent")
                .eventMetadata("messageSendId=" + send.getId() + ";status=SENT")
                .build());

        if (send.getCampaignId() != null) counterService.recount(send.getCampaignId());
        log.info("message_sent sendId={} leadId={}", send.getId(), send.getLeadId());
    }

    @Transactional
    public void completeTerminalFailure(Long sendId, String errorCode, String detail) {
        MessageSend send = messageSendRepository.findById(sendId).orElse(null);
        if (send == null) return;
        send.setStatus("FAILED");
        send.setErrorCode(errorCode);
        send.setResult(detail);
        messageSendRepository.save(send);

        leadEventRepository.save(LeadEvent.builder()
                .leadId(send.getLeadId())
                .campaignId(send.getCampaignId())
                .eventType("message_failed")
                .eventMetadata("errorCode=" + errorCode + ";status=FAILED")
                .build());

        if (send.getCampaignId() != null) counterService.recount(send.getCampaignId());
    }

    @Transactional
    public void completeTransientRetry(Long sendId, String errorCode, String detail, Instant nextAttemptAt) {
        MessageSend send = messageSendRepository.findById(sendId).orElse(null);
        if (send == null) return;
        send.setStatus("QUEUED");
        send.setErrorCode(errorCode);
        send.setResult(detail);
        send.setNextAttemptAt(nextAttemptAt);
        messageSendRepository.save(send);
    }

    @Transactional
    public void completeMaxAttemptsFailed(Long sendId, String errorCode, String detail) {
        completeTerminalFailure(sendId, errorCode, detail);
    }

    @Transactional
    public void completeSkipped(Long sendId, String errorCode) {
        MessageSend send = messageSendRepository.findById(sendId).orElse(null);
        if (send == null) return;
        send.setStatus("SKIPPED");
        send.setErrorCode(errorCode);
        send.setResult(errorCode);
        messageSendRepository.save(send);

        leadEventRepository.save(LeadEvent.builder()
                .leadId(send.getLeadId())
                .campaignId(send.getCampaignId())
                .eventType("message_skipped")
                .eventMetadata("errorCode=" + errorCode + ";status=SKIPPED")
                .build());

        if (send.getCampaignId() != null) counterService.recount(send.getCampaignId());
    }

    @Transactional
    public void completeDeliveryUnknown(Long sendId, String errorCode, String detail) {
        MessageSend send = messageSendRepository.findById(sendId).orElse(null);
        if (send == null) return;
        send.setStatus("DELIVERY_UNKNOWN");
        send.setErrorCode(errorCode);
        send.setResult(detail);
        messageSendRepository.save(send);

        // NAO marca Lead SENT.
        leadEventRepository.save(LeadEvent.builder()
                .leadId(send.getLeadId())
                .campaignId(send.getCampaignId())
                .eventType("message_delivery_unknown")
                .eventMetadata("errorCode=" + errorCode + ";status=DELIVERY_UNKNOWN")
                .build());

        if (send.getCampaignId() != null) counterService.recount(send.getCampaignId());
    }
}
