package com.gendaz.leads.service.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.gendaz.leads.domain.LeadCandidate;
import com.gendaz.leads.exception.ApiException;
import com.gendaz.leads.service.InstagramDetector;
import com.gendaz.leads.util.Normalizer;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.util.*;

@Component
public class OpenStreetMapProvider implements LeadDiscoveryProvider {

    private static final Logger log = LoggerFactory.getLogger(OpenStreetMapProvider.class);
    private static final SerializationFeature IGNORE_DATES_AS_NULLS = SerializationFeature.IgnoreDatesAsNulls;

    private static String rootCause(Throwable t) {
        // Returns a short summary for log correlation without leaking sensitive data.
        return (t.getMessage() != null ? t.getMessage() : "") +
                (t.getCause() != null ? " cause=" + rootCause(t.getCause()) : "");
    }

    private static final double BBOX_DELTA = 0.18;
    private static final int MIN_OUT = 80;
    private static final int MAX_OUT = 200;

    @Value("${app.discovery.osm.enabled:true}")
    private boolean enabled;

    @Value("${app.discovery.osm.timeout-ms:30000}")
    private int timeoutMs;

    @Value("${app.discovery.osm.overpass-url:https://overpass-api.de/api/interpreter}")
    private String overpassUrl;

    @Value("${app.discovery.osm.fallback-url:}")
    private String fallbackUrl;

    private final RestClient.Builder builder;
    private final ObjectMapper objectMapper;
    private final InstagramDetector instagramDetector;
    private final Normalizer normalizer;

    public OpenStreetMapProvider(RestClient.Builder builder, ObjectMapper objectMapper,
                                 InstagramDetector instagramDetector, Normalizer normalizer) {
        this.builder = builder;
        this.objectMapper = objectMapper;
        this.instagramDetector = instagramDetector;
        this.normalizer = normalizer;
    }

    @PostConstruct
    void logOsmConfig() {
        // Sem expor URL: apenas se o fallback esta configurado.
        log.info("[osm] config enabled={} timeoutMs={} osmFallbackConfigured={}",
                enabled, timeoutMs, fallbackUrl != null && !fallbackUrl.isBlank());
    }

    private RestClient client() {
        int effective = timeoutMs > 0 ? timeoutMs : 30000;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(15000);
        factory.setReadTimeout(effective);
        return builder
                .requestFactory(factory)
                .defaultHeader("User-Agent", "GendazLeads/1.0 (contato@gendaz.com)")
                .build();
    }

