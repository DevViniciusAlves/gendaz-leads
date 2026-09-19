package com.gendaz.leads.service.messaging;

import com.gendaz.leads.entity.Lead;
import com.gendaz.leads.entity.MessageSend;
import com.gendaz.leads.repository.LeadRepository;
import com.gendaz.leads.repository.MessageSendRepository;
import com.gendaz.leads.whatsapp.WhatsAppService;
import com.gendaz.leads.whatsapp.WhatsAppSessionStatus;
import org.junit.jupiter.api.BeforeEach;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SendQueueProcessorTest {

    @Mock
    MessageSendClaimService claimService;
    @Mock
    MessageSendCompletionService completionService;
    @Mock
    MessageSendRepository messageSendRepository;
    @Mock
    LeadRepository leadRepository;
    @Mock
    MessagingProviderRouter providerRouter;
    @Mock
    MessagingScheduleProperties scheduleProperties;
    @Mock
    MessagingProvider provider;
    @Mock
    WhatsAppService whatsAppService;

    SendQueueProcessor processor;

    @BeforeEach
    void setup() {
        processor = new SendQueueProcessor(claimService, completionService,
                messageSendRepository, leadRepository, providerRouter, scheduleProperties, whatsAppService);
    }

    private MessageSend claimed(int attempts) {
        return MessageSend.builder().id(1L).leadId(5L).campaignId(10L)
                .provider("whatsapp").status("SENDING").attempts(attempts)
                .requestId("req-abc")
                .messageTextSnapshot("Ola Empresa")
                .recipientSnapshot("5511999999999")
                .build();
    }

    private Lead lead(boolean dnc) {
        Lead l = new Lead();
        l.setId(5L);
        l.setBusinessName("Empresa");
        l.setPhone("(11) 00000-0000"); // diferente do snapshot: fila nao deve usar lead.phone
        l.setDoNotContact(dnc);
        l.setStatus("APPROVED");
        return l;
    }

    @Test
    void successUsesSnapshotsAndPreservesRequestId() {
        MessageSend c = claimed(1);
        when(leadRepository.findById(5L)).thenReturn(Optional.of(lead(false)));
        when(messageSendRepository.findById(1L)).thenReturn(Optional.of(c));
        when(providerRouter.getProvider("whatsapp")).thenReturn(provider);
        when(provider.send(any())).thenReturn(new MessagingSendResult(true, FailureCategory.NONE, "wa-1", null, "ok"));
        when(whatsAppService.status()).thenReturn(new WhatsAppSessionStatus("CONNECTED", false, null));

        processor.processOneOutsideTransaction(c);

        ArgumentCaptor<MessagingCommand> captor = ArgumentCaptor.forClass(MessagingCommand.class);
        verify(provider).send(captor.capture());
        MessagingCommand cmd = captor.getValue();
        assertEquals("5511999999999", cmd.recipient());
        assertEquals("Ola Empresa", cmd.message());
        assertEquals("req-abc", cmd.requestId());
        verify(completionService).completeSuccess(1L, "wa-1");
    }

    @Test
    void terminalGoesFailedWithoutRetry() {
        MessageSend c = claimed(1);
        when(leadRepository.findById(5L)).thenReturn(Optional.of(lead(false)));
        when(messageSendRepository.findById(1L)).thenReturn(Optional.of(c));
        when(providerRouter.getProvider("whatsapp")).thenReturn(provider);
        when(provider.send(any())).thenReturn(new MessagingSendResult(false,
                FailureCategory.TERMINAL, null, "RECIPIENT_NOT_ON_WHATSAPP", "sem zap"));
        when(whatsAppService.status()).thenReturn(new WhatsAppSessionStatus("CONNECTED", false, null));

        processor.processOneOutsideTransaction(c);

        verify(completionService).completeTerminalFailure(eq(1L), eq("RECIPIENT_NOT_ON_WHATSAPP"), any());
        verify(completionService, never()).completeTransientRetry(any(), any(), any(), any());
        verify(completionService, never()).completeDeliveryUnknown(any(), any(), any());
    }

    @Test
    void transientRequeues() {
        MessageSend c = claimed(1);
        when(leadRepository.findById(5L)).thenReturn(Optional.of(lead(false)));
        when(messageSendRepository.findById(1L)).thenReturn(Optional.of(c));
        when(providerRouter.getProvider("whatsapp")).thenReturn(provider);
        when(provider.send(any())).thenReturn(new MessagingSendResult(false,
                FailureCategory.TRANSIENT, null, "WHATSAPP_NOT_CONNECTED", "down"));
        when(whatsAppService.status()).thenReturn(new WhatsAppSessionStatus("CONNECTED", false, null));

        processor.processOneOutsideTransaction(c);

        verify(completionService).completeTransientRetry(eq(1L), eq("WHATSAPP_NOT_CONNECTED"), any(), any());
    }

    @Test
    void maxTransientGoesFailed() {
        MessageSend c = claimed(5);
        when(leadRepository.findById(5L)).thenReturn(Optional.of(lead(false)));
        when(messageSendRepository.findById(1L)).thenReturn(Optional.of(c));
        when(providerRouter.getProvider("whatsapp")).thenReturn(provider);
        when(provider.send(any())).thenReturn(new MessagingSendResult(false,
                FailureCategory.TRANSIENT, null, "WHATSAPP_NOT_CONNECTED", "down"));
        when(whatsAppService.status()).thenReturn(new WhatsAppSessionStatus("CONNECTED", false, null));

        processor.processOneOutsideTransaction(c);

        verify(completionService).completeMaxAttemptsFailed(eq(1L), eq("WHATSAPP_NOT_CONNECTED"), any());
    }

    @Test
    void ambiguousGoesDeliveryUnknownOnFirstAttempt() {
        MessageSend c = claimed(1);
        when(leadRepository.findById(5L)).thenReturn(Optional.of(lead(false)));
        when(messageSendRepository.findById(1L)).thenReturn(Optional.of(c));
        when(providerRouter.getProvider("whatsapp")).thenReturn(provider);
        when(provider.send(any())).thenReturn(new MessagingSendResult(false,
                FailureCategory.AMBIGUOUS, null, "WHATSAPP_READ_TIMEOUT", "timeout"));
        when(whatsAppService.status()).thenReturn(new WhatsAppSessionStatus("CONNECTED", false, null));

        processor.processOneOutsideTransaction(c);

        verify(completionService).completeDeliveryUnknown(eq(1L), eq("WHATSAPP_READ_TIMEOUT"), any());
        verify(completionService, never()).completeTransientRetry(any(), any(), any(), any());
        verify(completionService, never()).completeTerminalFailure(any(), any(), any());
    }

    @Test
    void dncSkipsWithoutCallingProvider() {
        MessageSend c = claimed(1);
        when(leadRepository.findById(5L)).thenReturn(Optional.of(lead(true)));

        processor.processOneOutsideTransaction(c);

        verify(completionService).completeSkipped(1L, "DO_NOT_CONTACT");
        verify(providerRouter, never()).getProvider(any());
        verify(provider, never()).send(any());
    }

    @Test
    void missingProviderFails() {
        MessageSend c = claimed(1);
        when(leadRepository.findById(5L)).thenReturn(Optional.of(lead(false)));
        when(messageSendRepository.findById(1L)).thenReturn(Optional.of(c));
        when(providerRouter.getProvider("whatsapp")).thenReturn(null);

        processor.processOneOutsideTransaction(c);

        verify(completionService).completeTerminalFailure(eq(1L), eq("PROVIDER_NOT_FOUND"), any());
    }

    @Test
    void missingMessageSnapshotFails() {
        MessageSend c = claimed(1);
        c.setMessageTextSnapshot(null);
        when(leadRepository.findById(5L)).thenReturn(Optional.of(lead(false)));
        when(messageSendRepository.findById(1L)).thenReturn(Optional.of(c));

        processor.processOneOutsideTransaction(c);

        verify(completionService).completeTerminalFailure(eq(1L), eq("MISSING_MESSAGE_SNAPSHOT"), any());
        verify(providerRouter, never()).getProvider(any());
    }

    @Test
    void missingRecipientSnapshotFails() {
        MessageSend c = claimed(1);
        c.setRecipientSnapshot("  ");
        when(leadRepository.findById(5L)).thenReturn(Optional.of(lead(false)));
        when(messageSendRepository.findById(1L)).thenReturn(Optional.of(c));

        processor.processOneOutsideTransaction(c);

        verify(completionService).completeTerminalFailure(eq(1L), eq("MISSING_RECIPIENT_SNAPSHOT"), any());
        verify(providerRouter, never()).getProvider(any());
    }

    @Test
    void unknownCodeCategorizedAmbiguous() {
        assertEquals(FailureCategory.AMBIGUOUS,
                SendQueueProcessor.categorize("SOME_NEW_CODE", FailureCategory.TRANSIENT));
        assertEquals(FailureCategory.TERMINAL,
                SendQueueProcessor.categorize("RECIPIENT_NOT_ON_WHATSAPP", FailureCategory.AMBIGUOUS));
        assertEquals(FailureCategory.TRANSIENT,
                SendQueueProcessor.categorize("WHATSAPP_NOT_CONNECTED", FailureCategory.AMBIGUOUS));
    }
}
