package com.gendaz.leads.service.provider;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gendaz.leads.domain.LeadCandidate;
import com.gendaz.leads.exception.ApiException;
import com.gendaz.leads.service.InstagramDetector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Component
public class GooglePlacesProvider implements LeadDiscoveryProvider {

    private static final Logger log = LoggerFactory.getLogger(GooglePlacesProvider.class);
    private static final String ENDPOINT = "https://places.googleapis.com/v1/places:searchText";

    @Value("${app.discovery.google.api-key:}")
    private String apiKey;

    @Value("${app.discovery.google.enabled:true}")
    private boolean enabled;

    @Value("${app.discovery.google.timeout-ms:8000}")
    private int timeoutMs;

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final InstagramDetector instagramDetector;

    public GooglePlacesProvider(RestClient.Builder builder, ObjectMapper objectMapper, InstagramDetector instagramDetector) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5000);
        factory.setReadTimeout(15000);
        this.restClient = builder.requestFactory(factory).build();
        this.objectMapper = objectMapper;
        this.instagramDetector = instagramDetector;
    }

    @Override
    public String getName() {
        return "google";
    }

    @Override
    public boolean isEnabled() {
        return enabled && apiKey != null && !apiKey.isBlank();
    }

    @Override
    public List<LeadCandidate> discover(String niche, String location, int limit) {
        if (!isEnabled()) {
            log.info("Google Places desabilitado ou sem API key");
            return List.of();
        }
        String query = String.format("%s em %s", niche, location);
        String fieldMask = "places.id,places.displayName,places.formattedAddress,places.location," +
                "places.types,places.websiteUri,places.internationalPhoneNumber,places.nationalPhoneNumber," +
                "places.googleMapsUri,places.addressComponents";
        try {
            String response = restClient.post()
                    .uri(ENDPOINT)
                    .header("X-Goog-Api-Key", apiKey)
                    .header("X-Goog-FieldMask", fieldMask)
                    .header("Content-Type", "application/json")
                    .body(java.util.Map.of("textQuery", query, "maxResultCount", Math.min(limit, 20), "languageCode", "pt-BR"))
                    .retrieve()
                    .body(String.class);

            JsonNode root = objectMapper.readTree(response);
            JsonNode places = root.path("places");
            if (places.isMissingNode() || places.isEmpty()) {
                return List.of();
            }
            Set<LeadCandidate> candidates = new LinkedHashSet<>();
            for (JsonNode place : places) {
                LeadCandidate candidate = mapPlace(place);
                if (candidate != null) candidates.add(candidate);
            }
            return new ArrayList<>(candidates);
        } catch (ApiException e) {
            throw e;
        } catch (RuntimeException | java.io.IOException e) {
            log.warn("Falha ao consultar Google Places: {}", e.getMessage());
            throw new ApiException(HttpStatus.BAD_GATEWAY, "GOOGLE_PLACES_ERROR",
                    "Falha ao obter leads do Google Places.");
        }
    }

    private LeadCandidate mapPlace(JsonNode place) {
        String placeId = place.path("id").asText(null);
        String name = place.path("displayName").path("text").asText(null);
        if (name == null || placeId == null) return null;

        LeadCandidate candidate = new LeadCandidate(name, "google", placeId);
        candidate.setWebsite(place.path("websiteUri").asText(null));
        candidate.setPhone(orEmpty(place.path("internationalPhoneNumber").asText(null),
                place.path("nationalPhoneNumber").asText(null)));
        parseAddress(place, candidate);
        parseTypes(place, candidate);

        String instagram = instagramDetector.detectFromWebsite(candidate.getWebsite());
        if (instagram != null) {
            candidate.setInstagramUsername(instagram);
            candidate.setInstagramUrl("https://instagram.com/" + instagram);
            candidate.setInstagramStatus("FOUND");
        }
        return candidate;
    }

    private void parseAddress(JsonNode place, LeadCandidate candidate) {
        String formatted = place.path("formattedAddress").asText(null);
        candidate.setAddress(formatted);
        JsonNode components = place.path("addressComponents");
        if (components.isArray()) {
            for (JsonNode c : components) {
                JsonNode types = c.path("types");
                String longText = c.path("longText").asText(null);
                for (JsonNode t : types) {
                    String type = t.asText();
                    if ("locality".equals(type) && candidate.getCity() == null) candidate.setCity(longText);
                    else if ("administrative_area_level_1".equals(type) && candidate.getState() == null) candidate.setState(longText);
                    else if ("country".equals(type) && candidate.getCountry() == null) candidate.setCountry(longText);
                }
            }
        }
        if (candidate.getCity() == null && formatted != null) {
            String[] parts = formatted.split(",");
            if (parts.length >= 2) candidate.setCity(parts[parts.length - 2].trim());
            if (parts.length >= 1) candidate.setState(parts[parts.length - 1].trim());
        }
    }

    private void parseTypes(JsonNode place, LeadCandidate candidate) {
        JsonNode types = place.path("types");
        if (types.isArray() && !types.isEmpty()) {
            candidate.setCategory(types.get(0).asText());
        }
    }

    private String orEmpty(String a, String b) {
        if (a != null && !a.isBlank()) return a;
        return (b != null && !b.isBlank()) ? b : null;
    }
}
