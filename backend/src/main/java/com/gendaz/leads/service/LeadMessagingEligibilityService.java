package com.gendaz.leads.service;

import com.gendaz.leads.entity.Lead;
import com.gendaz.leads.entity.MessageSend;
import com.gendaz.leads.repository.MessageSendRepository;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class LeadMessagingEligibilityService {

    private final MessageSendRepository messageSendRepository;
    private final WhatsAppRecipientNormalizer recipientNormalizer;

    public LeadMessagingEligibilityService(MessageSendRepository messageSendRepository,
                                          WhatsAppRecipientNormalizer recipientNormalizer) {
        this.messageSendRepository = messageSendRepository;
        this.recipientNormalizer = recipientNormalizer;
    }

    public EligibilityResult checkEligibility(Lead lead, Long campaignId) {
        List<MessageSend> sends = (lead != null && lead.getId() != null)
                ? messageSendRepository.findByLeadId(lead.getId())
                : List.of();
        return checkEligibility(lead, campaignId, sends);
    }

    public EligibilityResult checkEligibility(Lead lead, Long campaignId, List<MessageSend> sendsForLead) {
        if (lead == null) {
            return new EligibilityResult(false, "WRONG_CAMPAIGN", "Lead nulo.", null);
        }

        if (campaignId != null && lead.getCurrentCampaignId() != null
                && !lead.getCurrentCampaignId().equals(campaignId)) {
            return new EligibilityResult(false, "WRONG_CAMPAIGN", "Lead não pertence à campanha.", null);
        }

        if (lead.isDoNotContact()) {
            return new EligibilityResult(false, "DO_NOT_CONTACT", "Lead marcado como não prospectar.", null);
        }

        if (lead.getPhone() == null || lead.getPhone().isBlank()) {
            return new EligibilityResult(false, "NO_PHONE", "Lead sem telefone.", null);
        }

        String normalized = recipientNormalizer.normalizeForWhatsApp(lead.getPhone(), lead.getCountry());
        if (normalized == null || normalized.isBlank()) {
            return new EligibilityResult(false, "INVALID_PHONE", "Telefone inválido para WhatsApp.", null);
        }

        List<MessageSend> sends = sendsForLead != null ? sendsForLead : List.of();
        for (MessageSend send : sends) {
            String status = send.getStatus();
            if ("QUEUED".equals(status)) {
                return new EligibilityResult(false, "ALREADY_QUEUED", "Lead já está na fila de envio nesta campanha.", normalized);
            }
            if ("SENDING".equals(status)) {
                return new EligibilityResult(false, "ALREADY_SENDING", "Lead está em envio nesta campanha.", normalized);
            }
            if ("SENT".equals(status)) {
                return new EligibilityResult(false, "ALREADY_SENT", "Lead já recebeu mensagem nesta campanha.", normalized);
            }
            if ("DELIVERY_UNKNOWN".equals(status)) {
                return new EligibilityResult(false, "DELIVERY_UNKNOWN", "Existe uma entrega anterior incerta nesta campanha.", normalized);
            }
        }

        // Global Cross-Campaign Blocking: checa se o mesmo normalizedRecipient já foi prospectado
        // em QUALQUER outra campanha.
        if (normalized != null) {
            List<MessageSend> blockers = messageSendRepository.findBlockingByRecipientSnapshot(
                    normalized, List.of("QUEUED", "SENDING", "SENT", "DELIVERY_UNKNOWN"));
            
            // Filtra: mantem apenas sends de outras campanhas
            blockers = blockers.stream().filter(b -> !b.getCampaignId().equals(campaignId)).toList();
            
            if (!blockers.isEmpty()) {
                MessageSend blocker = blockers.get(0);
                String status = blocker.getStatus();
                if ("QUEUED".equals(status)) {
                    return new EligibilityResult(false, "ALREADY_QUEUED", "Lead já está na fila de outra campanha.", normalized);
                }
                if ("SENDING".equals(status)) {
                    return new EligibilityResult(false, "ALREADY_SENDING", "Lead possui envio em andamento em outra campanha.", normalized);
                }
                if ("SENT".equals(status)) {
                    return new EligibilityResult(false, "ALREADY_SENT", "Lead já recebeu mensagem em outra campanha.", normalized);
                }
                if ("DELIVERY_UNKNOWN".equals(status)) {
                    return new EligibilityResult(false, "DELIVERY_UNKNOWN", "Existe uma entrega anterior incerta em outra campanha.", normalized);
                }
            }
        }

        return new EligibilityResult(true, null, null, normalized);
    }

    public Map<Long, List<MessageSend>> loadSendsByLead(Collection<Long> leadIds) {
        Map<Long, List<MessageSend>> byLead = new HashMap<>();
        if (leadIds == null || leadIds.isEmpty()) return byLead;
        List<MessageSend> all = messageSendRepository.findByLeadIdIn(leadIds);
        for (MessageSend s : all) {
            byLead.computeIfAbsent(s.getLeadId(), k -> new java.util.ArrayList<>()).add(s);
        }
        return byLead;
    }
}
