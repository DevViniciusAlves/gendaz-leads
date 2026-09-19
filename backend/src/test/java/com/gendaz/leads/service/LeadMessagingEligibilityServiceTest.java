package com.gendaz.leads.service;

import com.gendaz.leads.entity.Lead;
import com.gendaz.leads.entity.MessageSend;
import com.gendaz.leads.repository.MessageSendRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LeadMessagingEligibilityServiceTest {

    @Mock
    MessageSendRepository messageSendRepository;

    @Mock
    WhatsAppRecipientNormalizer recipientNormalizer;

    @InjectMocks
    LeadMessagingEligibilityService eligibilityService;

    private Lead lead(Long id, Long campaignId, String phone, boolean dnc, String status) {
        Lead l = new Lead();
        l.setId(id);
        l.setCurrentCampaignId(campaignId);
        l.setPhone(phone);
        l.setCountry("BR");
        l.setDoNotContact(dnc);
        l.setStatus(status);
        l.setBusinessName("Empresa " + id);
        return l;
    }

    private MessageSend send(String status) {
        return MessageSend.builder().leadId(1L).campaignId(10L).status(status).build();
    }

    @Test
    void messageReadyWithValidPhoneIsEligible() {
        when(recipientNormalizer.normalizeForWhatsApp(any(), any())).thenReturn("5511999999999");
        when(messageSendRepository.findByLeadId(1L)).thenReturn(List.of());

        EligibilityResult r = eligibilityService.checkEligibility(lead(1L, 10L, "(11) 99999-9999", false, "MESSAGE_READY"), 10L);

        assertTrue(r.eligible());
        assertEquals("5511999999999", r.normalizedRecipient());
    }

    @Test
    void approvedIsAlsoEligible() {
        when(recipientNormalizer.normalizeForWhatsApp(any(), any())).thenReturn("5511999999999");
        when(messageSendRepository.findByLeadId(1L)).thenReturn(List.of());

        EligibilityResult r = eligibilityService.checkEligibility(lead(1L, 10L, "11999999999", false, "APPROVED"), 10L);

        assertTrue(r.eligible());
    }

    @Test
    void doNotContactBlocks() {
        EligibilityResult r = eligibilityService.checkEligibility(lead(1L, 10L, "11999999999", true, "MESSAGE_READY"), 10L);

        assertFalse(r.eligible());
        assertEquals("DO_NOT_CONTACT", r.code());
    }

    @Test
    void missingPhoneBlocks() {
        EligibilityResult r = eligibilityService.checkEligibility(lead(1L, 10L, null, false, "MESSAGE_READY"), 10L);

        assertFalse(r.eligible());
        assertEquals("NO_PHONE", r.code());
    }

    @Test
    void invalidPhoneBlocks() {
        when(recipientNormalizer.normalizeForWhatsApp(any(), any())).thenReturn(null);

        EligibilityResult r = eligibilityService.checkEligibility(lead(1L, 10L, "999", false, "MESSAGE_READY"), 10L);

        assertFalse(r.eligible());
        assertEquals("INVALID_PHONE", r.code());
    }

    @Test
    void queuedBlocks() {
        when(recipientNormalizer.normalizeForWhatsApp(any(), any())).thenReturn("5511999999999");
        when(messageSendRepository.findByLeadId(1L)).thenReturn(List.of(send("QUEUED")));

        EligibilityResult r = eligibilityService.checkEligibility(lead(1L, 10L, "11999999999", false, "MESSAGE_READY"), 10L);

        assertFalse(r.eligible());
        assertEquals("ALREADY_QUEUED", r.code());
    }

    @Test
    void sendingBlocks() {
        when(recipientNormalizer.normalizeForWhatsApp(any(), any())).thenReturn("5511999999999");
        when(messageSendRepository.findByLeadId(1L)).thenReturn(List.of(send("SENDING")));

        EligibilityResult r = eligibilityService.checkEligibility(lead(1L, 10L, "11999999999", false, "MESSAGE_READY"), 10L);

        assertFalse(r.eligible());
        assertEquals("ALREADY_SENDING", r.code());
    }

    @Test
    void sentBlocks() {
        when(recipientNormalizer.normalizeForWhatsApp(any(), any())).thenReturn("5511999999999");
        when(messageSendRepository.findByLeadId(1L)).thenReturn(List.of(send("SENT")));

        EligibilityResult r = eligibilityService.checkEligibility(lead(1L, 10L, "11999999999", false, "MESSAGE_READY"), 10L);

        assertFalse(r.eligible());
        assertEquals("ALREADY_SENT", r.code());
    }

    @Test
    void deliveryUnknownBlocks() {
        when(recipientNormalizer.normalizeForWhatsApp(any(), any())).thenReturn("5511999999999");
        when(messageSendRepository.findByLeadId(1L)).thenReturn(List.of(send("DELIVERY_UNKNOWN")));

        EligibilityResult r = eligibilityService.checkEligibility(lead(1L, 10L, "11999999999", false, "MESSAGE_READY"), 10L);

        assertFalse(r.eligible());
        assertEquals("DELIVERY_UNKNOWN", r.code());
    }

    @Test
    void failedDoesNotBlock() {
        when(recipientNormalizer.normalizeForWhatsApp(any(), any())).thenReturn("5511999999999");
        when(messageSendRepository.findByLeadId(1L)).thenReturn(List.of(send("FAILED")));

        EligibilityResult r = eligibilityService.checkEligibility(lead(1L, 10L, "11999999999", false, "MESSAGE_READY"), 10L);

        assertTrue(r.eligible());
    }

    @Test
    void wrongCampaignBlocks() {
        EligibilityResult r = eligibilityService.checkEligibility(lead(1L, 99L, "11999999999", false, "MESSAGE_READY"), 10L);

        assertFalse(r.eligible());
        assertEquals("WRONG_CAMPAIGN", r.code());
    }

    @Test
    void batchOverloadAvoidsRepositoryCall() {
        when(recipientNormalizer.normalizeForWhatsApp(any(), any())).thenReturn("5511999999999");

        EligibilityResult r = eligibilityService.checkEligibility(
                lead(1L, 10L, "11999999999", false, "MESSAGE_READY"), 10L, List.of());

        assertTrue(r.eligible());
        // Nenhuma chamada ao repositorio no overload em lote
        org.mockito.Mockito.verify(messageSendRepository, org.mockito.Mockito.never()).findByLeadId(any());
        when(messageSendRepository.findByLeadIdIn(anyCollection())).thenReturn(List.of());
        var grouped = eligibilityService.loadSendsByLead(List.of(1L));
        assertTrue(grouped.isEmpty());
    }
}
