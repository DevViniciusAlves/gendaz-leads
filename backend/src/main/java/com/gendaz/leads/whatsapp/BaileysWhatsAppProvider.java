package com.gendaz.leads.whatsapp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gendaz.leads.exception.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.util.Map;

/**
 * Implementacao HTTP do {@link WhatsAppServiceProvider} contra o whatsapp-service (Node/Baileys).
 * Mapeia respostas/erros do Node para excecoes de dominio. Nunca loga token, QR, JID ou texto integral.
 */
@Component
public class BaileysWhatsAppProvider implements WhatsAppServiceProvider {

    private static final Logger log = LoggerFactory.getLogger(BaileysWhatsAppProvider.class);

    private final WhatsAppProperties properties;
    private final ObjectMapper objectMapper;
    private final RestClient restClient;

    @Autowired
    public BaileysWhatsAppProvider(RestClient.Builder builder, WhatsAppProperties properties,
                                   ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(8000);
        factory.setReadTimeout(properties.getTimeoutMs());
        String baseUrl = properties.getServiceUrl() != null && !properties.getServiceUrl().isBlank()
                ? properties.getServiceUrl()
                : "http://localhost:3001";
        this.restClient = builder.baseUrl(baseUrl).requestFactory(factory).build();
    }

    /** Construtor para testes (RestClient ja montado). */
    BaileysWhatsAppProvider(WhatsAppProperties properties, ObjectMapper objectMapper, RestClient restClient) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.restClient = restClient;
    }

    @Override
    public String getName() {
        return "baileys";
    }

    private void requireConfigured() {
        if (!properties.isConfigured()) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "WHATSAPP_NOT_CONFIGURED",
                    "Integracao WhatsApp nao configurada (WHATSAPP_SERVICE_URL / WHATSAPP_INTERNAL_TOKEN).");
        }
    }

    private RestClient.RequestBodySpec authed(String path) {
        return restClient.post().uri(path)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + properties.getInternalToken())
                .contentType(MediaType.APPLICATION_JSON);
    }

    private RestClient.RequestHeadersSpec authedGet(String path) {
        return restClient.get().uri(path)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + properties.getInternalToken());
    }

    private String execute(RestClient.RequestHeadersSpec spec, String operation) {
        requireConfigured();
        try {
            return spec.retrieve().body(String.class);
        } catch (HttpStatusCodeException e) {
            throw mapNodeError(e, operation);
        } catch (ResourceAccessException e) {
            throw mapNetworkError(e, operation);
        }
    }

    private String execute(RestClient.RequestBodySpec spec, Object body, String operation) {
        requireConfigured();
        try {
            return spec.body(body).retrieve().body(String.class);
        } catch (HttpStatusCodeException e) {
            throw mapNodeError(e, operation);
        } catch (ResourceAccessException e) {
            throw mapNetworkError(e, operation);
        }
    }

    ApiException mapNetworkError(ResourceAccessException e, String operation) {
        Throwable root = e;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        String rootName = root.getClass().getName();
        String msg = root.getMessage() == null ? "" : root.getMessage().toLowerCase(java.util.Locale.ROOT);
        // ConnectException / connect timeout / UnknownHost / NoRouteToHost → falha de conexão.
        if (root instanceof java.net.ConnectException
                || root instanceof java.net.UnknownHostException
                || root instanceof java.net.NoRouteToHostException
                || (root instanceof java.net.SocketTimeoutException && msg.contains("connect"))) {
            log.warn("whatsapp-service connect failed na operacao {} errorType={}", operation, root.getClass().getSimpleName());
            return new ApiException(HttpStatus.BAD_GATEWAY, "WHATSAPP_SERVICE_CONNECT_FAILED",
                    "Falha ao conectar no servico WhatsApp. Verifique se o whatsapp-service esta no ar.");
        }
        // SocketTimeout READ após request enviado → read timeout.
        if (root instanceof java.net.SocketTimeoutException || rootName.contains("SocketTimeout")) {
            log.warn("whatsapp-service read timeout na operacao {} errorType={}", operation, root.getClass().getSimpleName());
            return new ApiException(HttpStatus.GATEWAY_TIMEOUT, "WHATSAPP_READ_TIMEOUT",
                    "Tempo esgotado aguardando resposta do servico WhatsApp.");
        }
        if (msg.contains("connection refused") || msg.contains("connection reset")
                || msg.contains("failed to connect") || msg.contains("connect timed out")) {
            log.warn("whatsapp-service connect failed na operacao {} errorType={}", operation, root.getClass().getSimpleName());
            return new ApiException(HttpStatus.BAD_GATEWAY, "WHATSAPP_SERVICE_CONNECT_FAILED",
                    "Falha ao conectar no servico WhatsApp. Verifique se o whatsapp-service esta no ar.");
        }
        if (msg.contains("read timed out") || msg.contains("read timeout")) {
            log.warn("whatsapp-service read timeout na operacao {} errorType={}", operation, root.getClass().getSimpleName());
            return new ApiException(HttpStatus.GATEWAY_TIMEOUT, "WHATSAPP_READ_TIMEOUT",
                    "Tempo esgotado aguardando resposta do servico WhatsApp.");
        }
        log.warn("whatsapp-service inacessivel na operacao {} errorType={}", operation, root.getClass().getSimpleName());
        return new ApiException(HttpStatus.BAD_GATEWAY, "WHATSAPP_SERVICE_CONNECT_FAILED",
                "Servico WhatsApp inacessivel. Verifique se o whatsapp-service esta no ar.");
    }

    ApiException mapNodeError(HttpStatusCodeException e, String operation) {
        String nodeError = extractNodeError(e.getResponseBodyAsString());
        return switch (nodeError) {
            case "recipient_not_on_whatsapp" -> new ApiException(HttpStatus.BAD_REQUEST,
                    "RECIPIENT_NOT_ON_WHATSAPP", "Destinatario nao possui conta WhatsApp.");
            case "recipient_check_failed" -> new ApiException(HttpStatus.BAD_GATEWAY,
                    "WHATSAPP_RECIPIENT_CHECK_FAILED", "Falha ao verificar destinatario no WhatsApp.");
            case "invalid_recipient" -> new ApiException(HttpStatus.BAD_REQUEST, "WHATSAPP_INVALID_RECIPIENT",
                    "Requisicao invalida para o WhatsApp (invalid_recipient).");
            case "invalid_text" -> new ApiException(HttpStatus.BAD_REQUEST, "WHATSAPP_INVALID_TEXT",
                    "Requisicao invalida para o WhatsApp (invalid_text).");
            case "text_too_long" -> new ApiException(HttpStatus.BAD_REQUEST, "WHATSAPP_TEXT_TOO_LONG",
                    "Requisicao invalida para o WhatsApp (text_too_long).");
            case "invalid_request_id" -> new ApiException(HttpStatus.BAD_REQUEST, "WHATSAPP_INVALID_REQUEST_ID",
                    "Requisicao invalida para o WhatsApp (invalid_request_id).");
            case "invalid_session_id" -> new ApiException(HttpStatus.BAD_REQUEST, "WHATSAPP_INVALID_SESSION",
                    "Sessao WhatsApp invalida.");
            case "session_not_connected" -> new ApiException(HttpStatus.CONFLICT, "WHATSAPP_NOT_CONNECTED",
                    "Sessao WhatsApp nao conectada. Conecte antes de enviar.");
            case "qr_not_available" -> new ApiException(HttpStatus.NOT_FOUND, "WHATSAPP_QR_NOT_AVAILABLE",
                    "QR Code indisponivel no momento.");
            case "unauthorized" -> {
                log.warn("whatsapp-service recusou autenticacao interna na operacao {}", operation);
                yield new ApiException(HttpStatus.BAD_GATEWAY, "WHATSAPP_AUTH_MISMATCH",
                        "Falha de autenticacao interna com o servico WhatsApp.");
            }
            default -> {
                if (e.getStatusCode() == HttpStatus.NOT_FOUND) {
                    yield new ApiException(HttpStatus.NOT_FOUND, "WHATSAPP_QR_NOT_AVAILABLE",
                            "QR Code indisponivel no momento.");
                }
                log.warn("Erro do whatsapp-service na operacao {}: status={}", operation, e.getStatusCode());
                yield new ApiException(HttpStatus.BAD_GATEWAY, "WHATSAPP_SEND_FAILED",
                        "Falha na operacao WhatsApp (" + operation + ").");
            }
        };
    }

    private String extractNodeError(String body) {
        if (body == null || body.isBlank()) return "";
        try {
            JsonNode node = objectMapper.readTree(body);
            return node.path("error").asText("");
        } catch (Exception ex) {
            return "";
        }
    }

    private JsonNode parse(String body, String operation) {
        try {
            return objectMapper.readTree(body == null ? "{}" : body);
        } catch (Exception ex) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "WHATSAPP_BAD_RESPONSE",
                    "Resposta invalida do servico WhatsApp (" + operation + ").");
        }
    }

    @Override
    public WhatsAppSessionStatus connect() {
        String body = execute(authed("/internal/whatsapp/session/connect"), Map.of(), "connect");
        return toStatus(parse(body, "connect"));
    }

    @Override
    public WhatsAppSessionStatus status() {
        String body = execute(authedGet("/internal/whatsapp/session/status"), "status");
        return toStatus(parse(body, "status"));
    }

    @Override
    public WhatsAppQr qr() {
        String body = execute(authedGet("/internal/whatsapp/session/qr"), "qr");
        JsonNode node = parse(body, "qr");
        return new WhatsAppQr(node.path("qr").asText(null), node.path("updatedAt").asText(null));
    }

    @Override
    public WhatsAppSessionStatus logout() {
        String body = execute(authed("/internal/whatsapp/session/logout"), Map.of(), "logout");
        return toStatus(parse(body, "logout"));
    }

    @Override
    public WhatsAppSendResult sendText(String recipient, String text, String requestId) {
        Map<String, String> payload = Map.of(
                "recipient", recipient,
                "text", text,
                "requestId", requestId);
        String body = execute(authed("/internal/whatsapp/session/messages/text"), payload, "send");
        JsonNode node = parse(body, "send");
        boolean sent = "sent".equalsIgnoreCase(node.path("status").asText(""));
        return new WhatsAppSendResult(sent,
                node.path("messageId").asText(null),
                node.path("requestId").asText(requestId),
                node.path("deduplicated").asBoolean(false));
    }

    private WhatsAppSessionStatus toStatus(JsonNode node) {
        return new WhatsAppSessionStatus(
                node.path("status").asText("UNKNOWN"),
                node.path("hasQr").asBoolean(false),
                node.path("lastError").isMissingNode() || node.path("lastError").isNull()
                        ? null : node.path("lastError").asText(null));
    }
}
