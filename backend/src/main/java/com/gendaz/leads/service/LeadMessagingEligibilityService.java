package com.gendaz.leads.service;

import com.gendaz.leads.entity.Lead;
import com.gendaz.leads.entity.MessageSend;
import com.gendaz.leads.repository.MessageSendRepository;
import org.springframework.stereotype.Service;
import java.util.List;

@Service
public class LeadMessagingEligibilityService {

    private final MessageSendRepository messageSendRepository;

    public LeadMessagingEligibilityService(MessageSendRepository messageSendRepository) {
        this.messageSendRepository = messageSendRepository;
    }

    public boolean isEligible(Lead lead) {
        if (lead == null) return false;
        if (lead.isDoNotContact()) return false;
        if (lead.getNormalizedPhone() == null || lead.getNormalizedPhone().isBlank()) return false;
        
        // Verifica se já enviamos mensagem nos últimos 30 dias (ou qualquer mensagem enviada dependendo da regra)
        List<MessageSend> sends = messageSendRepository.findByLeadId(lead.getId());
        for (MessageSend send : sends) {
            if ("SENT".equals(send.getStatus()) || "DELIVERY_UNKNOWN".equals(send.getStatus()) || "QUEUED".equals(send.getStatus()) || "SENDING".equals(send.getStatus())) {
                return false; // Já está no fluxo ou foi enviado
            }
        }
        return true;
    }
}
