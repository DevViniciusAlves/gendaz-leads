package com.gendaz.leads.service.messaging;

import com.gendaz.leads.entity.MessageSend;
import com.gendaz.leads.repository.MessageSendRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MessageSendClaimServiceTest {

    @Mock
    MessageSendRepository messageSendRepository;

    @InjectMocks
    MessageSendClaimService claimService;

    @Test
    void claimTransitionsQueuedToSendingWithIncrement() {
        MessageSend queued = MessageSend.builder().id(1L).leadId(5L).campaignId(10L)
                .status("QUEUED").attempts(0).build();
        when(messageSendRepository.claimNextDueNative(any())).thenReturn(Optional.of(queued));
        when(messageSendRepository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));

        MessageSend claimed = claimService.claimNextDue();

        assertNotNull(claimed);
        assertEquals("SENDING", claimed.getStatus());
        assertEquals(1, claimed.getAttempts());
        assertNotNull(claimed.getLastAttemptAt());
        verify(messageSendRepository).saveAndFlush(queued);
    }

    @Test
    void emptyQueueReturnsNull() {
        when(messageSendRepository.claimNextDueNative(any())).thenReturn(Optional.empty());

        assertNull(claimService.claimNextDue());
        verify(messageSendRepository, never()).saveAndFlush(any());
    }
}
