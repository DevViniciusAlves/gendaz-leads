package com.gendaz.leads.service.messaging;

import com.gendaz.leads.entity.Campaign;
import com.gendaz.leads.entity.Lead;
import com.gendaz.leads.entity.LeadEvent;
import com.gendaz.leads.entity.LeadMessage;
import com.gendaz.leads.entity.MessageSend;
import com.gendaz.leads.repository.CampaignRepository;
import com.gendaz.leads.repository.LeadEventRepository;
import com.gendaz.leads.repository.LeadMessageRepository;
import com.gendaz.leads.repository.LeadRepository;
import com.gendaz.leads.repository.MessageSendRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

@Component
public class SendQueueProcessor {

    private static final Logger log = LoggerFactory.getLogger(SendQueueProcessor.class);
    private static final int MAX_ATTEMPTS = 5;

    @Value("${app.messaging.max-concurrent-sends:1}")
    private int maxConcurrentSends;

    @Value("${app.messaging.provider:log}")
    private String providerName;

    private final MessageSendRepository messageSendRepository;
    private final LeadRepository leadRepository;
    private final LeadMessageRepository leadMessageRepository;
    private final LeadEventRepository leadEventRepository;
    private final CampaignRepository campaignRepository;
    private final MessagingProvider messagingProvider;

    public SendQueueProcessor(MessageSendRepository messageSendRepository, LeadRepository leadRepository,
                              LeadMessageRepository leadMessageRepository, LeadEventRepository leadEventRepository,
                              CampaignRepository campaignRepository, MessagingProvider messagingProvider) {
        this.messageSendRepository = messageSendRepository;
        this.leadRepository = leadRepository;
        this.leadMessageRepository = leadMessageRepository;
        this.leadEventRepository = leadEventRepository;
        this.campaignRepository = campaignRepository;
        this.messagingProvider = messagingProvider;
    }

    @Scheduled(fixedDelayString = "${app.messaging.send-interval-seconds:30}000")
    @Transactional
    public void processQueue() {
        List<MessageSend> due = messageSendRepository.findDue(Instant.now());
        int processed = 0;
        for (MessageSend send : due) {
            if (processed >= maxConcurrentSends) break;
            try {
                processOne(send);
                processed++;
            } catch (RuntimeException e) {
                log.warn("Erro ao processar envio {}: {}", send.getId(), e.getMessage());
            }
        }
    }

    private void processOne(MessageSend send) {
        send.setStatus("SENDING");
        send.setAttempts(send.getAttempts() + 1);
        send.setLastAttemptAt(Instant.now());
        messageSendRepository.save(send);

        Lead lead = leadRepository.findById(send.getLeadId()).orElse(null);
        if (lead == null) {
            send.setStatus("FAILED");
            send.setResult("Lead nao encontrado.");
            messageSendRepository.save(send);
            return;
        }
        LeadMessage msg = leadMessageRepository.findByLeadId(lead.getId()).orElse(null);
        String text = msg != null ? msg.getMessageText() : "";

        MessagingProvider.SendResult result = messagingProvider.send(lead, text);
        if (result.success()) {
            send.setStatus("SENT");
            send.setResult(result.detail());
            lead.setStatus("SENT");
            leadRepository.save(lead);
            leadEventRepository.save(LeadEvent.builder()
                    .leadId(lead.getId()).campaignId(send.getCampaignId())
                    .eventType("lead_status_changed").eventMetadata("SENT").build());
            if (send.getCampaignId() != null) {
                recountCampaign(send.getCampaignId());
            }
        } else {
            if (send.getAttempts() >= MAX_ATTEMPTS) {
                send.setStatus("FAILED");
            } else {
                send.setStatus("QUEUED");
                send.setNextAttemptAt(Instant.now().plus(2 * send.getAttempts(), ChronoUnit.MINUTES));
            }
            send.setResult(result.detail());
        }
        messageSendRepository.save(send);
    }

    private void recountCampaign(Long campaignId) {
        Campaign campaign = campaignRepository.findById(campaignId).orElse(null);
        if (campaign == null) return;
        List<Lead> leads = leadRepository.findByCampaign(campaignId);
        int approved = 0, sent = 0, replied = 0, interested = 0, converted = 0, blocked = 0, messages = 0;
        for (Lead l : leads) {
            if (l.isDoNotContact()) blocked++;
            String s = l.getStatus();
            if (List.of("MESSAGE_READY", "ANALYZED", "APPROVED", "SENT", "REPLIED", "INTERESTED", "SCHEDULED", "CONVERTED").contains(s)) messages++;
            if (List.of("APPROVED", "SENT", "REPLIED", "INTERESTED", "SCHEDULED", "CONVERTED").contains(s)) approved++;
            if (List.of("SENT", "REPLIED", "INTERESTED", "SCHEDULED", "CONVERTED").contains(s)) sent++;
            if (List.of("REPLIED", "INTERESTED", "SCHEDULED", "CONVERTED").contains(s)) replied++;
            if (List.of("INTERESTED", "SCHEDULED", "CONVERTED").contains(s)) interested++;
            if ("CONVERTED".equals(s)) converted++;
        }
        campaign.setApprovedCount(approved);
        campaign.setSentCount(sent);
        campaign.setRepliedCount(replied);
        campaign.setInterestedCount(interested);
        campaign.setConvertedCount(converted);
        campaign.setBlockedCount(blocked);
        campaign.setMessageCount(messages);
        campaignRepository.save(campaign);
    }
}
