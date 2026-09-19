package com.gendaz.leads.service.messaging;

import com.gendaz.leads.exception.ApiException;
import com.gendaz.leads.whatsapp.WhatsAppSendResult;
import com.gendaz.leads.whatsapp.WhatsAppService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WhatsAppMessagingProviderTest {

    @Mock
    WhatsAppService whatsAppService;

    @InjectMocks
    WhatsAppMessagingProvider provider;

    private MessagingCommand cmd() {
        return new MessagingCommand(1L, 5L, "5511999999999", "Ola", "req-1");
    }

    @Test
    void successMapsToNone() {
        when(whatsAppService.sendText(any(), any(), any()))
                .thenReturn(new WhatsAppSendResult(true, "wa-1", "req-1", false));

        MessagingSendResult r = provider.send(cmd());

        assertTrue(r.success());
        assertEquals(FailureCategory.NONE, r.failureCategory());
        assertEquals("wa-1", r.providerMessageId());
    }

    @Test
    void terminalByCodeRecipientNotOnWhatsapp() {
        when(whatsAppService.sendText(any(), any(), any()))
                .thenThrow(new ApiException(HttpStatus.BAD_REQUEST, "RECIPIENT_NOT_ON_WHATSAPP", "sem conta"));

        MessagingSendResult r = provider.send(cmd());

        assertFalse(r.success());
        assertEquals(FailureCategory.TERMINAL, r.failureCategory());
        assertEquals("RECIPIENT_NOT_ON_WHATSAPP", r.errorCode());
    }

    @Test
    void terminalByCodeInvalidRecipient() {
        when(whatsAppService.sendText(any(), any(), any()))
                .thenThrow(new ApiException(HttpStatus.BAD_REQUEST, "WHATSAPP_INVALID_RECIPIENT", "bad"));

        assertEquals(FailureCategory.TERMINAL, provider.send(cmd()).failureCategory());
    }

    @Test
    void transientByCodeNotConnected() {
        when(whatsAppService.sendText(any(), any(), any()))
                .thenThrow(new ApiException(HttpStatus.CONFLICT, "WHATSAPP_NOT_CONNECTED", "down"));

        MessagingSendResult r = provider.send(cmd());

        assertEquals(FailureCategory.TRANSIENT, r.failureCategory());
    }

    @Test
    void ambiguousByCodeReadTimeout() {
        when(whatsAppService.sendText(any(), any(), any()))
                .thenThrow(new ApiException(HttpStatus.GATEWAY_TIMEOUT, "WHATSAPP_READ_TIMEOUT", "timeout"));

        assertEquals(FailureCategory.AMBIGUOUS, provider.send(cmd()).failureCategory());
    }

    @Test
    void unknown4xxCodeIsNotTerminal() {
        // Classificacao por ApiException.getCode(), nao por "todo 4xx terminal".
        when(whatsAppService.sendText(any(), any(), any()))
                .thenThrow(new ApiException(HttpStatus.NOT_FOUND, "WHATSAPP_QR_NOT_AVAILABLE", "sem qr"));

        assertEquals(FailureCategory.AMBIGUOUS, provider.send(cmd()).failureCategory());
    }

    @Test
    void unexpectedExceptionIsAmbiguous() {
        when(whatsAppService.sendText(any(), any(), any()))
                .thenThrow(new RuntimeException("boom"));

        MessagingSendResult r = provider.send(cmd());

        assertEquals(FailureCategory.AMBIGUOUS, r.failureCategory());
        assertEquals("UNEXPECTED", r.errorCode());
    }
}
