package com.gendaz.leads.service.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gendaz.leads.domain.LeadCandidate;
import com.gendaz.leads.exception.ApiException;
import com.gendaz.leads.service.InstagramDetector;
import com.gendaz.leads.service.WebsiteContactEnricher;
import com.gendaz.leads.util.Normalizer;
import com.gendaz.leads.util.SsrfGuard;
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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

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
        message = message.replaceAll("(?i)(password|token|secret|url|database_url|key)=[^\\s&]+", "$1=***");
        if (message.length() > 300) message = message.substring(0, 300);
        return new FailureDetails(type, message);
    }

    private static final int MAX_OUT = 200;

    @Value("${app.discovery.osm.enabled:true}")
    private boolean enabled;

    @Value("${app.discovery.osm.timeout-ms:20000}")
    private int timeoutMs;

    @Value("${app.discovery.osm.nominatim-timeout-ms:8000}")
    private int nominatimTimeoutMs;

    @Value("${app.discovery.osm.nominatim-max-attempts:2}")
    private int nominatimMaxAttempts;

    @Value("${app.discovery.osm.discovery-deadline-ms:60000}")
    private long discoveryDeadlineMs;

    @Value("${app.discovery.osm.overpass-endpoints:https://overpass.private.coffee/api/interpreter,https://maps.mail.ru/osm/tools/overpass/api/interpreter,https://overpass-api.de/api/interpreter}")
    private String overpassEndpoints;

    @Value("${app.discovery.osm.overpass-url:}")
    private String overpassUrl;

    @Value("${app.discovery.osm.fallback-url:}")
    private String fallbackUrl;

    @Value("${app.discovery.osm.max-concurrency:1}")
    private int maxConcurrency;

    @Value("${app.discovery.osm.circuit-open-seconds:180}")
    private long circuitOpenSeconds;

    @Value("${app.discovery.osm.tile-target-km:8}")
    private double tileTargetKm;

    private final RestClient.Builder builder;
    private final ObjectMapper objectMapper;
    private final InstagramDetector instagramDetector;
    private final WebsiteContactEnricher websiteContactEnricher;
    private final Normalizer normalizer;
    private final SsrfGuard ssrfGuard;
    private OverpassCircuitBreaker circuitBreaker;
    private final Semaphore overpassSemaphore = new Semaphore(1, true);
    private final Map<String, WebsiteContactEnricher.WebsiteContactData> enrichmentCache = new ConcurrentHashMap<>();

    public OpenStreetMapProvider(RestClient.Builder builder, ObjectMapper objectMapper,
                                 InstagramDetector instagramDetector, WebsiteContactEnricher websiteContactEnricher,
                                 Normalizer normalizer, SsrfGuard ssrfGuard) {
        this.builder = builder;
        this.objectMapper = objectMapper;
        this.instagramDetector = instagramDetector;
        this.websiteContactEnricher = websiteContactEnricher;
        this.normalizer = normalizer;
        this.ssrfGuard = ssrfGuard;
        this.circuitBreaker = new OverpassCircuitBreaker(180);
    }

    @PostConstruct
    void logOsmConfig() {
        List<String> endpoints = buildEndpointList();
        this.circuitBreaker = new OverpassCircuitBreaker(circuitOpenSeconds);
        log.info("[osm] config enabled={} timeoutMs={} nominatimTimeoutMs={} nominatimMaxAttempts={} deadlineMs={} endpoints={} maxConcurrency={} circuitOpenSeconds={} tileTargetKm={}",
                enabled, timeoutMs, nominatimTimeoutMs, nominatimMaxAttempts, discoveryDeadlineMs, endpoints.size(), maxConcurrency, circuitOpenSeconds, tileTargetKm);
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
                if (!trimmed.isBlank()) endpoints.add(trimmed);
            }
        }
        if (endpoints.isEmpty()) {
            if (overpassUrl != null && !overpassUrl.isBlank()) endpoints.add(overpassUrl.trim());
            if (fallbackUrl != null && !fallbackUrl.isBlank()) endpoints.add(fallbackUrl.trim());
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
    public LeadDiscoveryResult discover(LeadDiscoveryRequest request) {
        if (!isEnabled()) return LeadDiscoveryResult.empty("OSM_DISABLED", "OpenStreetMap provider desabilitado");

        DiscoveryDeadline deadline = new DiscoveryDeadline(discoveryDeadlineMs);
        Long campaignId = request.campaignId();

        log.info("[osm] discovery_started campaignId={} niche={} city={} country={} requestedUseful={} deadlineMs={}",
                campaignId, request.niche(), request.city(), request.country(), request.targetQuantity(), discoveryDeadlineMs);

        Geo geo;
        try {
            geo = geocodeCityCountry(request.city(), request.country(), deadline, campaignId);
        } catch (ApiException e) {
            return LeadDiscoveryResult.empty(e.getCode(), e.getMessage());
        }

        List<Tile> tiles = buildCityTiles(geo);
        if (tiles.isEmpty()) {
            return LeadDiscoveryResult.empty("OSM_CITY_TILES_EMPTY", "Nenhum tile gerado para a cidade");
        }

        List<String> endpoints = buildEndpointList();
        Map<String, Optional<String>> instagramCache = new HashMap<>();
        Map<String, LeadCandidate> allCandidates = new LinkedHashMap<>();
        Map<String, LeadCandidate> dedupeBySourceId = new HashMap<>();

        AtomicInteger usefulFound = new AtomicInteger(0);
        boolean allEndpointsFailed = true;
        Exception lastFailure = null;
        boolean deadlineExceeded = false;

        for (int tileIdx = 0; tileIdx < tiles.size(); tileIdx++) {
            Tile tile = tiles.get(tileIdx);

            if (deadline.isExpired()) {
                deadlineExceeded = true;
                log.warn("[osm] deadline_exceeded campaignId={} tileIdx={}/{}", campaignId, tileIdx, tiles.size());
                break;
            }

            long remainingUseful = request.targetQuantity() - usefulFound.get();
            if (remainingUseful <= 0) {
                log.info("[osm] quantity_reached campaignId={} usefulFound={}", campaignId, usefulFound.get());
                break;
            }

            log.info("[osm] tile_start campaignId={} tileIdx={}/{} remainingUseful={} bbox={}",
                    campaignId, tileIdx, tiles.size(), remainingUseful, tile.bbox());

            for (int phase = 0; phase < 2; phase++) {
                if (deadline.isExpired()) {
                    deadlineExceeded = true;
                    break;
                }

                if (usefulFound.get() >= request.targetQuantity()) break;

                int remainingUsefulInt = (int) Math.min(remainingUseful, Integer.MAX_VALUE);
                String query = (phase == 0) ? buildStructuredQuery(request.niche(), tile, remainingUsefulInt)
                                            : buildNameFallbackQuery(request.niche(), tile, remainingUsefulInt);
                if (query == null) {
                    if (phase == 1) log.info("[osm] name_fallback_skipped campaignId={} tileIdx={} reason=no_fallback", campaignId, tileIdx);
                    continue;
                }

                for (int endpointIdx = 0; endpointIdx < endpoints.size(); endpointIdx++) {
                    if (deadline.isExpired()) {
                        deadlineExceeded = true;
                        break;
                    }
                    if (usefulFound.get() >= request.targetQuantity()) break;

                    String url = endpoints.get(endpointIdx);
                    String host;
                    try { host = java.net.URI.create(url).getHost(); } catch (Exception ignored) { host = "unknown"; }

                    if (circuitBreaker.isOpen(host)) {
                        log.info("[osm] endpoint_skipped campaignId={} tileIdx={} phase={} endpointHost={} reason=circuit_open", campaignId, tileIdx, phase, host);
                        continue;
                    }

                    long remainingMs = deadline.remainingMs();
                    if (remainingMs <= 0) {
                        deadlineExceeded = true;
                        break;
                    }

                    int effectiveTimeout = (int) Math.min(timeoutMs, remainingMs);
                    int queryLimit = Math.min(remainingUsefulInt + 5, MAX_OUT);

                    log.info("[osm] overpass_request campaignId={} tileIdx={} phase={} endpointHost={} timeoutMs={} remainingBudgetMs={} queryLimit={}",
                            campaignId, tileIdx, phase, host, effectiveTimeout, remainingMs, queryLimit);

                    try {
                        List<LeadCandidate> candidates = executeOverpassQuery(url, query, tile, geo, instagramCache, queryLimit, dedupeBySourceId, campaignId, host, deadline);
                        allEndpointsFailed = false;

                        for (LeadCandidate c : candidates) {
                            String key = "osm:" + c.getSourceId();
                            if (dedupeBySourceId.putIfAbsent(key, c) == null) {
                                allCandidates.put(key, c);
                                if (hasContactData(c)) {
                                    usefulFound.incrementAndGet();
                                }
                            }
                        }

                        log.info("[osm] overpass_response campaignId={} tileIdx={} phase={} endpointHost={} elements={} mapped={} usefulNow={} elapsedMs={}",
                                campaignId, tileIdx, phase, host, candidates.size(), allCandidates.size(), usefulFound.get(),
                                (deadline.getTotalMs() - deadline.remainingMs()));

                        circuitBreaker.recordSuccess(host);
                        lastFailure = null;

                        if (usefulFound.get() >= request.targetQuantity()) break;

                        break;

                    } catch (Exception e) {
                        lastFailure = e;
                        boolean retryable = isRetryable(e);
                        FailureDetails fd = getFailureDetails(e);
                        log.warn("[osm] overpass_failed campaignId={} tileIdx={} phase={} endpointHost={} errorType={} rootCauseType={} rootCauseMessage={} retryable={} elapsedMs={}",
                                campaignId, tileIdx, phase, host, e.getClass().getSimpleName(), fd.rootCauseType(), fd.safeMessage(), retryable,
                                (deadline.getTotalMs() - deadline.remainingMs()));

                        if (retryable) {
                            circuitBreaker.recordFailure(host);
                        }
                        if (!retryable) {
                            return LeadDiscoveryResult.infraUnavailable("OSM_OVERPASS_ERROR", "Falha não recuperável no Overpass: " + fd.safeMessage());
                        }
                    }
                }

                if (deadlineExceeded || usefulFound.get() >= request.targetQuantity()) break;
            }
        }

        int finalUseful = usefulFound.get();
        int totalCandidates = allCandidates.size();
        long elapsedMs = deadline.getTotalMs() - deadline.remainingMs();
        boolean regionExhausted = tileIdxReachedEnd(tiles, deadline, deadlineExceeded);

        log.info("[osm] discovery_finished campaignId={} totalCandidates={} usefulFound={} tilesVisited={} outcome={} elapsedMs={}",
                campaignId, totalCandidates, finalUseful, tiles.size(), determineOutcome(finalUseful, request.targetQuantity(), allEndpointsFailed, deadlineExceeded, regionExhausted), elapsedMs);

        if (allEndpointsFailed && totalCandidates == 0) {
            return LeadDiscoveryResult.infraUnavailable("OSM_OVERPASS_UNAVAILABLE", "Todos os endpoints Overpass indisponíveis");
        }
        if (deadlineExceeded) {
            return LeadDiscoveryResult.deadlineExceeded(new ArrayList<>(allCandidates.values()));
        }
        if (finalUseful == 0) {
            return LeadDiscoveryResult.empty("EMPTY", "Nenhum lead com dados de contato encontrado na cidade");
        }
        if (finalUseful >= request.targetQuantity()) {
            return LeadDiscoveryResult.complete(new ArrayList<>(allCandidates.values()));
        }
        return LeadDiscoveryResult.partial(new ArrayList<>(allCandidates.values()), regionExhausted);
    }

    private boolean tileIdxReachedEnd(List<Tile> tiles, DiscoveryDeadline deadline, boolean deadlineExceeded) {
        return deadlineExceeded || tiles.isEmpty();
    }

    private LeadDiscoveryResult.DiscoveryOutcome determineOutcome(int usefulFound, int targetQuantity, boolean allEndpointsFailed, boolean deadlineExceeded, boolean regionExhausted) {
        if (allEndpointsFailed) return LeadDiscoveryResult.DiscoveryOutcome.INFRA_UNAVAILABLE;
        if (deadlineExceeded) return LeadDiscoveryResult.DiscoveryOutcome.DEADLINE_EXCEEDED;
        if (usefulFound == 0) return LeadDiscoveryResult.DiscoveryOutcome.EMPTY;
        if (usefulFound >= targetQuantity) return LeadDiscoveryResult.DiscoveryOutcome.COMPLETE;
        return LeadDiscoveryResult.DiscoveryOutcome.PARTIAL;
    }

    private List<LeadCandidate> executeOverpassQuery(String url, String query, Tile tile, Geo geo,
                                                      Map<String, Optional<String>> instagramCache,
                                                      int queryLimit,
                                                      Map<String, LeadCandidate> existingCandidates,
                                                      Long campaignId,
                                                      String host,
                                                      DiscoveryDeadline deadline) throws RestClientException, java.io.IOException {
        long remainingMs = deadline.remainingMs();
        if (remainingMs <= 0) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "OSM_DISCOVERY_TIMEOUT", "A busca de leads excedeu o tempo máximo permitido.");
        }

        int effectiveTimeout = (int) Math.min(timeoutMs, remainingMs);

        boolean acquired = false;
        try {
            acquired = overpassSemaphore.tryAcquire(remainingMs, TimeUnit.MILLISECONDS);
            if (!acquired) {
                throw new ApiException(HttpStatus.BAD_GATEWAY, "OSM_DISCOVERY_TIMEOUT", "Timeout aguardando acesso ao Overpass.");
            }

            long remainingAfterAcquire = deadline.remainingMs();
            if (remainingAfterAcquire <= 0) {
                throw new ApiException(HttpStatus.BAD_GATEWAY, "OSM_DISCOVERY_TIMEOUT", "Deadline excedido após adquirir semáforo.");
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
                if (candidates.size() >= queryLimit) break;
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
            throw new ApiException(HttpStatus.BAD_GATEWAY, "OSM_DISCOVERY_TIMEOUT", "Interrompido aguardando acesso ao Overpass.");
        } finally {
            if (acquired) overpassSemaphore.release();
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
        return false;
    }

    private Geo geocodeCityCountry(String city, String country, DiscoveryDeadline deadline, Long campaignId) {
        String query = city + ", " + country;
        Exception lastFailure = null;

        for (int attempt = 1; attempt <= nominatimMaxAttempts; attempt++) {
            if (deadline.isExpired()) {
                throw new ApiException(HttpStatus.BAD_GATEWAY, "OSM_DISCOVERY_TIMEOUT", "Deadline excedido durante geocodificação");
            }

            long remainingMs = deadline.remainingMs();
            if (remainingMs <= 0) {
                throw new ApiException(HttpStatus.BAD_GATEWAY, "OSM_DISCOVERY_TIMEOUT", "Deadline excedido durante geocodificação");
            }

            int effectiveTimeout = (int) Math.min(nominatimTimeoutMs, remainingMs);

            try {
                String response = client(effectiveTimeout).get()
                        .uri(uriBuilder -> uriBuilder
                                .scheme("https").host("nominatim.openstreetmap.org").path("/search")
                                .queryParam("q", query)
                                .queryParam("format", "json")
                                .queryParam("limit", "5")
                                .queryParam("addressdetails", "1")
                                .build())
                        .retrieve()
                        .body(String.class);

                JsonNode arr = objectMapper.readTree(response);
                if (!arr.isArray() || arr.isEmpty()) {
                    throw new ApiException(HttpStatus.NOT_FOUND, "LOCATION_NOT_FOUND", "Cidade não encontrada: " + query);
                }

                for (JsonNode hit : arr) {
                    JsonNode address = hit.path("address");
                    String hitCity = firstPresent(address, "city", "town", "village", "municipality");
                    String hitCountry = address.path("country").asText(null);
                    if (hitCountry == null || hitCountry.isBlank()) continue;

                    if (cityMatches(hitCity, city) && countryMatches(hitCountry, country)) {
                        double lat = hit.path("lat").asDouble(Double.NaN);
                        double lon = hit.path("lon").asDouble(Double.NaN);
                        if (Double.isNaN(lat) || Double.isNaN(lon)) continue;

                        String state = address.path("state").asText(null);

                        double south = 0, north = 0, west = 0, east = 0;
                        boolean bboxValid = false;
                        JsonNode bboxNode = hit.path("boundingbox");
                        if (bboxNode.isArray() && bboxNode.size() == 4) {
                            south = bboxNode.get(0).asDouble();
                            north = bboxNode.get(1).asDouble();
                            west = bboxNode.get(2).asDouble();
                            east = bboxNode.get(3).asDouble();
                            if (south < north && west < east) {
                                bboxValid = true;
                            }
                        }

                        log.info("[osm] geocode_success campaignId={} city={} country={} lat={} lon={} bboxValid={}",
                                campaignId, hitCity, hitCountry, lat, lon, bboxValid);
                        return new Geo(lat, lon, hitCity, state, hitCountry, south, north, west, east, bboxValid);
                    }
                }

                throw new ApiException(HttpStatus.NOT_FOUND, "LOCATION_COUNTRY_MISMATCH",
                        "Cidade encontrada mas em país diferente: " + query);

            } catch (ApiException e) {
                throw e;
            } catch (RestClientException | java.io.IOException e) {
                lastFailure = e;
                boolean retryable = isRetryable(e);
                FailureDetails fd = getFailureDetails(e);
                log.warn("[osm] geocode_failed campaignId={} attempt={}/{} errorType={} rootCauseType={} rootCauseMessage={} retryable={}",
                        campaignId, attempt, nominatimMaxAttempts, e.getClass().getSimpleName(), fd.rootCauseType(), fd.safeMessage(), retryable);
                if (!retryable) throw new ApiException(HttpStatus.BAD_GATEWAY, "OSM_GEOCODE_ERROR", "Falha não recuperável: " + fd.safeMessage());
            }
        }

        throw new ApiException(HttpStatus.BAD_GATEWAY, "OSM_GEOCODE_ERROR",
                "Falha ao geocodificar cidade após retentativas: " + query);
    }

    private boolean cityMatches(String hitCity, String requestedCity) {
        if (hitCity == null || requestedCity == null) return false;
        return normalizeForCompare(hitCity).equals(normalizeForCompare(requestedCity));
    }

    private boolean countryMatches(String hitCountry, String requestedCountry) {
        if (hitCountry == null || requestedCountry == null) return false;
        return normalizeForCompare(hitCountry).equals(normalizeForCompare(requestedCountry));
    }

    private String normalizeForCompare(String s) {
        return java.text.Normalizer.normalize(s.trim().toLowerCase(), java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .replaceAll("\\s+", " ");
    }

    private List<Tile> buildCityTiles(Geo geo) {
        List<Tile> tiles = new ArrayList<>();
        if (!geo.bboxValid()) {
            double lat = geo.lat();
            double lon = geo.lon();
            double delta = 0.18;
            tiles.add(new Tile(lat - delta, lon - delta, lat + delta, lon + delta, 0));
            return tiles;
        }

        double south = geo.south();
        double north = geo.north();
        double west = geo.west();
        double east = geo.east();

        double latKmPerDeg = 111.32;
        double lonKmPerDeg = 111.32 * Math.cos(Math.toRadians((south + north) / 2.0));

        double tileLatDeg = tileTargetKm / latKmPerDeg;
        double tileLonDeg = tileTargetKm / lonKmPerDeg;

        double centerLat = (south + north) / 2.0;
        double centerLon = (west + east) / 2.0;

        int latTiles = (int) Math.ceil((north - south) / tileLatDeg);
        int lonTiles = (int) Math.ceil((east - west) / tileLonDeg);
        latTiles = Math.max(1, Math.min(latTiles, 20));
        lonTiles = Math.max(1, Math.min(lonTiles, 20));

        for (int i = 0; i < latTiles; i++) {
            for (int j = 0; j < lonTiles; j++) {
                double tileSouth = south + i * tileLatDeg;
                double tileNorth = Math.min(tileSouth + tileLatDeg, north);
                double tileWest = west + j * tileLonDeg;
                double tileEast = Math.min(tileWest + tileLonDeg, east);
                if (tileSouth >= tileNorth || tileWest >= tileEast) continue;

                double tileCenterLat = (tileSouth + tileNorth) / 2.0;
                double tileCenterLon = (tileWest + tileEast) / 2.0;
                double dist = haversineDistance(centerLat, centerLon, tileCenterLat, tileCenterLon);
                tiles.add(new Tile(tileSouth, tileWest, tileNorth, tileEast, dist));
            }
        }

        tiles.sort(Comparator.comparingDouble(Tile::distanceFromCenter));
        return tiles;
    }

    private double haversineDistance(double lat1, double lon1, double lat2, double lon2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        return 6371 * c;
    }

    String buildStructuredQuery(String niche, Tile tile, int limit) {
        String bbox = String.format(Locale.US, "%f,%f,%f,%f", tile.south(), tile.west(), tile.north(), tile.east());
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
        if (sb.length() <= "[out:json][timeout:25];(".length()) {
            return null;
        }
        sb.append(");out center tags ").append(Math.min(limit, MAX_OUT)).append(";");
        return sb.toString();
    }

    String buildNameFallbackQuery(String niche, Tile tile, int limit) {
        NicheMapper.NicheStrategy strategy = NicheMapper.resolve(niche);
        String fallbackRegex = strategy.fallbackNameRegex();
        if (fallbackRegex == null || fallbackRegex.isBlank() || fallbackRegex.equals("''")) {
            return null;
        }
        String bbox = String.format(Locale.US, "%f,%f,%f,%f", tile.south(), tile.west(), tile.north(), tile.east());
        StringBuilder sb = new StringBuilder();
        sb.append("[out:json][timeout:25];(");
        sb.append(String.format("nwr[\"name\"~\"%s\",i](%s);", fallbackRegex, bbox));
        sb.append(");out center tags ").append(Math.min(limit, MAX_OUT)).append(";");
        return sb.toString();
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
        candidate.setEmail(firstPresent(tags, "contact:email", "email"));

        candidate.setCity(firstPresent(tags, "addr:city", "addr:town", "addr:village", "addr:municipality") != null
                ? firstPresent(tags, "addr:city", "addr:town", "addr:village", "addr:municipality") : geo.city());
        candidate.setState(asTextOrNull(tags, "addr:state") != null ? asTextOrNull(tags, "addr:state") : geo.state());
        candidate.setCountry(asTextOrNull(tags, "addr:country") != null ? asTextOrNull(tags, "addr:country") : geo.country());
        candidate.setAddress(buildAddress(tags, geo));

        String rawIg = firstPresent(tags, "contact:instagram", "instagram");
        String username = normalizer.normalizeInstagram(rawIg);

        if (username != null && !username.isBlank()) {
            candidate.setInstagramUsername(username);
            candidate.setInstagramUrl("https://instagram.com/" + username);
            candidate.setInstagramStatus("FOUND");
        } else {
            String website = candidate.getWebsite();
            if (website != null && !website.isBlank() && ssrfGuard.isSafe(website)) {
                String normalizedWebsite = website.trim().toLowerCase();
                
                Optional<String> detected = instagramCache.computeIfAbsent(normalizedWebsite,
                        key -> Optional.ofNullable(instagramDetector.detectFromWebsite(key)));
                
                WebsiteContactEnricher.WebsiteContactData enriched = enrichmentCache.computeIfAbsent(normalizedWebsite,
                        key -> websiteContactEnricher.enrich(key));

                if (detected.isPresent()) {
                    candidate.setInstagramUsername(detected.get());
                    candidate.setInstagramUrl("https://instagram.com/" + detected.get());
                    candidate.setInstagramStatus("FOUND");
                } else if (enriched != null && enriched.instagramUsername() != null) {
                    candidate.setInstagramUsername(enriched.instagramUsername());
                    candidate.setInstagramUrl("https://instagram.com/" + enriched.instagramUsername());
                    candidate.setInstagramStatus("FOUND");
                } else {
                    candidate.setInstagramStatus("NOT_FOUND");
                }

                if (enriched != null) {
                    if (enriched.phone() != null && (candidate.getPhone() == null || candidate.getPhone().isBlank())) {
                        candidate.setPhone(enriched.phone());
                    }
                    if (enriched.email() != null && (candidate.getEmail() == null || candidate.getEmail().isBlank())) {
                        candidate.setEmail(enriched.email());
                    }
                }
            } else {
                candidate.setInstagramStatus("NOT_FOUND");
            }
        }
        return candidate;
    }

    private boolean hasContactData(LeadCandidate c) {
        return (c.getPhone() != null && !c.getPhone().isBlank())
                || (c.getEmail() != null && !c.getEmail().isBlank())
                || (c.getInstagramUsername() != null && !c.getInstagramUsername().isBlank())
                || (c.getWebsite() != null && !c.getWebsite().isBlank());
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
        if (city == null) city = geo.city();
        if (state == null) state = geo.state();

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

    private static String escapeTag(String s) {
        return s.replace("\\", "").replace("\"", "");
    }

    record Geo(double lat, double lon, String city, String state, String country, double south, double north, double west, double east, boolean bboxValid) {}

    record Tile(double south, double west, double north, double east, double distanceFromCenter) {
        String bbox() {
            return String.format(Locale.US, "%f,%f,%f,%f", south, west, north, east);
        }
    }

    static class DiscoveryDeadline {
        private final long deadlineNanos;
        private final long totalMs;

        DiscoveryDeadline(long totalMs) {
            this.totalMs = totalMs;
            this.deadlineNanos = System.nanoTime() + totalMs * 1_000_000L;
        }

        long remainingMs() {
            return Math.max(0, (deadlineNanos - System.nanoTime()) / 1_000_000L);
        }

        boolean isExpired() {
            return System.nanoTime() >= deadlineNanos;
        }

        long getTotalMs() {
            return totalMs;
        }
    }

    static class OverpassCircuitBreaker {
        private static final class EndpointState {
            volatile State state = State.CLOSED;
            volatile long openSince = 0;
            volatile int failureCount = 0;
            volatile int halfOpenSuccesses = 0;
            volatile AtomicReference<Thread> halfOpenThread = new AtomicReference<>();

            enum State { CLOSED, OPEN, HALF_OPEN }
        }

        private final Map<String, EndpointState> states = new ConcurrentHashMap<>();
        private final long openSeconds;

        OverpassCircuitBreaker() {
            this.openSeconds = 180;
        }

        OverpassCircuitBreaker(long openSeconds) {
            this.openSeconds = openSeconds;
        }

        boolean isOpen(String host) {
            EndpointState es = states.computeIfAbsent(host, k -> new EndpointState());
            synchronized (es) {
                if (es.state == EndpointState.State.OPEN) {
                    if (System.currentTimeMillis() - es.openSince >= openSeconds * 1000L) {
                        es.state = EndpointState.State.HALF_OPEN;
                        es.halfOpenSuccesses = 0;
                        es.halfOpenThread.set(null);
                        return false;
                    }
                    return true;
                }
                return false;
            }
        }

        boolean tryEnterHalfOpen(String host) {
            EndpointState es = states.computeIfAbsent(host, k -> new EndpointState());
            synchronized (es) {
                if (es.state == EndpointState.State.HALF_OPEN && es.halfOpenThread.get() == null) {
                    es.halfOpenThread.set(Thread.currentThread());
                    return true;
                }
                return false;
            }
        }

        void recordSuccess(String host) {
            EndpointState es = states.computeIfAbsent(host, k -> new EndpointState());
            synchronized (es) {
                if (es.state == EndpointState.State.HALF_OPEN) {
                    if (es.halfOpenThread.get() == Thread.currentThread()) {
                        es.halfOpenSuccesses++;
                        if (es.halfOpenSuccesses >= 1) {
                            es.state = EndpointState.State.CLOSED;
                            es.failureCount = 0;
                            es.halfOpenThread.set(null);
                        }
                    }
                } else if (es.state == EndpointState.State.CLOSED) {
                    es.failureCount = 0;
                }
            }
        }

        void recordFailure(String host) {
            EndpointState es = states.computeIfAbsent(host, k -> new EndpointState());
            synchronized (es) {
                if (es.state == EndpointState.State.HALF_OPEN) {
                    if (es.halfOpenThread.get() == Thread.currentThread()) {
                        es.state = EndpointState.State.OPEN;
                        es.openSince = System.currentTimeMillis();
                        es.failureCount = 0;
                        es.halfOpenThread.set(null);
                    }
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
}