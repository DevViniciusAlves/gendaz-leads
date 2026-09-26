package com.gendaz.leads.osm.whatsapp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Preflight Java (substitui whatsapp_sync_preflight.py).
 * CONNECTED -> prossegue. CONNECTING/DISCONNECTED transitorio -> poll bounded.
 * QR_REQUIRED/LOGGED_OUT/ERROR -> falha clara. Timeout/transporte -> retry bounded.
 * Nunca reseta sessao, nunca envia mensagem, nunca apaga credenciais.
 */
public class WhatsAppPreflightService {

    private static final ObjectMapper JSON = new ObjectMapper();
    private final HttpClient httpClient;
    private final String baseUrl;
    private final String internalToken;

    public WhatsAppPreflightService(String baseUrl, String internalToken) {
        this.baseUrl = baseUrl == null ? "" : baseUrl.replaceAll("/$", "");
        this.internalToken = internalToken;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(5000))
                .build();
    }

    public void ensureReady() {
        String statusUrl = baseUrl + "/internal/whatsapp/session/status";
        long deadline = System.currentTimeMillis() + 90_000;
        while (true) {
            String payload = get(statusUrl);
            String status = parseStatus(payload);
            switch (status) {
                case "CONNECTED" -> {
                    return;
                }
                case "QR_REQUIRED" ->
                        throw new WhatsAppInfrastructureException("WPP_QR_REQUIRED",
                                "Conecte o WhatsApp antes de sincronizar (QR pendente).");
                case "LOGGED_OUT" ->
                        throw new WhatsAppInfrastructureException("WPP_LOGGED_OUT",
                                "Sessao WhatsApp desconectada. Reconecte antes de sincronizar.");
                case "ERROR" ->
                        throw new WhatsAppInfrastructureException("WPP_SESSION_ERROR",
                                "Sessao WhatsApp em erro. Verifique o whatsapp-service.");
                case "CONNECTING", "DISCONNECTED", "NOT_CONNECTED" -> {
                    post(baseUrl + "/internal/whatsapp/session/connect", "{}");
                    if (System.currentTimeMillis() >= deadline) {
                        throw new WhatsAppInfrastructureException("WPP_PREFLIGHT_TIMEOUT",
                                "WhatsApp nao conectou dentro do timeout do preflight.");
                    }
                    sleep(5000);
                }
                default -> {
                    if (System.currentTimeMillis() >= deadline) {
                        throw new WhatsAppInfrastructureException("WPP_PREFLIGHT_TIMEOUT",
                                "WhatsApp indisponivel (status=" + status + ").");
                    }
                    sleep(5000);
                }
            }
        }
    }

    private String get(String url) {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofMillis(10000))
                    .header("Authorization", "Bearer " + internalToken)
                    .header("User-Agent", "GendazLeads-OsmSync/1.0")
                    .GET().build();
            HttpResponse<String> res = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            return res.body() == null ? "{}" : res.body();
        } catch (Exception e) {
            throw new WhatsAppInfrastructureException("WPP_STATUS_TRANSPORT_FAILED",
                    "Falha ao consultar status do WhatsApp.", e);
        }
    }

    private void post(String url, String body) {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofMillis(10000))
                    .header("Authorization", "Bearer " + internalToken)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            httpClient.send(req, HttpResponse.BodyHandlers.discarding());
        } catch (Exception ignored) {
            // connect e best-effort; o poll acima decide o resultado final.
        }
    }

    static String parseStatus(String payload) {
        try {
            JsonNode n = JSON.readTree(payload);
            String s = n.path("status").asText("");
            if (!s.isBlank()) return s.toUpperCase(java.util.Locale.ROOT);
            // Formatos alternativos do Node.
            if (n.path("connected").asBoolean(false)) return "CONNECTED";
            if (n.path("qrRequired").asBoolean(false)) return "QR_REQUIRED";
        } catch (Exception ignored) {}
        return "UNKNOWN";
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new WhatsAppInfrastructureException("WPP_PREFLIGHT_INTERRUPTED", "Preflight interrompido.", e);
        }
    }
}
