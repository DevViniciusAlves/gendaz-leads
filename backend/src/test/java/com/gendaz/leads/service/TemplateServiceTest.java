package com.gendaz.leads.service;

import com.gendaz.leads.entity.MessageTemplate;
import com.gendaz.leads.repository.MessageTemplateRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TemplateServiceTest {

    @Mock
    MessageTemplateRepository repository;

    @InjectMocks
    TemplateService service;

    @Test
    void upsertUpdatesExistingDefault() {
        MessageTemplate existing = MessageTemplate.builder().id(1L).name("Abordagem padrão")
                .templateText("Antigo").isDefault(true).build();
        when(repository.findFirstByIsDefaultTrue()).thenReturn(Optional.of(existing));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        MessageTemplate out = service.upsertDefault(null, "Olá {{nome}}");

        assertEquals(1L, out.getId());
        assertEquals("Olá {{nome}}", out.getTemplateText());
        assertEquals("Abordagem padrão", out.getName());
        verify(repository, never()).save(argThat(t -> !((MessageTemplate) t).isDefault()));
    }

    @Test
    void upsertCreatesDefaultWhenMissing() {
        when(repository.findFirstByIsDefaultTrue()).thenReturn(Optional.empty());
        when(repository.save(any())).thenAnswer(inv -> {
            MessageTemplate t = inv.getArgument(0);
            t.setId(5L);
            return t;
        });

        MessageTemplate out = service.upsertDefault(null, "Novo {{cidade}}");

        assertEquals(5L, out.getId());
        assertEquals("Abordagem padrão", out.getName());
        assertTrue(out.isDefault());
    }

    @Test
    void singleDefaultEnforcedByRepositoryQuery() {
        // findDefault delega ao índice parcial uq_message_template_default.
        when(repository.findFirstByIsDefaultTrue()).thenReturn(Optional.empty());
        assertTrue(service.findDefault().isEmpty());
    }
}