    @Override
    public String getName() {
        return "openstreetmap";
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    @Override
    public List<LeadCandidate> discover(String niche, String location, int limit) {
        if (!isEnabled()) return List.of();
        log.info("[osm] discovery_started niche={} location={} requested={}", niche, location, limit);

        Geo geo = geocodeOrThrow(location);
        log.info("[osm] geocode_success=true bbox_source={}", geo.bboxValid ? "nominatim" : "fallback");

        String query = buildOverpassQuery(niche, geo, limit);

        List<String> endpoints = new ArrayList<>();
        endpoints.add(overpassUrl);
        if (fallbackUrl != null && !fallbackUrl.isBlank()) {
            endpoints.add(fallbackUrl);
        }

        Exception lastFailure = null;
        for (int i = 0; i < endpoints.size(); i++) {
            String url = endpoints.get(i);
            boolean isFallback = (i > 0);

            for (int attempt = 1; attempt <= 3; attempt++) {
                try {
                    log.info("[osm] overpass_attempt={} endpoint={}", attempt, url);
                    String response = client().post()
                            .uri(url)
                            .header("Content-Type", "application/x-www-form-urlencoded")
                            .body("data=" + java.net.URLEncoder.encode(query, java.nio.charset.StandardCharsets.UTF_8))
                            .retrieve()
                            .body(String.class);

                    JsonNode root = objectMapper.readTree(response);
                    JsonNode elements = root.path("elements");
                    if (elements.isMissingNode() || !elements.isArray() || elements.isEmpty()) {
                        // Overpass 200 + elements=[]: resultado valido zero. Sem fallback.
                        log.info("[osm] discovery_finished returned=0");
                        return List.of();
                    }

                    log.info("[osm] elements_received={}", elements.size());
                    Map<String, LeadCandidate> byKey = new LinkedHashMap<>();
                    for (JsonNode el : elements) {
                        if (byKey.size() >= limit) break;
                        LeadCandidate candidate = mapElement(el, geo);
                        if (candidate != null) {
                            String key = "osm:" + candidate.getSourceId();
                            byKey.putIfAbsent(key, candidate);
                        }
                    }
                    log.info("[osm] candidates_after_dedupe={} discovery_finished returned={}", byKey.size(), byKey.size());
                    return new ArrayList<>(byKey.values());

                } catch (RestClientException | java.io.IOException e) {
                    lastFailure = e;
                    log.warn("[osm] overpass_failed attempt={} errorType={} rootCause={} retryable={}", 
                            attempt, e.getClass().getSimpleName(), rootCause(e), isRetryable(e));
                    if (!retryable) break; // falha definitiva: nao insistir neste endpoint
                    if (attempt < 3) {
                        sleepBackoff(attempt);
                    }
                }
            }
            // Fallback endpoint somente apos falha real do primary (nao em zero valido, que ja retornou).
            if (!isFallback && endpoints.size() > 1 && lastFailure != null) {
                log.info("[osm] fallback_endpoint=true");
            }
            if (isFallback || endpoints.size() == 1) {
                break;
            }
            // Se primary falhou mas existe fallback, continua o loop para tentar o fallback.
            if (lastFailure == null) break;
        }

        throw new ApiException(HttpStatus.BAD_GATEWAY, "OSM_OVERPASS_ERROR",
                "Falha ao consultar Overpass após retentativas.");
    }

    static boolean isRetryable(Exception e) {
        if (e instanceof HttpStatusCodeException http) {
            int v = http.getStatusCode().value();
            return v == 429 || v == 502 || v == 503 || v == 504;
        }
        if (e instanceof ResourceAccessException) {
            // connect timeout / read timeout / conexao recusada: retry.
            // Read timeout apos POST sera classificado como AMBIGUOUS no envio,
            // mas na descoberta ainda vale retry limitado.
            return true;
        }
        Throwable t = e;
        while (t != null) {
            if (t instanceof ConnectException || t instanceof SocketTimeoutException) return true;
            t = t.getCause();
        }
        String msg = e.getMessage() != null ? e.getMessage().toLowerCase() : "";
        return msg.contains("timed out") || msg.contains("timeout") || msg.contains("connect")
                || msg.contains("429") || msg.contains("502") || msg.contains("503") || msg.contains("504");
    }

    private void sleepBackoff(int attempt) {
        try { Thread.sleep(1000L * attempt); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
    }

    private Geo geocodeOrThrow(String location) {
        Exception lastFailure = null;
        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                String response = client().get()
                        .uri(uriBuilder -> uriBuilder
                                .scheme("https").host("nominatim.openstreetmap.org").path("/search")
                                .queryParam("q", location)
                                .queryParam("format", "json")
                                .queryParam("limit", "1")
                                .queryParam("addressdetails", "1")
                                .build())
                        .retrieve()
                        .body(String.class);
                JsonNode arr = objectMapper.readTree(response);
                // Nominatim 200 + []: LOCATION_NOT_FOUND.
                if (!arr.isArray() || arr.isEmpty()) {
                    throw new ApiException(HttpStatus.NOT_FOUND, "LOCATION_NOT_FOUND",
                            "Local não encontrado: " + location);
                }

                JsonNode hit = arr.get(0);
                double lat = hit.path("lat").asDouble(Double.NaN);
                double lon = hit.path("lon").asDouble(Double.NaN);
                if (Double.isNaN(lat) || Double.isNaN(lon)) {
                    throw new ApiException(HttpStatus.NOT_FOUND, "LOCATION_NOT_FOUND",
                            "Local não encontrado: " + location);
                }

                JsonNode address = hit.path("address");
                String city = firstPresent(address, "city", "town", "village", "municipality");
                String state = address.path("state").asText(null);
                String country = address.path("country").asText(null);
                if (country == null || country.isBlank()) country = "BR";

                double south = 0, north = 0, west = 0, east = 0;
                boolean bboxValid = false;
                JsonNode bboxNode = hit.path("boundingbox");
                if (bboxNode.isArray() && bboxNode.size() == 4) {
                    // Nominatim retorna south, north, west, east.
                    south = bboxNode.get(0).asDouble();
                    north = bboxNode.get(1).asDouble();
                    west = bboxNode.get(2).asDouble();
                    east = bboxNode.get(3).asDouble();

                    if (south < north && west < east && (north - south) < 2.0 && (east - west) < 2.0) {
                        bboxValid = true;
                    }
                }

                return new Geo(lat, lon, blankToNull(city), blankToNull(state), country, south, north, west, east, bboxValid);
            } catch (ApiException e) {
                throw e;
            } catch (RestClientException | java.io.IOException e) {
                lastFailure = e;
                log.warn("[osm] geocode_failed attempt={} errorType={} rootCause={}", 
                        attempt, e.getClass().getSimpleName(), rootCause(e));
            }
        }
        // Falha Nominatim apos retries: OSM_GEOCODE_ERROR.
        throw new ApiException(HttpStatus.BAD_GATEWAY, "OSM_GEOCODE_ERROR",
                "Falha ao geocodificar local após retentativas: " + location);
    }

