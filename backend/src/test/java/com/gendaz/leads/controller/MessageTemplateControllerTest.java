package com.gendaz.leads.controller;

import com.gendaz.leads.dto.template.MessageTemplateResponse;
import com.gendaz.leads.dto.template.UpdateMessageTemplateRequest;
import com.gendaz.leads.entity.MessageTemplate;
import com.gendaz.leads.service.TemplateService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MessageTemplateControllerTest {

    @Mock
    TemplateService templateService;

    @InjectMocks
    MessageTemplateController controller;

    private MessageTemplate entity() {
        return MessageTemplate.builder().id(1L).name("Abordagem padrão")
                .templateText("Olá {{nome}}").isDefault(true)
                .createdAt(Instant.now()).updatedAt(Instant.now()).build();
    }

    @Test
    void getDefaultReturnsDto() {
        when(templateService.findDefault()).thenReturn(Optional.of(entity()));
        ResponseEntity<MessageTemplateResponse> res = controller.getDefault();
        assertEquals(HttpStatus.OK, res.getStatusCode());
        assertEquals(1L, res.getBody().id());
        assertEquals("Olá {{nome}}", res.getBody().templateText());
        assertTrue(res.getBody().isDefault());
        assertNotNull(res.getBody().updatedAt());
    }

    @Test
    void getDefaultMissingReturns404() {
        when(templateService.findDefault()).thenReturn(Optional.empty());
        assertEquals(HttpStatus.NOT_FOUND, controller.getDefault().getStatusCode());
    }

    @Test
    void putExistingUpdatesViaUpsert() {
        when(templateService.upsertDefault(eq("Abordagem padrão"), eq("Olá {{nome}} em {{cidade}}")))
                .thenReturn(entity());
        ResponseEntity<MessageTemplateResponse> res = controller.putDefault(
                new UpdateMessageTemplateRequest("Olá {{nome}} em {{cidade}}", "Abordagem padrão"));
        assertEquals(HttpStatus.OK, res.getStatusCode());
        assertEquals("Olá {{nome}}", res.getBody().templateText());
        verify(templateService).upsertDefault("Abordagem padrão", "Olá {{nome}} em {{cidade}}");
    }

    @Test
    void putCreatesDefaultWhenMissing() {
        MessageTemplate created = MessageTemplate.builder().id(9L).name("Abordagem padrão")
                .templateText("Novo {{nome}}").isDefault(true).build();
        when(templateService.upsertDefault(any(), any())).thenReturn(created);
        ResponseEntity<MessageTemplateResponse> res = controller.putDefault(
                new UpdateMessageTemplateRequest("Novo {{nome}}", null));
        assertEquals(HttpStatus.OK, res.getStatusCode());
        assertEquals(9L, res.getBody().id());
        assertTrue(res.getBody().isDefault());
    }

    @Test
    void dtoDoesNotExposeEntityControlFields() throws Exception {
        // Request DTO só possui templateText + name opcional.
        var reqFields = UpdateMessageTemplateRequest.class.getRecordComponents();
        assertEquals(2, reqFields.length);
        // Response DTO possui apenas id/name/templateText/isDefault/updatedAt.
        var resFields = MessageTemplateResponse.class.getRecordComponents();
        assertEquals(5, resFields.length);
        // Frontend não controla id/isDefault/createdAt via request:
        for (var c : reqFields) {
            assertFalse(c.getName().equals("id"));
            assertFalse(c.getName().equals("isDefault"));
            assertFalse(c.getName().equals("createdAt"));
        }
    }
}
