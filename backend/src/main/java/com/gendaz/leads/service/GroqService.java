package com.gendaz.leads.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gendaz.leads.domain.AnalysisResult;
import com.gendaz.leads.domain.LeadCandidate;
import com.gendaz.leads.exception.ApiException;
import com.gendaz.leads.service.BookingSystemDetector.Detection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.List;
import java.util.Map;

@Service
public class GroqService {

    private static final Logger log = LoggerFactory.getLogger(GroqService.class);
    private static final String ENDPOINT = "https://api.groq.com/openai/v1/chat/completions";

    @Value("${app.groq.api-key:}")
    private String apiKey;

    @Value("${app.groq.model:llama-3.3-70b-versatile}")
    private String model;

    @Value("${app.groq.enabled:true}")
    private boolean enabled;

    @Value("${app.groq.timeout-ms:30000}")
    private int timeoutMs;

    @Value("${app.groq.max-retries:2}")
    private int maxRetries;

    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    public GroqService(RestClient.Builder builder, ObjectMapper objectMapper) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(10000);
        factory.setReadTimeout(timeoutMs);
        this.restClient = builder.requestFactory(factory).build();
        this.objectMapper = objectMapper;
    }

    public boolean isEnabled() {
        return enabled && apiKey != null && !apiKey.isBlank();
    }

    public AnalysisResult analyze(LeadCandidate candidate, Detection booking) {
        if (!isEnabled()) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "GROQ_DISABLED",
                    "Analise de IA desabilitada (GROQ_API_KEY ausente).");
        }
        String system = """
                Voce e um analista comercial da Gendaz, uma plataforma SaaS de agendamento online,
                gestao de agenda, confirmacao de atendimentos e reducao de trabalho manual para pequenos
                e medios negocios. Analise o negocio informado e retorne SOMENTE um objeto JSON valido,
                sem texto adicional, com as chaves:
                businessType (string), services (string resumida), digitalPresence (string: 'none','basic','moderate','strong'),
                usesBookingSystem (boolean), bookingSystemStatus ('NOT_IDENTIFIED'|'IDENTIFIED'|'PROBABLE'|'UNKNOWN'),
                detectedSystem (string ou null), manualAttendanceSignals (string), painPoints (string resumida),
                commercialOpportunity (string), opportunityScore (inteiro 0-100), reasoningSummary (string).
                Regras: NUNCA invente fatos. Se algo nao puder ser determinado, use o valor apropriado
                ('unknown' ou null). bookingSystemStatus deve refletir o sinal tecnicamente detectado.
                """;
        String user = buildAnalysisUser(candidate, booking);

        Map<String, Object> request = Map.of(
                "model", model,
                "temperature", 0.3,
                "max_tokens", 800,
                "response_format", Map.of("type", "json_object"),
                "messages", List.of(
                        Map.of("role", "system", "content", system),
                        Map.of("role", "user", "content", user)));

        String content = callGroq(request, "analise");
        return parseAnalysis(content);
    }

    public String generateMessage(LeadCandidate candidate, AnalysisResult analysis) {
        if (!isEnabled()) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "GROQ_DISABLED",
                    "Geracao de mensagem desabilitada (GROQ_API_KEY ausente).");
        }
        String system = """
                Voce escreve mensagens de prospeccao comerciais para a Gendaz (plataforma de agendamento online).
                Regras OBRIGATORIAS:
                - Use o NOME REAL do negocio informado.
                - Mensagem CURTA (ate 3 frases), natural, em portugues do Brasil, sem caracteres de emoji.
                - SEM language excessivamente comercial ou robotica.
                - Se o negocio JA usa um sistema de agendamento identificado, a abordagem deve ser de
                  entendimento e apresentar a Gendaz como alternativa, SEM afirmar limitacoes sem prova.
                - Se NAO ha sistema identificado, explore organizacao de agenda, confirmacao e reducao de trabalho manual.
                - Retorne APENAS o texto da mensagem, sem aspas, sem explicacao.
                """;
        String user = String.format(
                "Negocio: %s. Tipo: %s. Presenca digital: %s. Sistema de agendamento identificado: %s (%s). " +
                        "Pontos de dor: %s. Oportunidade: %s. Score: %s.",
                candidate.getBusinessName(),
                analysis.businessType() != null ? analysis.businessType() : "desconhecido",
                analysis.digitalPresence() != null ? analysis.digitalPresence() : "desconhecido",
                analysis.detectedSystem() != null ? analysis.detectedSystem() : "nenhum",
                analysis.bookingSystemStatus() != null ? analysis.bookingSystemStatus() : "UNKNOWN",
                analysis.painPoints() != null ? analysis.painPoints() : "desconhecido",
                analysis.commercialOpportunity() != null ? analysis.commercialOpportunity() : "desconhecido",
                analysis.opportunityScore() != null ? analysis.opportunityScore() : "?");

        Map<String, Object> request = Map.of(
                "model", model,
                "temperature", 0.7,
                "max_tokens", 400,
                "messages", List.of(
                        Map.of("role", "system", "content", system),
                        Map.of("role", "user", "content", user)));

        return callGroq(request, "mensagem").trim();
    }

    private String callGroq(Map<String, Object> request, String purpose) {
        int attempt = 0;
        while (true) {
            try {
                String body = objectMapper.writeValueAsString(request);
                String response = restClient.post()
                        .uri(ENDPOINT)
                        .header("Authorization", "Bearer " + apiKey)
                        .header("Content-Type", "application/json")
                        .body(body)
                        .retrieve()
                        .body(String.class);
                JsonNode root = objectMapper.readTree(response);
                JsonNode choices = root.path("choices");
                if (choices.isArray() && !choices.isEmpty()) {
                    return choices.get(0).path("message").path("content").asText();
                }
                throw new ApiException(HttpStatus.BAD_GATEWAY, "GROQ_EMPTY", "Groq retornou resposta vazia.");
            } catch (ApiException e) {
                throw e;
            } catch (RuntimeException | java.io.IOException e) {
                attempt++;
                if (attempt > maxRetries) {
                    log.warn("Groq falhou apos {} tentativas ({}): {}", attempt, purpose, e.getMessage());
                    throw new ApiException(HttpStatus.BAD_GATEWAY, "GROQ_ERROR",
                            "Falha ao comunicar com Groq para " + purpose + ".");
                }
                try {
                    Thread.sleep(800L * attempt);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new ApiException(HttpStatus.BAD_GATEWAY, "GROQ_ERROR", "Interrompido.");
                }
            }
        }
    }

    private AnalysisResult parseAnalysis(String content) {
        try {
            JsonNode node = objectMapper.readTree(content);
            Integer score = node.path("opportunityScore").isIntegralNumber()
                    ? node.path("opportunityScore").asInt() : null;
            if (score != null) score = Math.max(0, Math.min(100, score));
            Boolean uses = node.path("usesBookingSystem").isBoolean() ? node.path("usesBookingSystem").asBoolean() : null;
            String bStatus = asText(node, "bookingSystemStatus");
            if (!List.of("NOT_IDENTIFIED", "IDENTIFIED", "PROBABLE", "UNKNOWN").contains(bStatus)) bStatus = "UNKNOWN";
            return new AnalysisResult(
                    asText(node, "businessType"),
                    asText(node, "services"),
                    asText(node, "digitalPresence"),
                    uses,
                    bStatus,
                    asTextOrNull(node, "detectedSystem"),
                    asText(node, "manualAttendanceSignals"),
                    asText(node, "painPoints"),
                    asText(node, "commercialOpportunity"),
                    score,
                    asText(node, "reasoningSummary"));
        } catch (RuntimeException | java.io.IOException e) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "GROQ_PARSE_ERROR",
                    "Resposta da IA em formato invalido.");
        }
    }

    private String asText(JsonNode node, String key) {
        JsonNode v = node.path(key);
        return v.isMissingNode() || v.isNull() ? "unknown" : v.asText();
    }

    private String asTextOrNull(JsonNode node, String key) {
        JsonNode v = node.path(key);
        if (v.isMissingNode() || v.isNull()) return null;
        String s = v.asText();
        return s.isBlank() ? null : s;
    }

    private String buildAnalysisUser(LeadCandidate candidate, Detection booking) {
        return String.format(
                "Analise este negocio para prospeccao Gendaz.\n" +
                        "Nome: %s\nCategoria: %s\nEndereco: %s\nCidade: %s\nEstado: %s\n" +
                        "Telefone: %s\nWebsite: %s\nInstagram: %s\nFonte: %s\n" +
                        "Sinal tecnico de sistema de agendamento detectado por nossa ferramenta: status=%s, sistema=%s",
                candidate.getBusinessName(),
                candidate.getCategory() != null ? candidate.getCategory() : "desconhecido",
                candidate.getAddress() != null ? candidate.getAddress() : "desconhecido",
                candidate.getCity() != null ? candidate.getCity() : "desconhecido",
                candidate.getState() != null ? candidate.getState() : "desconhecido",
                candidate.getPhone() != null ? candidate.getPhone() : "desconhecido",
                candidate.getWebsite() != null ? candidate.getWebsite() : "nenhum",
                candidate.getInstagramUsername() != null ? candidate.getInstagramUsername() : "nenhum",
                candidate.getSource(),
                booking.status(), booking.system() != null ? booking.system() : "nenhum");
    }
}
