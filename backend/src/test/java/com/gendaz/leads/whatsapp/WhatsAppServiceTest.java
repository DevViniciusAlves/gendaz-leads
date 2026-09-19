package com.gendaz.leads.whatsapp;

import com.gendaz.leads.exception.ApiException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WhatsAppServiceTest {

    private WhatsAppService serviceWith(FakeProvider fake) {
        return new WhatsAppService(fake);
    }

    @Test
    void rejectsInvalidRecipient() {
        FakeProvider fake = new FakeProvider();
        WhatsAppService service = serviceWith(fake);
        ApiException ex = assertThrows(ApiException.class,
                () -> service.sendText("abc-def", "ola", "req-1"));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
        assertEquals("WHATSAPP_INVALID_RECIPIENT", ex.getCode());
    }

    @Test
    void rejectsEmptyText() {
        FakeProvider fake = new FakeProvider();
        WhatsAppService service = serviceWith(fake);
        ApiException ex = assertThrows(ApiException.class,
                () -> service.sendText("5565999999999", "   ", "req-1"));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
        assertEquals("WHATSAPP_INVALID_TEXT", ex.getCode());
    }

    @Test
    void rejectsTooLongText() {
        FakeProvider fake = new FakeProvider();
        WhatsAppService service = serviceWith(fake);
        ApiException ex = assertThrows(ApiException.class,
                () -> service.sendText("5565999999999", "x".repeat(4001), "req-1"));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
        assertEquals("WHATSAPP_TEXT_TOO_LONG", ex.getCode());
    }

    @Test
    void normalizesRecipientAndGeneratesRequestIdWhenBlank() {
        FakeProvider fake = new FakeProvider();
        WhatsAppService service = serviceWith(fake);
        WhatsAppSendResult result = service.sendText("+55 (65) 99999-1234", "ola", null);
        assertTrue(result.sent());
        assertEquals("5565999991234", fake.lastRecipient);
        assertNotNull(fake.lastRequestId);
    }

    @Test
    void delegatesSessionOperations() {
        FakeProvider fake = new FakeProvider();
        WhatsAppService service = serviceWith(fake);
        assertEquals("CONNECTED", service.status().status());
        assertEquals("NOT_CONNECTED", service.disconnect().status());
        assertEquals("QR_REQUIRED", service.connect().status());
        assertNotNull(service.qr().qr());
    }

    static class FakeProvider implements WhatsAppServiceProvider {
        String lastRecipient;
        String lastRequestId;

        @Override
        public String getName() {
            return "fake";
        }

        @Override
        public WhatsAppSessionStatus connect() {
            return new WhatsAppSessionStatus("QR_REQUIRED", true, null);
        }

        @Override
        public WhatsAppSessionStatus status() {
            return new WhatsAppSessionStatus("CONNECTED", false, null);
        }

        @Override
        public WhatsAppQr qr() {
            return new WhatsAppQr("fake-qr", "2026-01-01T00:00:00Z");
        }

        @Override
        public WhatsAppSessionStatus logout() {
            return new WhatsAppSessionStatus("NOT_CONNECTED", false, null);
        }

        @Override
        public WhatsAppSendResult sendText(String recipient, String text, String requestId) {
            this.lastRecipient = recipient;
            this.lastRequestId = requestId;
            return new WhatsAppSendResult(true, "mid-1", requestId, false);
        }
    }
}
