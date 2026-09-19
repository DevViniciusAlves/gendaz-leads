package com.gendaz.leads.service.messaging;

import com.gendaz.leads.entity.Lead;
import com.gendaz.leads.entity.LeadEvent;
import com.gendaz.leads.entity.MessageSend;
import com.gendaz.leads.repository.LeadEventRepository;
import com.gendaz.leads.repository.LeadRepository;
import com.gendaz.leads.repository.MessageSendRepository;
import com.gendaz.leads.service.CampaignCounterService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MessageSendCompletionServiceTest {

    @Mock
    MessageSendRepository messageSendRepository;
    @Mock
    LeadRepository leadRepository;
    @Mock
    LeadEventRepository leadEventRepository;
    @Mock
    CampaignCounterService counterService;

    @InjectMocks
    MessageSendCompletionService completionService;

    private MessageSend send() {
        return MessageSend.builder().id(1L).leadId(5L).campaignId(10L)
                .status("SENDING").attempts(1).build();
    }

    private Lead lead() {
        Lead l = new Lead();
        l.setId(5L);
        l.setStatus("APPROVED");
        return l;
    }

    @Test
    void successMarksSentAndLeadAndEventAndCounters() {
        MessageSend s = send();
        Lead l = lead();
        when(messageSendRepository.findById(1L)).thenReturn(Optional.of(s));
        when(messageSendRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(leadRepository.findById(5L)).thenReturn(Optional.of(l));
        when(leadRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        completionService.completeSuccess(1L, "wa-123");

        assertEquals("SENT", s.getStatus());
        assertEquals("wa-123", s.getProviderMessageId());
        assertNotNull(s.getSentAt());
        assertNull(s.getErrorCode());
        assertEquals("SENT", l.getStatus());
        ArgumentCaptor<LeadEvent> captor = ArgumentCaptor.forClass(LeadEvent.class);
        verify(leadEventRepository).save(captor.capture());
        assertEquals("message_sent", captor.getValue().getEventType());
        verify(counterService).recount(10L);
    }

    @Test
    void terminalFailureEmitsMessageFailed() {
        MessageSend s = send();
        when(messageSendRepository.findById(1L)).thenReturn(Optional.of(s));
        when(messageSendRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        completionService.completeTerminalFailure(1L, "RECIPIENT_NOT_ON_WHATSAPP", "sem zap");

        assertEquals("FAILED", s.getStatus());
        assertEquals("RECIPIENT_NOT_ON_WHATSAPP", s.getErrorCode());
        ArgumentCaptor<LeadEvent> captor = ArgumentCaptor.forClass(LeadEvent.class);
        verify(leadEventRepository).save(captor.capture());
        assertEquals("message_failed", captor.getValue().getEventType());
        assertTrue(captor.getValue().getEventMetadata().contains("RECIPIENT_NOT_ON_WHATSAPP"));
        verify(counterService).recount(10L);
    }

    @Test
    void skippedEmitsMessageSkipped() {
        MessageSend s = send();
        when(messageSendRepository.findById(1L)).thenReturn(Optional.of(s));
        when(messageSendRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        completionService.completeSkipped(1L, "DO_NOT_CONTACT");

        assertEquals("SKIPPED", s.getStatus());
        verify(leadEventRepository).save(argThat(e -> "message_skipped".equals(e.getEventType())));
    }

    @Test
    void deliveryUnknownDoesNotMarkLeadSent() {
        MessageSend s = send();
        Lead l = lead();
        when(messageSendRepository.findById(1L)).thenReturn(Optional.of(s));
        when(messageSendRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        completionService.completeDeliveryUnknown(1L, "WHATSAPP_READ_TIMEOUT", "timeout");

        assertEquals("DELIVERY_UNKNOWN", s.getStatus());
        assertEquals("APPROVED", l.getStatus());
        verify(leadRepository, never()).save(any());
        verify(leadEventRepository).save(argThat(e -> "message_delivery_unknown".equals(e.getEventType())));
        verify(counterService).recount(10L);
    }
}