    String buildOverpassQuery(String niche, Geo geo, int limit) {
        String bbox;
        if (geo.bboxValid) {
            bbox = String.format(Locale.US, "%f,%f,%f,%f", geo.south, geo.west, geo.north, geo.east);
        } else {
            bbox = String.format(Locale.US, "%f,%f,%f,%f",
                    geo.lat - BBOX_DELTA, geo.lon - BBOX_DELTA, geo.lat + BBOX_DELTA, geo.lon + BBOX_DELTA);
        }
        
        NicheMapper.NicheStrategy strategy = NicheMapper.resolve(niche);
        StringBuilder sb = new StringBuilder();
        sb.append("[out:json][timeout:25];(");
        for (String t : strategy.tagFilters()) {
            StringBuilder filterBuilder = new StringBuilder();
            for (String part : t.split(",")) {
                String[] kv = part.split("=", 2);
                if (kv.length == 2 && !kv[0].isBlank() && !kv[1].isBlank()) {
                    filterBuilder.append(String.format("[\"%s\"=\"%s\"]", escapeTag(kv[0].trim()), escapeTag(kv[1].trim())));
                }
            }
            if (!filterBuilder.isEmpty()) {
                sb.append(String.format("nwr%s(%s);", filterBuilder, bbox));
            }
        }
        if (strategy.fallbackNameRegex() != null && !strategy.fallbackNameRegex().isBlank() && !strategy.fallbackNameRegex().equals("''")) {
            sb.append(String.format("nwr[\"name\"~\"%s\",i](%s);", strategy.fallbackNameRegex(), bbox));
        }
        sb.append(");out center tags ").append(Math.min(Math.max(limit, MIN_OUT), MAX_OUT)).append(";");
        return sb.toString();
    }

