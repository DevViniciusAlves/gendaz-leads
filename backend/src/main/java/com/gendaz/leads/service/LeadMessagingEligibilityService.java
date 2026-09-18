package com.gendaz.leads.service;

import com.gendaz.leads.entity.Lead;
import com.gendaz.leads.entity.MessageSend;
import com.gendaz.leads.repository.MessageSendRepository;
import org.springframework.stereotype.Service;

@Service
public class LeadMessagingEligibilityService {

    private final MessageSendRepository messageSendRepository;

    public LeadMessagingEligibilityService(MessageSendRepository messageSendRepository) {
        this.messageSendRepository = messageSendRepository;
    }

    public EligibilityResult checkEligibility(Lead lead, Long campaignId) {
        if (lead == null) {
            return new EligibilityResult(false, "WRONG_CAMPAIGN", "Lead nulo", null);
        }

        // Verifica se o lead pertence à campaign
        if (campaignId != null && lead.getCurrentCampaignId() != null && !lead.getCurrentCampaignId().equals(campaignId)) {
            return new EligibilityResult(false, "WRONG_CAMPAIGN", "Lead não pertence à campanha", null);
        }

        // doNotContact == false é necessário para ser elegível
        if (lead.isDoNotContact()) {
            return new EligibilityResult(false, "DO_NOT_CONTACT", "Não prospectar", null);
        }

        // Possui telefone
        if (lead.getPhone() == null || lead.getPhone().isBlank()) {
            return new EligibilityResult(false, "NO_PHONE", "Sem telefone", null);
        }

        // Telefone normaliza
        String normalizedPhone = normalizePhone(lead.getPhone());
        if (normalizedPhone == null || normalizedPhone.isBlank()) {
            return new EligibilityResult(false, "INVALID_PHONE", "Telefone inválido", null);
        }

        // Não existe MessageSend bloqueante (QUEUED, SENDING, SENT, DELIVERY_UNKNOWN)
        List<MessageSend> sends = messageSendRepository.findByLeadId(lead.getId());
        for (MessageSend send : sends) {
            if ("QUEUED".equals(send.getStatus())
                    || "SENDING".equals(send.getStatus())
                    || "SENT".equals(send.getStatus())
                    || "DELIVERY_UNKNOWN".equals(send.getStatus())) {
                return new EligibilityResult(false, "ALREADY_QUEUED_OR_SENT",
                        "Já existe envio bloqueante na fila ou concluído", normalizedPhone);
            }
        }

        return new EligibilityResult(true, null, null, normalizedPhone);
    }

    private String normalizePhone(String phone) {
        if (phone == null) return null;
        String digits = phone.replaceAll("\\D", "");
        if (digits.length() < 8) return null;
        if (digits.startsWith("00")) digits = digits.substring(2);
        return digits;
    }
}

record EligibilityResult(
        boolean eligible,
        String code,
        String reason,
        String normalizedRecipient
) {}