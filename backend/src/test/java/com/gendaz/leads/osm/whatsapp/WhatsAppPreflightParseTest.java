package com.gendaz.leads.osm.whatsapp;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class WhatsAppPreflightParseTest {

    @Test
    void parsesConnectedVariants() {
        assertEquals("CONNECTED", WhatsAppPreflightService.parseStatus("{\"status\":\"connected\"}"));
        assertEquals("CONNECTED", WhatsAppPreflightService.parseStatus("{\"connected\":true}"));
        assertEquals("QR_REQUIRED", WhatsAppPreflightService.parseStatus("{\"qrRequired\":true}"));
        assertEquals("QR_REQUIRED", WhatsAppPreflightService.parseStatus("{\"status\":\"QR_REQUIRED\"}"));
        assertEquals("UNKNOWN", WhatsAppPreflightService.parseStatus("not json"));
    }
}
