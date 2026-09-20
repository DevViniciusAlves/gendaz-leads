package com.gendaz.leads.service;

import com.gendaz.leads.dto.campaign.MessageSentResult;
import com.gendaz.leads.entity.*;
import com.gendaz.leads.exception.ApiException;
import com.gendaz.leads.repository.*;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class CampaignMessageEnqueueTransactionService {

    private final MessageSendRepository messageSendRepository;
    private final LeadEventRepository leadEventRepository;
    private final LeadRepository leadRepository;
    private final CampaignLeadRepository campaignLeadRepository;
    private final LeadMessagingEligibilityService eligibilityService;

    public CampaignMessageEnqueueTransactionService(MessageSendRepository messageSendRepository,
                                                    LeadEventRepository leadEventRepository,
                                                    LeadRepository leadRepository,
                                                    CampaignLeadRepository campaignLeadRepository,
                                                    LeadMessagingEligibilityService eligibilityService) {
        this.messageSendRepository = messageSendRepository;
        this.leadEventRepository = leadEventRepository;
        this.leadRepository = leadRepository;
        this.campaignLeadRepository = campaignLeadRepository;
        this.eligibilityService = eligibilityService;
    }

    @Transactional
    public MessageSentResult enqueue(MessageSend send, Lead lead, Campaign campaign) {
        // Reload lead inside transaction to get fresh state
        Lead freshLead = leadRepository.findById(lead.getId())
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "LEAD_NOT_FOUND", "Lead não encontrado."));

        // Revalidate membership
        if (!isMemberOfCampaign(freshLead, campaign.getId())) {
            throw new ApiException(HttpStatus.CONFLICT, "WRONG_CAMPAIGN", "Lead não pertence mais à campanha.");
        }

        // Revalidate eligibility (DNC, phone, blockers)
        EligibilityResult eligibility = eligibilityService.checkEligibility(freshLead, campaign.getId());
        if (!eligibility.eligible()) {
            throw new ApiException(HttpStatus.CONFLICT, eligibility.code(), eligibility.reason());
        }

        // Advisory lock por recipient
        messageSendRepository.acquireLock(eligibility.normalizedRecipient());

        // Re-check blockers after acquiring lock (race condition protection)
        List<MessageSend> blockers = messageSendRepository.findBlockingByRecipientSnapshot(
                eligibility.normalizedRecipient(), List.of("QUEUED", "SENDING", "SENT", "DELIVERY_UNKNOWN"));
        if (!blockers.isEmpty()) {
            // Check if any blocker is for this campaign or other campaign
            for (MessageSend blocker : blockers) {
                if (blocker.getCampaignId().equals(campaign.getId())) {
                    throw new ApiException(HttpStatus.CONFLICT, blocker.getStatus().equals("QUEUED") ? "ALREADY_QUEUED" :
                            blocker.getStatus().equals("SENDING") ? "ALREADY_SENDING" :
                            blocker.getStatus().equals("SENT") ? "ALREADY_SENT" : "DELIVERY_UNKNOWN",
                            "Bloqueio detectado após lock.");
                }
            }
            // Cross-campaign blocker
            MessageSend blocker = blockers.get(0);
            throw new ApiException(HttpStatus.CONFLICT, blocker.getStatus().equals("QUEUED") ? "ALREADY_QUEUED" :
                    blocker.getStatus().equals("SENDING") ? "ALREADY_SENDING" :
                    blocker.getStatus().equals("SENT") ? "ALREADY_SENT" : "DELIVERY_UNKNOWN",
                    "Destinatário já prospectado em outra campanha.");
        }

        // Update send with revalidated normalized recipient
        send.setRecipientSnapshot(eligibility.normalizedRecipient());

        try {
            messageSendRepository.saveAndFlush(send);
        } catch (DataIntegrityViolationException e) {
            throw new ApiException(HttpStatus.CONFLICT, "ALREADY_QUEUED_OR_SENT",
                    "Lead já possui envio na fila ou concluído.");
        }

        leadEventRepository.save(LeadEvent.builder()
                .leadId(freshLead.getId())
                .campaignId(campaign.getId())
                .eventType("message_queued")
                .eventMetadata("messageSendId=" + send.getId() + ";status=QUEUED")
                .build());

        return new MessageSentResult(
                send.getId(),
                freshLead.getBusinessName(),
                send.getRecipientSnapshot(),
                "QUEUED"
        );
    }

    private boolean isMemberOfCampaign(Lead lead, Long campaignId) {
        if (lead.getCurrentCampaignId() != null && lead.getCurrentCampaignId().equals(campaignId)) return true;
        return campaignLeadRepository.existsByCampaignIdAndLeadId(campaignId, lead.getId());
    }

}
