package com.gendaz.leads.service.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
    record FailureDetails(String rootCauseType, String safeMessage) {}

    private static FailureDetails getFailureDetails(Throwable t) {
        Throwable root = t;
        while (root.getCause() != null) {
            root = root.getCause();
        }
        
        String type = root.getClass().getSimpleName();
        String message = root.getMessage() != null ? root.getMessage() : "";
        
        // Sanitizar (simples, melhora conforme necessidade)
        message = message.replaceAll("(?i)(password|token|secret|url|database_url|key)=[^\\s&]+", "$1=***");
        if (message.length() > 300) {
            message = message.substring(0, 300);
        }
        
        return new FailureDetails(type, message);
    }

    private static final double BBOX_DELTA = 0.18;
    private static final int MAX_OUT = 200;

    @Value("${app.discovery.osm.enabled:true}")
    private boolean enabled;

    @Value("${app.discovery.osm.timeout-ms:20000}")
    private int timeoutMs;

    @Value("${app.discovery.osm.discovery-deadline-ms:60000}")
    private long discoveryDeadlineMs;

    @Value("${app.discovery.osm.overpass-endpoints:}")
    private String overpassEndpoints;

    @Value("${app.discovery.osm.overpass-url:https://overpass-api.de/api/interpreter}")
    private String overpassUrl;

    @Value("${app.discovery.osm.fallback-url:}")
    private String fallbackUrl;

    @Value("${app.discovery.osm.max-concurrency:1}")
    private int maxConcurrency;

    @Value("${app.discovery.osm.circuit-open-seconds:180}")
    private long circuitOpenSeconds;

    private final RestClient.Builder builder;
    private final ObjectMapper objectMapper;
    private final InstagramDetector instagramDetector;
    private final Normalizer normalizer;
    private OverpassCircuitBreaker circuitBreaker;
    private static final java.util.concurrent.Semaphore overpassSemaphore = new java.util.concurrent.Semaphore(1, true);

    public OpenStreetMapProvider(RestClient.Builder builder, ObjectMapper objectMapper,
                                 InstagramDetector instagramDetector, Normalizer normalizer) {
        this.builder = builder;
        this.objectMapper = objectMapper;
        this.instagramDetector = instagramDetector;
        this.normalizer = normalizer;
        this.circuitBreaker = new OverpassCircuitBreaker(180);
    }

    @PostConstruct
    void logOsmConfig() {
        List<String> endpoints = buildEndpointList();
        this.circuitBreaker = new OverpassCircuitBreaker(circuitOpenSeconds);
        log.info("[osm] config enabled={} timeoutMs={} deadlineMs={} endpoints={} maxConcurrency={} circuitOpenSeconds={}",
                enabled, timeoutMs, discoveryDeadlineMs, endpoints.size(), maxConcurrency, circuitOpenSeconds);
    }

    private RestClient client(int effectiveTimeoutMs) {
        int connectTimeout = Math.min(15000, effectiveTimeoutMs / 2);
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(connectTimeout);
        factory.setReadTimeout(effectiveTimeoutMs);
        return builder
                .requestFactory(factory)
                .defaultHeader("User-Agent", "GendazLeads/1.0 (contato@gendaz.com)")
                .build();
    }

    private List<String> buildEndpointList() {
        List<String> endpoints = new ArrayList<>();
        if (overpassEndpoints != null && !overpassEndpoints.isBlank()) {
            String[] parts = overpassEndpoints.split(",");
            for (String part : parts) {
                String trimmed = part.trim();
                if (!trimmed.isBlank()) {
                    endpoints.add(trimmed);
                }
            }
        }
        if (endpoints.isEmpty()) {
            if (overpassUrl != null && !overpassUrl.isBlank()) {
                endpoints.add(overpassUrl.trim());
            }
            if (fallbackUrl != null && !fallbackUrl.isBlank()) {
                endpoints.add(fallbackUrl.trim());
            }
        }
        if (endpoints.isEmpty()) {
            endpoints.add("https://overpass-api.de/api/interpreter");
        }
        return endpoints;
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

        long deadlineNanos = System.nanoTime() + discoveryDeadlineMs * 1_000_000L;
        String campaignId = "unknown"; // Could be passed via context if needed
        log.info("[osm] discovery_started campaignId={} niche={} location={} requested={} deadlineMs={} maxConcurrency={}",
                campaignId, niche, location, limit, discoveryDeadlineMs, maxConcurrency);

        Geo geo = geocodeOrThrow(location);
        log.info("[osm] geocode_success campaignId={} bbox_source={} south={} west={} north={} east={}",
                campaignId, geo.bboxValid ? "nominatim" : "fallback", geo.south, geo.west, geo.north, geo.east);

        List<String> endpoints = buildEndpointList();
        Map<String, Optional<String>> instagramCache = new HashMap<>();
        Map<String, LeadCandidate> allCandidates = new LinkedHashMap<>();

        Exception lastFailure = null;

        for (int i = 0; i < endpoints.size(); i++) {
            String url = endpoints.get(i);
            String host;
            try { host = java.net.URI.create(url).getHost(); } catch (Exception ignored) { host = "unknown"; }

            if (circuitBreaker.isOpen(host)) {
                log.info("[osm] endpoint_skipped campaignId={} endpointHost={} reason=circuit_open", campaignId, host);
                continue;
            }

            long remainingMs = (deadlineNanos - System.nanoTime()) / 1_000_000L;
            if (remainingMs <= 0) {
                log.warn("[osm] deadline_exceeded campaignId={} elapsedMs={}", campaignId, discoveryDeadlineMs);
                throw new ApiException(HttpStatus.BAD_GATEWAY, "OSM_DISCOVERY_TIMEOUT",
                        "A busca de leads excedeu o tempo máximo permitido.");
            }

            int effectiveTimeout = (int) Math.min(timeoutMs, remainingMs);
            log.info("[osm] overpass_request campaignId={} phase=structured endpointHost={} timeoutMs={} remainingBudgetMs={}",
                    campaignId, host, effectiveTimeout, remainingMs);

            String structuredQuery = buildStructuredQuery(niche, geo, limit);
            try {
                List<LeadCandidate> candidates = executeOverpassQuery(url, structuredQuery, geo, instagramCache, limit, allCandidates, campaignId, host, deadlineNanos);
                for (LeadCandidate c : candidates) {
                    String key = "osm:" + c.getSourceId();
                    allCandidates.putIfAbsent(key, c);
                }

                if (allCandidates.size() >= limit) {
                    log.info("[osm] discovery_finished campaignId={} phase=structured candidates={} elapsedMs={}",
                            campaignId, allCandidates.size(), (discoveryDeadlineMs - remainingMs));
                    return new ArrayList<>(allCandidates.values()).subList(0, Math.min(limit, allCandidates.size()));
                }

                circuitBreaker.recordSuccess(host);
                lastFailure = null;

            } catch (Exception e) {
                lastFailure = e;
                boolean retryable = isRetryable(e);
                FailureDetails fd = getFailureDetails(e);
                log.warn("[osm] overpass_failed campaignId={} phase=structured endpointHost={} errorType={} rootCauseType={} rootCauseMessage={} retryable={} elapsedMs={}",
                        campaignId, host, e.getClass().getSimpleName(), fd.rootCauseType(), fd.safeMessage(), retryable, (discoveryDeadlineMs - remainingMs));

                if (retryable) {
                    circuitBreaker.recordFailure(host);
                }
                if (!retryable) {
                    throw new ApiException(HttpStatus.BAD_GATEWAY, "OSM_OVERPASS_ERROR", "Falha não recuperável no Overpass: " + fd.safeMessage());
                }
                continue;
            }
        }

        if (allCandidates.size() >= limit) {
            return new ArrayList<>(allCandidates.values()).subList(0, limit);
        }

        for (int i = 0; i < endpoints.size(); i++) {
            String url = endpoints.get(i);
            String host;
            try { host = java.net.URI.create(url).getHost(); } catch (Exception ignored) { host = "unknown"; }

            if (circuitBreaker.isOpen(host)) {
                log.info("[osm] endpoint_skipped campaignId={} endpointHost={} reason=circuit_open", campaignId, host);
                continue;
            }

            long remainingMs = (deadlineNanos - System.nanoTime()) / 1_000_000L;
            if (remainingMs <= 0) {
                log.warn("[osm] deadline_exceeded campaignId={} elapsedMs={}", campaignId, discoveryDeadlineMs);
                throw new ApiException(HttpStatus.BAD_GATEWAY, "OSM_DISCOVERY_TIMEOUT",
                        "A busca de leads excedeu o tempo máximo permitido.");
            }

            int effectiveTimeout = (int) Math.min(timeoutMs, remainingMs);
            log.info("[osm] overpass_request campaignId={} phase=name_fallback endpointHost={} timeoutMs={} remainingBudgetMs={}",
                    campaignId, host, effectiveTimeout, remainingMs);

            String nameFallbackQuery = buildNameFallbackQuery(niche, geo, limit);
            if (nameFallbackQuery == null) {
                log.info("[osm] name_fallback_skipped campaignId={} reason=no_fallback_regex", campaignId);
                continue;
            }

            try {
                List<LeadCandidate> candidates = executeOverpassQuery(url, nameFallbackQuery, geo, instagramCache, limit, allCandidates, campaignId, host, deadlineNanos);
                for (LeadCandidate c : candidates) {
                    String key = "osm:" + c.getSourceId();
                    allCandidates.putIfAbsent(key, c);
                }

                if (allCandidates.size() >= limit) {
                    log.info("[osm] discovery_finished campaignId={} phase=name_fallback candidates={} elapsedMs={}",
                            campaignId, allCandidates.size(), (discoveryDeadlineMs - remainingMs));
                    return new ArrayList<>(allCandidates.values()).subList(0, Math.min(limit, allCandidates.size()));
                }

                circuitBreaker.recordSuccess(host);
                lastFailure = null;

            } catch (Exception e) {
                lastFailure = e;
                boolean retryable = isRetryable(e);
                FailureDetails fd = getFailureDetails(e);
                log.warn("[osm] overpass_failed campaignId={} phase=name_fallback endpointHost={} errorType={} rootCauseType={} rootCauseMessage={} retryable={} elapsedMs={}",
                        campaignId, host, e.getClass().getSimpleName(), fd.rootCauseType(), fd.safeMessage(), retryable, (discoveryDeadlineMs - remainingMs));

                if (retryable) {
                    circuitBreaker.recordFailure(host);
                }
                if (!retryable) {
                    throw new ApiException(HttpStatus.BAD_GATEWAY, "OSM_OVERPASS_ERROR", "Falha não recuperável no Overpass: " + fd.safeMessage());
                }
                continue;
            }
        }

        int finalCount = allCandidates.size();
        long elapsedMs = (discoveryDeadlineMs - ((deadlineNanos - System.nanoTime()) / 1_000_000L));
        log.info("[osm] discovery_finished campaignId={} totalCandidates={} elapsedMs={} result={}",
                campaignId, finalCount, elapsedMs, finalCount > 0 ? "partial" : "empty");

        if (finalCount == 0) {
            if (lastFailure != null) {
                String msg = "Falha ao consultar Overpass: " + lastFailure.getMessage();
                throw new ApiException(HttpStatus.BAD_GATEWAY, "OSM_OVERPASS_ERROR", msg);
            }
            return List.of();
        }

        return new ArrayList<>(allCandidates.values()).subList(0, Math.min(limit, finalCount));
    }

    private List<LeadCandidate> executeOverpassQuery(String url, String query, Geo geo,
                                                     Map<String, Optional<String>> instagramCache,
                                                     int limit,
                                                     Map<String, LeadCandidate> existingCandidates,
                                                     String campaignId,
                                                     String host,
                                                     long deadlineNanos) throws RestClientException, java.io.IOException {
        long remainingMs = (deadlineNanos - System.nanoTime()) / 1_000_000L;
        if (remainingMs <= 0) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "OSM_DISCOVERY_TIMEOUT",
                    "A busca de leads excedeu o tempo máximo permitido.");
        }

        int effectiveTimeout = (int) Math.min(timeoutMs, remainingMs);

        boolean acquired = false;
        try {
            acquired = overpassSemaphore.tryAcquire(remainingMs, java.util.concurrent.TimeUnit.MILLISECONDS);
            if (!acquired) {
                throw new ApiException(HttpStatus.BAD_GATEWAY, "OSM_DISCOVERY_TIMEOUT",
                        "Timeout aguardando acesso ao Overpass.");
            }

            long startNs = System.nanoTime();
            String response = client(effectiveTimeout).post()
                    .uri(url)
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .body("data=" + java.net.URLEncoder.encode(query, java.nio.charset.StandardCharsets.UTF_8))
                    .retrieve()
                    .body(String.class);

            long elapsedMs = (System.nanoTime() - startNs) / 1_000_000L;

            JsonNode root = objectMapper.readTree(response);
            JsonNode elements = root.path("elements");
            if (elements.isMissingNode() || !elements.isArray() || elements.isEmpty()) {
                log.info("[osm] overpass_response campaignId={} endpointHost={} elements=0 elapsedMs={}", campaignId, host, elapsedMs);
                return List.of();
            }

            log.info("[osm] overpass_response campaignId={} endpointHost={} elements={} elapsedMs={}", campaignId, host, elements.size(), elapsedMs);

            List<LeadCandidate> candidates = new ArrayList<>();
            for (JsonNode el : elements) {
                if (candidates.size() + existingCandidates.size() >= limit) break;
                LeadCandidate candidate = mapElement(el, geo, instagramCache);
                if (candidate != null) {
                    String key = "osm:" + candidate.getSourceId();
                    if (!existingCandidates.containsKey(key)) {
                        candidates.add(candidate);
                    }
                }
            }
            return candidates;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApiException(HttpStatus.BAD_GATEWAY, "OSM_DISCOVERY_TIMEOUT",
                    "Interrompido aguardando acesso ao Overpass.");
        } finally {
            if (acquired) {
                overpassSemaphore.release();
            }
        }
    }

    static boolean isRetryable(Exception e) {
        if (e instanceof HttpStatusCodeException http) {
            int v = http.getStatusCode().value();
            return v == 429 || v == 502 || v == 503 || v == 504;
        }

        Throwable root = e;
        while (root != null) {
            if (root instanceof ConnectException || root instanceof SocketTimeoutException
                || root instanceof java.net.NoRouteToHostException
                || root instanceof java.net.UnknownHostException
                || (root.getMessage() != null && root.getMessage().toLowerCase().contains("connection reset"))) {
                return true;
            }
            if (root instanceof javax.net.ssl.SSLHandshakeException) return false;
            root = root.getCause();
        }
        
        // Mantemos comportamento padrão para ResourceAccessException genérico se não identificar causa específica
        // mas o prompt pede para não tratar "todo ResourceAccessException" como retryable.
        // Já identifiquei os casos retryable específicos.
        return false;
    }

    private void sleepBackoff(int attempt) {
        try { Thread.sleep(1000L * attempt); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
    }

    private Geo geocodeOrThrow(String location) {
        Exception lastFailure = null;
        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                String response = client(timeoutMs).get()
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
                boolean retryable = isRetryable(e);
                FailureDetails fd = getFailureDetails(e);
                log.warn("[osm] geocode_failed attempt={} errorType={} rootCauseType={} rootCauseMessage={} retryable={}", 
                        attempt, e.getClass().getSimpleName(), fd.rootCauseType(), fd.safeMessage(), retryable);
                if (!retryable) throw new ApiException(HttpStatus.BAD_GATEWAY, "OSM_GEOCODE_ERROR", "Falha não recuperável: " + fd.safeMessage());
            }
        }
        // Falha Nominatim apos retries: OSM_GEOCODE_ERROR.
        throw new ApiException(HttpStatus.BAD_GATEWAY, "OSM_GEOCODE_ERROR",
                "Falha ao geocodificar local após retentativas: " + location);
    }

    String buildStructuredQuery(String niche, Geo geo, int limit) {
        String bbox = buildBbox(geo);
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
        sb.append(");out center tags ").append(Math.min(limit + 5, MAX_OUT)).append(";");
        return sb.toString();
    }

    String buildNameFallbackQuery(String niche, Geo geo, int limit) {
        NicheMapper.NicheStrategy strategy = NicheMapper.resolve(niche);
        String fallbackRegex = strategy.fallbackNameRegex();
        if (fallbackRegex == null || fallbackRegex.isBlank() || fallbackRegex.equals("''")) {
            return null;
        }
        String bbox = buildBbox(geo);
        StringBuilder sb = new StringBuilder();
        sb.append("[out:json][timeout:25];(");
        sb.append(String.format("nwr[\"name\"~\"%s\",i](%s);", fallbackRegex, bbox));
        sb.append(");out center tags ").append(Math.min(limit + 5, MAX_OUT)).append(";");
        return sb.toString();
    }

    private String buildBbox(Geo geo) {
        if (geo.bboxValid) {
            double spanLat = geo.north - geo.south;
            double spanLon = geo.east - geo.west;
            double maxSpan = 2.0;
            if (spanLat > maxSpan || spanLon > maxSpan) {
                double centerLat = (geo.south + geo.north) / 2.0;
                double centerLon = (geo.west + geo.east) / 2.0;
                double halfLat = maxSpan / 2.0;
                double halfLon = maxSpan / 2.0;
                double south = centerLat - halfLat;
                double north = centerLat + halfLat;
                double west = centerLon - halfLon;
                double east = centerLon + halfLon;
                return String.format(Locale.US, "%f,%f,%f,%f", south, west, north, east);
            }
            return String.format(Locale.US, "%f,%f,%f,%f", geo.south, geo.west, geo.north, geo.east);
        } else {
            return String.format(Locale.US, "%f,%f,%f,%f",
                    geo.lat - BBOX_DELTA, geo.lon - BBOX_DELTA, geo.lat + BBOX_DELTA, geo.lon + BBOX_DELTA);
        }
    }

    LeadCandidate mapElement(JsonNode el, Geo geo, Map<String, Optional<String>> instagramCache) {
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
            String website = candidate.getWebsite();
            if (website != null && !website.isBlank() && instagramDetector != null) {
                String normalizedWebsite = website.trim().toLowerCase();
                
                Optional<String> detected = instagramCache.computeIfAbsent(normalizedWebsite, 
                        key -> Optional.ofNullable(instagramDetector.detectFromWebsite(key)));
                
                if (detected.isPresent()) {
                    candidate.setInstagramUsername(detected.get());
                    candidate.setInstagramUrl("https://instagram.com/" + detected.get());
                    candidate.setInstagramStatus("FOUND");
                } else {
                    candidate.setInstagramStatus("NOT_FOUND");
                }
            } else {
                candidate.setInstagramStatus("NOT_FOUND");
            }
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

    static class OverpassCircuitBreaker {
        private static final class EndpointState {
            volatile State state = State.CLOSED;
            volatile long openSince = 0;
            volatile int failureCount = 0;
            volatile int halfOpenSuccesses = 0;

            enum State { CLOSED, OPEN, HALF_OPEN }
        }

        private final java.util.Map<String, EndpointState> states = new java.util.concurrent.ConcurrentHashMap<>();
        private final long openSeconds;

        OverpassCircuitBreaker() {
            this.openSeconds = 180;
        }

        OverpassCircuitBreaker(long openSeconds) {
            this.openSeconds = openSeconds;
        }

        boolean isOpen(String host) {
            EndpointState es = states.computeIfAbsent(host, k -> new EndpointState());
            if (es.state == EndpointState.State.OPEN) {
                if (System.currentTimeMillis() - es.openSince >= openSeconds * 1000L) {
                    es.state = EndpointState.State.HALF_OPEN;
                    es.halfOpenSuccesses = 0;
                    return false;
                }
                return true;
            }
            return false;
        }

        void recordSuccess(String host) {
            EndpointState es = states.computeIfAbsent(host, k -> new EndpointState());
            if (es.state == EndpointState.State.HALF_OPEN) {
                es.halfOpenSuccesses++;
                if (es.halfOpenSuccesses >= 1) {
                    es.state = EndpointState.State.CLOSED;
                    es.failureCount = 0;
                }
            } else if (es.state == EndpointState.State.CLOSED) {
                es.failureCount = 0;
            }
        }

        void recordFailure(String host) {
            EndpointState es = states.computeIfAbsent(host, k -> new EndpointState());
            if (es.state == EndpointState.State.HALF_OPEN) {
                es.state = EndpointState.State.OPEN;
                es.openSince = System.currentTimeMillis();
                es.failureCount = 0;
            } else if (es.state == EndpointState.State.CLOSED) {
                es.failureCount++;
                if (es.failureCount >= 1) {
                    es.state = EndpointState.State.OPEN;
                    es.openSince = System.currentTimeMillis();
                }
            }
        }
    }
}
