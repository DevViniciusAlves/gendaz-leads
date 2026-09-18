package com.gendaz.leads.whatsapp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gendaz.leads.exception.ApiException;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Testa o mapeamento HTTP do Node (Baileys) contra um servidor HTTP falso.
 * Nao depende de WhatsApp real.
 */
class BaileysWhatsAppProviderTest {

    private HttpServer server;
    private BaileysWhatsAppProvider provider;
    private final AtomicReference<String> lastAuth = new AtomicReference<>();
    private volatile int statusCode = 200;
    private volatile String responseBody = "{}";

    @BeforeEach
    void setup() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            lastAuth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] body = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(statusCode, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
        server.start();
        int port = server.getAddress().getPort();

        WhatsAppProperties props = new WhatsAppProperties();
        props.setServiceUrl("http://127.0.0.1:" + port);
        props.setInternalToken("test-token");
        RestClient restClient = RestClient.builder()
                .baseUrl("http://127.0.0.1:" + port).build();
        provider = new BaileysWhatsAppProvider(props, new ObjectMapper(), restClient);
    }

    @AfterEach
    void teardown() {
        if (server != null) server.stop(0);
    }

    @Test
    void mapsStatusResponse() {
        statusCode = 200;
        responseBody = "{\"status\":\"CONNECTED\",\"hasQr\":false}";
        WhatsAppSessionStatus s = provider.status();
        assertEquals("CONNECTED", s.status());
        assertEquals("Bearer test-token", lastAuth.get());
    }

    @Test
    void mapsQrResponse() {
        statusCode = 200;
        responseBody = "{\"qr\":\"QR-DATA\",\"updatedAt\":\"2026-01-01T00:00:00Z\"}";
        WhatsAppQr qr = provider.qr();
        assertEquals("QR-DATA", qr.qr());
    }

    @Test
    void mapsSendResponse() {
        statusCode = 200;
        responseBody = "{\"status\":\"sent\",\"messageId\":\"mid-9\",\"requestId\":\"req-9\",\"deduplicated\":false}";
        WhatsAppSendResult r = provider.sendText("5565999999999", "ola", "req-9");
        assertTrue(r.sent());
        assertEquals("mid-9", r.messageId());
        assertEquals("req-9", r.requestId());
    }

    @Test
    void mapsRecipientNotOnWhatsapp() {
        statusCode = 400;
        responseBody = "{\"error\":\"recipient_not_on_whatsapp\"}";
        ApiException ex = assertThrows(ApiException.class,
                () -> provider.sendText("5565999999999", "ola", "req-1"));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
        assertEquals("RECIPIENT_NOT_ON_WHATSAPP", ex.getCode());
    }

    @Test
    void mapsSessionNotConnected() {
        statusCode = 409;
        responseBody = "{\"error\":\"session_not_connected\"}";
        ApiException ex = assertThrows(ApiException.class,
                () -> provider.sendText("5565999999999", "ola", "req-1"));
        assertEquals(HttpStatus.CONFLICT, ex.getStatus());
        assertEquals("WHATSAPP_NOT_CONNECTED", ex.getCode());
    }

    @Test
    void mapsQrNotAvailable() {
        statusCode = 404;
        responseBody = "{\"error\":\"qr_not_available\",\"hasQr\":false}";
        ApiException ex = assertThrows(ApiException.class, () -> provider.qr());
        assertEquals(HttpStatus.NOT_FOUND, ex.getStatus());
        assertEquals("WHATSAPP_QR_NOT_AVAILABLE", ex.getCode());
    }

    @Test
    void refusesWhenNotConfigured() {
        WhatsAppProperties empty = new WhatsAppProperties();
        BaileysWhatsAppProvider unconfigured = new BaileysWhatsAppProvider(
                empty, new ObjectMapper(), RestClient.builder().baseUrl("http://127.0.0.1:9").build());
        ApiException ex = assertThrows(ApiException.class, unconfigured::status);
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, ex.getStatus());
        assertEquals("WHATSAPP_NOT_CONFIGURED", ex.getCode());
    }

    @Test
    void mapsUnreachableServiceToBadGateway() {
        WhatsAppProperties props = new WhatsAppProperties();
        props.setServiceUrl("http://127.0.0.1:9");
        props.setInternalToken("test-token");
        BaileysWhatsAppProvider unreachable = new BaileysWhatsAppProvider(
                props, new ObjectMapper(), RestClient.builder().baseUrl("http://127.0.0.1:9").build());
        ApiException ex = assertThrows(ApiException.class, unreachable::status);
        assertEquals(HttpStatus.BAD_GATEWAY, ex.getStatus());
    }
}