    LeadCandidate mapElement(JsonNode el, Geo geo) {
        JsonNode tags = el.path("tags");
        if (tags.isMissingNode() || !tags.isObject()) return null;
        String name = asTextOrNull(tags, "name");
        if (name == null || name.isBlank()) return null;

        String type = el.path("type").asText("");
        String id = el.path("id").asText("");
        if (type.isBlank() || id.isBlank()) return null;

        LeadCandidate candidate = new LeadCandidate(name.trim(), "openstreetmap", type + "/" + id);
        candidate.setCategory(buildCategory(tags));
        candidate.setWebsite(firstPresent(tags, "contact:website", "website", "url"));
        candidate.setPhone(firstPresent(tags, "contact:phone", "phone", "contact:mobile", "mobile"));
        candidate.setCity(firstPresent(tags, "addr:city", "addr:town") != null ? firstPresent(tags, "addr:city", "addr:town") : geo.city);
        candidate.setState(asTextOrNull(tags, "addr:state") != null ? asTextOrNull(tags, "addr:state") : geo.state);
        candidate.setCountry(asTextOrNull(tags, "addr:country") != null ? asTextOrNull(tags, "addr:country") : geo.country);
        candidate.setAddress(buildAddress(tags, geo));

        String rawIg = firstPresent(tags, "contact:instagram", "instagram");
        String username = normalizer.normalizeInstagram(rawIg);
        if (username != null && !username.isBlank()) {
            candidate.setInstagramUsername(username);
            candidate.setInstagramUrl("https://instagram.com/" + username);
            candidate.setInstagramStatus("FOUND");
        } else {
            candidate.setInstagramStatus("NOT_FOUND");
        }
        return candidate;
    }

    static String buildCategory(JsonNode tags) {
        if (asTextOrNull(tags, "beauty") != null) return "beauty:" + asTextOrNull(tags, "beauty");
        if (asTextOrNull(tags, "shop") != null) return "shop:" + asTextOrNull(tags, "shop");
        if (asTextOrNull(tags, "amenity") != null) return "amenity:" + asTextOrNull(tags, "amenity");
        return null;
    }

    static String buildAddress(JsonNode tags, Geo geo) {
        String street = asTextOrNull(tags, "addr:street");
        String number = asTextOrNull(tags, "addr:housenumber");
        String suburb = asTextOrNull(tags, "addr:suburb", "addr:neighbourhood", "addr:district");
        String city = asTextOrNull(tags, "addr:city", "addr:town", "addr:village", "addr:municipality");
        String state = asTextOrNull(tags, "addr:state");
        if (city == null) city = geo.city;
        if (state == null) state = geo.state;

        List<String> head = new ArrayList<>();
        StringBuilder streetPart = new StringBuilder();
        if (street != null) streetPart.append(street);
        if (number != null) {
            if (!streetPart.isEmpty()) streetPart.append(" ");
            streetPart.append(number);
        }
        if (!streetPart.isEmpty()) head.add(streetPart.toString());
        if (suburb != null) head.add(suburb);

        List<String> tail = new ArrayList<>();
        if (city != null) tail.add(city);
        if (state != null) tail.add(state);

        String left = String.join(", ", head);
        String right = String.join(" - ", tail);
        if (!left.isEmpty() && !right.isEmpty()) return left + ", " + right;
        if (!left.isEmpty()) return left;
        if (!right.isEmpty()) return right;
        return null;
    }

    private static String firstPresent(JsonNode tags, String... keys) {
        for (String k : keys) {
            String v = asTextOrNull(tags, k);
            if (v != null && !v.isBlank()) return v.trim();
        }
        return null;
    }

    private static String asTextOrNull(JsonNode tags, String... keys) {
        for (String k : keys) {
            JsonNode n = tags.path(k);
            if (!n.isMissingNode() && !n.isNull()) {
                String v = n.asText(null);
                if (v != null && !v.isBlank()) return v.trim();
            }
        }
        return null;
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }

    private static String escapeTag(String s) {
        return s.replace("\\", "").replace("\"", "");
    }

    record Geo(double lat, double lon, String city, String state, String country, double south, double north, double west, double east, boolean bboxValid) {}
}
