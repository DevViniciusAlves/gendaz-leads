package com.gendaz.leads.osm.whatsapp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Cliente Java do recipient check (Node: POST
 * /internal/whatsapp/session/recipients/check via sock.onWhatsApp).
 * Nao envia mensagem. Normaliza antes. 409/429/5xx/timeout = falha tecnica
 * com retry bounded; apos retries -> FAILED (nunca NOT_ON_WHATSAPP).
 */
public class WhatsAppRecipientValidationClient {

    private static final ObjectMapper JSON = new ObjectMapper();
    private final HttpClient httpClient;
    private final String baseUrl;
    private final String internalToken;
    private final int maxAttempts;
    private final long intervalMs;

    public WhatsAppRecipientValidationClient(String baseUrl, String internalToken,
                                             int maxAttempts, long intervalMs) {
        this.baseUrl = baseUrl == null ? "" : baseUrl.replaceAll("/$", "");
        this.internalToken = internalToken;
        this.maxAttempts = Math.max(1, maxAttempts);
        this.intervalMs = Math.max(0, intervalMs);
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(5000))
                .build();
    }

    public WhatsAppRecipientCheckResult check(String normalizedRecipient) {
        String url = baseUrl + "/internal/whatsapp/session/recipients/check";
        String body;
        try {
            body = JSON.writeValueAsString(java.util.Map.of("recipient", normalizedRecipient));
        } catch (Exception e) {
            throw new WhatsAppInfrastructureException("WPP_SERIALIZE_FAILED", "Falha ao serializar recipient check");
        }
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                        .timeout(Duration.ofMillis(10000))
                        .header("Authorization", "Bearer " + internalToken)
                        .header("Content-Type", "application/json")
                        .header("User-Agent", "GendazLeads-OsmSync/1.0")
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build();
                HttpResponse<String> res = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
                int status = res.statusCode();
                if (status == 200) {
                    JsonNode node = JSON.readTree(res.body() == null ? "{}" : res.body());
                    boolean exists = node.path("exists").asBoolean(false);
                    return exists ? WhatsAppRecipientCheckResult.found()
                            : WhatsAppRecipientCheckResult.absent();
                }
                if (status == 400) {
                    // Numero invalido / nao existe no WhatsApp.
                    return WhatsAppRecipientCheckResult.absent();
                }
                if (status == 409) {
                    return WhatsAppRecipientCheckResult.infra("WPP_SESSION_NOT_CONNECTED");
                }
                if (status == 429 || status >= 500) {
                    if (attempt < maxAttempts) {
                        sleep(intervalMs);
                        continue;
                    }
                    return WhatsAppRecipientCheckResult.infra(status == 429 ? "WPP_RATE_LIMITED" : "WPP_SERVICE_ERROR");
                }
                if (status == 401 || status == 403) {
                    return WhatsAppRecipientCheckResult.infra("WPP_UNAUTHORIZED");
                }
                if (attempt < maxAttempts) {
                    sleep(intervalMs);
                    continue;
                }
                return WhatsAppRecipientCheckResult.infra("WPP_CHECK_FAILED");
            } catch (java.net.http.HttpTimeoutException e) {
                if (attempt < maxAttempts) {
                    sleep(intervalMs);
                    continue;
                }
                return WhatsAppRecipientCheckResult.infra("WPP_TIMEOUT");
            } catch (IOExceptionWithRetry | RuntimeException e) {
                if (attempt < maxAttempts) {
                    sleep(intervalMs);
                    continue;
                }
                return WhatsAppRecipientCheckResult.infra("WPP_TRANSPORT_FAILED");
            } catch (Exception e) {
                if (attempt < maxAttempts) {
                    sleep(intervalMs);
                    continue;
                }
                return WhatsAppRecipientCheckResult.infra("WPP_TRANSPORT_FAILED");
            }
        }
        return WhatsAppRecipientCheckResult.infra("WPP_CHECK_FAILED");
    }

    private void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static class IOExceptionWithRetry extends java.io.IOException {}
}
