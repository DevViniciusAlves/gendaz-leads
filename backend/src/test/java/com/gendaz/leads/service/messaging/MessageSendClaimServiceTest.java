package com.gendaz.leads.service.messaging;

import com.gendaz.leads.entity.MessageSend;
import com.gendaz.leads.repository.MessageSendRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.TypedQuery;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MessageSendClaimServiceTest {

    @Mock
    EntityManager entityManager;
    @Mock
    MessageSendRepository messageSendRepository;

    @InjectMocks
    MessageSendClaimService claimService;

    @SuppressWarnings("unchecked")
    private TypedQuery<MessageSend> queryReturning(List<MessageSend> rows) {
        TypedQuery<MessageSend> q = mock(TypedQuery.class);
        when(entityManager.createQuery(anyString(), eq(MessageSend.class))).thenReturn(q);
        when(q.setParameter(eq("now"), any())).thenReturn(q);
        when(q.setMaxResults(1)).thenReturn(q);
        when(q.setLockMode(any(LockModeType.class))).thenReturn(q);
        when(q.setHint(anyString(), any())).thenReturn(q);
        when(q.getResultList()).thenReturn(rows);
        return q;
    }

    @Test
    void claimTransitionsQueuedToSendingWithIncrement() {
        MessageSend queued = MessageSend.builder().id(1L).leadId(5L).campaignId(10L)
                .status("QUEUED").attempts(0).build();
        queryReturning(List.of(queued));
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
        queryReturning(List.of());

        assertNull(claimService.claimNextDue());
        verify(messageSendRepository, never()).saveAndFlush(any());
    }
}
