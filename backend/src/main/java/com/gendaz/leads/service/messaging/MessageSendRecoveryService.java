package com.gendaz.leads.service.messaging;

import com.gendaz.leads.entity.MessageSend;
import com.gendaz.leads.repository.MessageSendRepository;
import com.gendaz.leads.repository.LeadEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

@Service
public class MessageSendRecoveryService {

    private static final Logger log = LoggerFactory.getLogger(MessageSendRecoveryService.class);

    private final MessageSendRepository messageSendRepository;
    private final LeadEventRepository leadEventRepository;
    private final MessagingScheduleProperties scheduleProperties;

    public MessageSendRecoveryService(MessageSendRepository messageSendRepository,
                                      LeadEventRepository leadEventRepository,
                                      MessagingScheduleProperties scheduleProperties) {
        this.messageSendRepository = messageSendRepository;
        this.leadEventRepository = leadEventRepository;
        this.scheduleProperties = scheduleProperties;
    }

    @Scheduled(fixedDelayString = "#{@messagingScheduleProperties.stuckSendingThresholdMinutes * 60 * 1000}")
    @Transactional
    public void recoverStuckSending() {
        Instant threshold = Instant.now().minusSeconds(scheduleProperties.getStuckSendingThresholdMinutes() * 60L);
        List<MessageSend> stuck = messageSendRepository.findStuckSending(threshold);

        if (stuck.isEmpty()) {
            return;
        }

        log.info("Recuperando {} envio(s) SENDING travado(s) (threshold: {} min)", stuck.size(), scheduleProperties.getStuckSendingThresholdMinutes());

        for (MessageSend send : stuck) {
            send.setStatus("DELIVERY_UNKNOWN");
            send.setErrorCode("STUCK_SENDING_RECOVERY");
            send.setResult("Envio travado em SENDING por mais de " + scheduleProperties.getStuckSendingThresholdMinutes() + " min; provider pode ter enviado.");
            messageSendRepository.saveAndFlush(send);

            leadEventRepository.save(com.gendaz.leads.entity.LeadEvent.builder()
                    .leadId(send.getLeadId())
                    .campaignId(send.getCampaignId())
                    .eventType("message_delivery_unknown")
                    .eventMetadata("messageSendId=" + send.getId() + ";reason=STUCK_SENDING_RECOVERY")
                    .build());
        }
    }

}