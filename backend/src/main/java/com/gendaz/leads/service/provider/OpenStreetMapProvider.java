package com.gendaz.leads.service.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gendaz.leads.domain.LeadCandidate;
import com.gendaz.leads.exception.ApiException;
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
    private static final int MAX_OUT = 200;

    @Value("${app.discovery.osm.enabled:true}")
    private boolean enabled;

    @Value("${app.discovery.osm.timeout-ms:10000}")
    private int timeoutMs;

    @Value("${app.discovery.osm.nominatim-timeout-ms:8000}")
    private int nominatimTimeoutMs;

    @Value("${app.discovery.osm.nominatim-max-attempts:2}")
    private int nominatimMaxAttempts;

    @Value("${app.discovery.osm.nominatim-cache-seconds:3600}")
    private int nominatimCacheSeconds;

    @Value("${app.discovery.osm.nominatim-min-interval-ms:1000}")
    private long nominatimMinIntervalMs;

    @Value("${app.discovery.osm.overpass-endpoints:https://overpass.private.coffee/api/interpreter,https://maps.mail.ru/osm/tools/overpass/api/interpreter,https://overpass-api.de/api/interpreter}")
    private String overpassEndpoints;

    @Value("${app.discovery.osm.overpass-url:}")
    private String overpassUrl;

    @Value("${app.discovery.osm.fallback-url:}")
    private String fallbackUrl;

    @Value("${app.discovery.osm.max-concurrency:1}")
    private int maxConcurrency;

    @Value("${app.discovery.osm.circuit-open-seconds:30}")
    private long circuitOpenSeconds;

    @Value("${app.discovery.osm.adaptive-max-depth:6}")
    private int adaptiveMaxDepth;

    @Value("${app.discovery.osm.adaptive-min-edge-km:2.0}")
    private double adaptiveMinEdgeKm;

    private final RestClient.Builder builder;
    private final ObjectMapper objectMapper;
    private final Normalizer normalizer;
    private final SsrfGuard ssrfGuard;
    private OverpassCircuitBreaker circuitBreaker;
    private Semaphore overpassSemaphore;

    private final Map<String, TimedValue<ResolvedCountry>> countryCache = new ConcurrentHashMap<>();
    private final Map<String, TimedValue<GeoScope>> geoCache = new ConcurrentHashMap<>();

    private final Object nominatimRateLock = new Object();
    private long lastNominatimRequestAtMs = 0L;

    record TimedValue<T>(T value, long expiresAtMs) {
        boolean valid() {
            return System.currentTimeMillis() < expiresAtMs;
        }
    }

    record ResolvedCountry(String countryCode, String countryName) {}

    record FailureDetails(String rootCauseType, String safeMessage) {}

    public OpenStreetMapProvider(RestClient.Builder builder, ObjectMapper objectMapper,
                                 Normalizer normalizer, SsrfGuard ssrfGuard) {
        this.builder = builder;
        this.objectMapper = objectMapper;
        this.normalizer = normalizer;
        this.ssrfGuard = ssrfGuard;
        this.circuitBreaker = new OverpassCircuitBreaker(180);
    }

    @PostConstruct
    void init() {
        this.overpassSemaphore = new Semaphore(Math.max(1, maxConcurrency), true);
        this.circuitBreaker = new OverpassCircuitBreaker(circuitOpenSeconds);
        List<String> endpoints = buildEndpointList();
        log.info("[osm] config enabled={} timeoutMs={} nominatimTimeoutMs={} nominatimMaxAttempts={} nominatimCacheSeconds={} nominatimMinIntervalMs={} endpoints={} maxConcurrency={} circuitOpenSeconds={}",
                enabled, timeoutMs, nominatimTimeoutMs, nominatimMaxAttempts, nominatimCacheSeconds, nominatimMinIntervalMs, endpoints.size(), maxConcurrency, circuitOpenSeconds);
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

    private List<String> orderedEndpoints(String preferredHost) {
        List<String> endpoints = buildEndpointList();

        if (preferredHost == null || preferredHost.isBlank()) {
            return endpoints;
        }

        endpoints.sort((a, b) -> {
            String ha = hostOf(a);
            String hb = hostOf(b);

            if (preferredHost.equalsIgnoreCase(ha)) return -1;
            if (preferredHost.equalsIgnoreCase(hb)) return 1;
            return 0;
        });

        return endpoints;
    }

    private String hostOf(String url) {
        try {
            return java.net.URI.create(url).getHost();
        } catch (Exception ignored) {
            return "unknown";
        }
    }

    @Override
    public String getName() {
        return "openstreetmap";
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    public GeoScope resolveScope(
            LeadDiscoveryRequest request,
            DiscoveryBudget budget
    ) {
        String cityKey = request.city().trim().toLowerCase();
        String countryKey = request.country().trim().toLowerCase();
        String cacheKey = "geo:" + cityKey + "|" + countryKey;

        TimedValue<GeoScope> cached = geoCache.get(cacheKey);
        if (cached != null && cached.valid()) {
            log.info("[osm] geocode_cache_hit campaignId={} city={} country={}", request.campaignId(), request.city(), request.country());
            return cached.value();
        }

        ResolvedCountry resolved = resolveRequestedCountryCode(request.country(), budget, request.campaignId());
        String requestedCountryCode = resolved.countryCode();

        String response;
        Exception lastFailure = null;

        for (int attempt = 1; attempt <= nominatimMaxAttempts; attempt++) {
            if (budget.expired()) {
                throw new ApiException(HttpStatus.BAD_GATEWAY, "OSM_DISCOVERY_TIMEOUT", "Deadline excedido durante geocodificação");
            }

            long remainingMs = budget.remainingMs();
            if (remainingMs <= 0) {
                throw new ApiException(HttpStatus.BAD_GATEWAY, "OSM_DISCOVERY_TIMEOUT", "Deadline excedido durante geocodificação");
            }

            awaitNominatimSlot(budget);

            int effectiveTimeout = (int) Math.min(nominatimTimeoutMs, remainingMs);

            try {
                response = client(effectiveTimeout).get()
                        .uri(uriBuilder -> uriBuilder
                                .scheme("https")
                                .host("nominatim.openstreetmap.org")
                                .path("/search")
                                .queryParam("q", request.city())
                                .queryParam("countrycodes", requestedCountryCode)
                                .queryParam("format", "json")
                                .queryParam("limit", "5")
                                .queryParam("addressdetails", "1")
                                .build())
                        .retrieve()
                        .body(String.class);

                JsonNode arr = objectMapper.readTree(response);
                if (!arr.isArray() || arr.isEmpty()) {
                    throw new ApiException(HttpStatus.NOT_FOUND, "LOCATION_NOT_FOUND", "Cidade não encontrada: " + request.city() + " (" + requestedCountryCode + ")");
                }

                for (JsonNode hit : arr) {
                    JsonNode address = hit.path("address");
                    String hitCity = firstPresent(address, "city", "town", "village", "municipality");
                    String hitCountryCode = address.path("country_code").asText(null);
                    if (hitCountryCode == null || hitCountryCode.isBlank()) continue;

                    if (cityMatches(hitCity, request.city()) && requestedCountryCode.equalsIgnoreCase(hitCountryCode)) {
                        double lat = hit.path("lat").asDouble(Double.NaN);
                        double lon = hit.path("lon").asDouble(Double.NaN);
                        if (Double.isNaN(lat) || Double.isNaN(lon)) continue;

                        String state = address.path("state").asText(null);
                        String hitCountry = address.path("country").asText(null);

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

                        log.info("[osm] geocode_success campaignId={} requestedCity={} requestedCountry={} resolvedCity={} resolvedCountry={} countryCode={} lat={} lon={} bboxValid={}",
                                request.campaignId(), request.city(), request.country(), hitCity, hitCountry, hitCountryCode, lat, lon, bboxValid);

                        GeoScope scope = new GeoScope(lat, lon, hitCity, state, hitCountry, hitCountryCode, south, west, north, east, bboxValid);
                        geoCache.put(cacheKey, new TimedValue<>(scope, System.currentTimeMillis() + nominatimCacheSeconds * 1000L));
                        return scope;
                    }
                }

                String hitCountry = firstPresent(arr.get(0).path("address"), "country");
                String hitCountryCode = arr.get(0).path("address").path("country_code").asText(null);
                log.warn("[osm] geocode_country_mismatch campaignId={} requestedCity={} requestedCountry={} requestedCountryCode={} hitCountry={} hitCountryCode={}",
                        request.campaignId(), request.city(), request.country(), requestedCountryCode, hitCountry, hitCountryCode);
                throw new ApiException(HttpStatus.NOT_FOUND, "LOCATION_COUNTRY_MISMATCH",
                        "Cidade encontrada mas em país diferente: " + request.city() + " (" + request.country() + ")");

            } catch (ApiException e) {
                throw e;
            } catch (RestClientException | java.io.IOException e) {
                lastFailure = e;
                boolean retryable = isRetryable(e);
                FailureDetails fd = getFailureDetails(e);
                log.warn("[osm] geocode_failed campaignId={} attempt={}/{} errorType={} rootCauseType={} rootCauseMessage={} retryable={}",
                        request.campaignId(), attempt, nominatimMaxAttempts, e.getClass().getSimpleName(), fd.rootCauseType(), fd.safeMessage(), retryable);
                if (!retryable) throw new ApiException(HttpStatus.BAD_GATEWAY, "OSM_GEOCODE_ERROR", "Falha não recuperável: " + fd.safeMessage());
            }
        }

        throw new ApiException(HttpStatus.BAD_GATEWAY, "OSM_GEOCODE_ERROR",
                "Falha ao geocodificar cidade após retentativas: " + request.city() + " (" + request.country() + ")");
    }

    public AreaQueryResult queryRegion(
            GeoScope scope,
            String niche,
            SearchRegion region,
            AreaQueryPhase phase,
            int rawLimit,
            String preferredEndpointHost,
            DiscoveryBudget budget,
            Long campaignId
    ) {
        List<String> endpoints = orderedEndpoints(preferredEndpointHost);
        long startNs = System.nanoTime();
        boolean hadTimeoutOr504 = false;
        Exception lastFailure = null;

        for (int endpointIdx = 0; endpointIdx < endpoints.size(); endpointIdx++) {
            String url = endpoints.get(endpointIdx);
            String host = hostOf(url);

            if (!circuitBreaker.tryAcquire(host)) {
                log.info("[osm] endpoint_skipped campaignId={} phase={} endpointHost={} reason=circuit_open", campaignId, phase, host);
                continue;
            }

            long waitBudgetMs = budget.remainingMs();
            if (waitBudgetMs <= 0) {
                circuitBreaker.recordFailure(host);
                return AreaQueryResult.infraUnavailable("OSM_DISCOVERY_TIMEOUT", "Budget insuficiente aguardando semaphore", (System.nanoTime() - startNs) / 1_000_000L);
            }

            boolean acquired = false;
            try {
                acquired = overpassSemaphore.tryAcquire(waitBudgetMs, TimeUnit.MILLISECONDS);
                if (!acquired) {
                    circuitBreaker.recordFailure(host);
                    return AreaQueryResult.infraUnavailable("OSM_DISCOVERY_TIMEOUT", "Timeout aguardando acesso ao Overpass", (System.nanoTime() - startNs) / 1_000_000L);
                }

                int effectiveTimeoutMs = budget.clampTimeout(timeoutMs);
                if (effectiveTimeoutMs <= 0) {
                    circuitBreaker.recordFailure(host);
                    return AreaQueryResult.infraUnavailable("OSM_DISCOVERY_TIMEOUT", "Budget insuficiente para timeout efetivo", (System.nanoTime() - startNs) / 1_000_000L);
                }

                int overpassTimeoutSeconds = Math.max(1, (int) Math.ceil(effectiveTimeoutMs / 1000.0));

                String query;
                if (phase == AreaQueryPhase.STRUCTURED) {
                    query = buildStructuredQuery(niche, region, rawLimit, overpassTimeoutSeconds);
                } else {
                    query = buildNameFallbackQuery(niche, region, rawLimit, overpassTimeoutSeconds);
                }

                if (query == null) {
                    circuitBreaker.recordSuccess(host);
                    return AreaQueryResult.success(List.of(), false, host, (System.nanoTime() - startNs) / 1_000_000L);
                }

                log.info("[osm] area_query_start campaignId={} depth={} phase={} bbox={} rawLimit={} preferredEndpointHost={} remainingBudgetMs={}",
                        campaignId, region.depth(), phase, region.bbox(), rawLimit, preferredEndpointHost, budget.remainingMs());

                String response = client(effectiveTimeoutMs).post()
                        .uri(url)
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .body("data=" + java.net.URLEncoder.encode(query, java.nio.charset.StandardCharsets.UTF_8))
                        .retrieve()
                        .body(String.class);

                long elapsedMs = (System.nanoTime() - startNs) / 1_000_000L;

                JsonNode root = objectMapper.readTree(response);
                JsonNode elements = root.path("elements");
                if (elements.isMissingNode() || !elements.isArray() || elements.isEmpty()) {
                    log.info("[osm] area_query_success campaignId={} depth={} phase={} endpointHost={} elements=0 mapped=0 saturated=false elapsedMs={}",
                            campaignId, region.depth(), phase, host, elapsedMs);
                    circuitBreaker.recordSuccess(host);
                    return AreaQueryResult.success(List.of(), false, host, elapsedMs);
                }

                List<LeadCandidate> candidates = new ArrayList<>();
                for (JsonNode el : elements) {
                    if (candidates.size() >= rawLimit) break;
                    LeadCandidate candidate = mapElement(el, scope);
                    if (candidate != null) {
                        candidates.add(candidate);
                    }
                }

                boolean saturated = elements.size() >= rawLimit;

                log.info("[osm] area_query_success campaignId={} depth={} phase={} endpointHost={} elements={} mapped={} saturated={} elapsedMs={}",
                        campaignId, region.depth(), phase, host, elements.size(), candidates.size(), saturated, elapsedMs);

                circuitBreaker.recordSuccess(host);
                return AreaQueryResult.success(candidates, saturated, host, elapsedMs);

            } catch (Exception e) {
                lastFailure = e;
                boolean retryable = isRetryable(e);
                FailureDetails fd = getFailureDetails(e);
                long elapsedMs = (System.nanoTime() - startNs) / 1_000_000L;
                log.warn("[osm] overpass_failed campaignId={} depth={} phase={} endpointHost={} errorType={} rootCauseType={} rootCauseMessage={} retryable={} elapsedMs={}",
                        campaignId, region.depth(), phase, host, e.getClass().getSimpleName(), fd.rootCauseType(), fd.safeMessage(), retryable, elapsedMs);

                if (retryable) {
                    if (e instanceof HttpStatusCodeException http) {
                        int v = http.getStatusCode().value();
                        if (v == 504 || e instanceof SocketTimeoutException || e instanceof ResourceAccessException) {
                            hadTimeoutOr504 = true;
                        }
                    } else {
                        Throwable root = e;
                        while (root != null) {
                            if (root instanceof SocketTimeoutException) {
                                hadTimeoutOr504 = true;
                                break;
                            }
                            root = root.getCause();
                        }
                    }
                    circuitBreaker.recordFailure(host);
                } else {
                    return AreaQueryResult.queryError("OSM_OVERPASS_QUERY_ERROR", "Falha não recuperável no Overpass: " + fd.safeMessage(), elapsedMs);
                }
            } finally {
                if (acquired) {
                    overpassSemaphore.release();
                }
            }
        }

        long elapsedMs = (System.nanoTime() - startNs) / 1_000_000L;

        if (hadTimeoutOr504 && region.canSplit(adaptiveMaxDepth, adaptiveMinEdgeKm)) {
            return AreaQueryResult.splitRequired("OSM_SPLIT_REQUIRED", "Timeout/504 em todos os endpoints, subdividindo região", elapsedMs);
        }

        return AreaQueryResult.infraUnavailable("OSM_ALL_ENDPOINTS_FAILED", "Todos os endpoints Overpass indisponíveis", elapsedMs);
    }

    private void awaitNominatimSlot(DiscoveryBudget budget) {
        synchronized (nominatimRateLock) {
            long now = System.currentTimeMillis();
            long waitMs = nominatimMinIntervalMs - (now - lastNominatimRequestAtMs);

            if (waitMs > 0) {
                if (budget.remainingMs() <= waitMs) {
                    throw new ApiException(
                            HttpStatus.BAD_GATEWAY,
                            "OSM_DISCOVERY_TIMEOUT",
                            "Budget insuficiente aguardando Nominatim."
                    );
                }

                try {
                    Thread.sleep(waitMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new ApiException(
                            HttpStatus.BAD_GATEWAY,
                            "OSM_DISCOVERY_INTERRUPTED",
                            "Busca interrompida aguardando Nominatim."
                    );
                }
            }

            lastNominatimRequestAtMs = System.currentTimeMillis();
        }
    }

    private ResolvedCountry resolveRequestedCountryCode(String requestedCountry, DiscoveryBudget budget, Long campaignId) {
        String cacheKey = "country:" + requestedCountry.trim().toLowerCase();
        TimedValue<ResolvedCountry> cached = countryCache.get(cacheKey);
        if (cached != null && cached.valid()) {
            log.info("[osm] country_cache_hit campaignId={} requestedCountry={} resolvedCountryCode={}", campaignId, requestedCountry, cached.value().countryCode());
            return cached.value();
        }

        Exception lastFailure = null;
        for (int attempt = 1; attempt <= nominatimMaxAttempts; attempt++) {
            if (budget.expired()) {
                throw new ApiException(HttpStatus.BAD_GATEWAY, "OSM_DISCOVERY_TIMEOUT", "Deadline excedido durante resolução de país");
            }

            long remainingMs = budget.remainingMs();
            if (remainingMs <= 0) {
                throw new ApiException(HttpStatus.BAD_GATEWAY, "OSM_DISCOVERY_TIMEOUT", "Deadline excedido durante resolução de país");
            }

            awaitNominatimSlot(budget);

            int effectiveTimeout = (int) Math.min(nominatimTimeoutMs, remainingMs);

            try {
                String response = client(effectiveTimeout).get()
                        .uri(uriBuilder -> uriBuilder
                                .scheme("https").host("nominatim.openstreetmap.org").path("/search")
                                .queryParam("q", requestedCountry)
                                .queryParam("format", "json")
                                .queryParam("limit", "5")
                                .queryParam("addressdetails", "1")
                                .queryParam("featuretype", "country")
                                .build())
                        .retrieve()
                        .body(String.class);

                JsonNode arr = objectMapper.readTree(response);
                if (!arr.isArray() || arr.isEmpty()) {
                    throw new ApiException(HttpStatus.NOT_FOUND, "LOCATION_NOT_FOUND", "País não encontrado: " + requestedCountry);
                }

                for (JsonNode hit : arr) {
                    JsonNode address = hit.path("address");
                    String countryCode = address.path("country_code").asText(null);
                    String countryName = address.path("country").asText(null);
                    if (countryCode != null && !countryCode.isBlank()) {
                        String normalizedCode = countryCode.trim().toLowerCase();
                        String normalizedName = countryName != null ? countryName.trim() : requestedCountry;
                        log.info("[osm] country_resolved campaignId={} requestedCountry={} resolvedCountryCode={} resolvedCountryName={}",
                                campaignId, requestedCountry, normalizedCode, normalizedName);
                        ResolvedCountry resolved = new ResolvedCountry(normalizedCode, normalizedName);
                        countryCache.put(cacheKey, new TimedValue<>(resolved, System.currentTimeMillis() + nominatimCacheSeconds * 1000L));
                        return resolved;
                    }
                }

                throw new ApiException(HttpStatus.NOT_FOUND, "LOCATION_NOT_FOUND", "País não encontrado: " + requestedCountry);

            } catch (ApiException e) {
                throw e;
            } catch (RestClientException | java.io.IOException e) {
                lastFailure = e;
                boolean retryable = isRetryable(e);
                FailureDetails fd = getFailureDetails(e);
                log.warn("[osm] country_resolve_failed campaignId={} attempt={}/{} errorType={} rootCauseType={} rootCauseMessage={} retryable={}",
                        campaignId, attempt, nominatimMaxAttempts, e.getClass().getSimpleName(), fd.rootCauseType(), fd.safeMessage(), retryable);
                if (!retryable) throw new ApiException(HttpStatus.BAD_GATEWAY, "OSM_GEOCODE_ERROR", "Falha não recuperável ao resolver país: " + fd.safeMessage());
            }
        }

        throw new ApiException(HttpStatus.BAD_GATEWAY, "OSM_GEOCODE_ERROR",
                "Falha ao resolver país após retentativas: " + requestedCountry);
    }

    String buildStructuredQuery(String niche, SearchRegion region, int limit, int overpassTimeoutSeconds) {
        String bbox = region.bbox();
        NicheMapper.NicheStrategy strategy = NicheMapper.resolve(niche);
        StringBuilder sb = new StringBuilder();
        sb.append("[out:json][timeout:")
                .append(Math.max(1, overpassTimeoutSeconds))
                .append("];(");
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
        if (sb.length() <= ("[out:json][timeout:" + Math.max(1, overpassTimeoutSeconds) + "];(").length()) {
            return null;
        }
        sb.append(");out center tags ").append(Math.min(limit, MAX_OUT)).append(";");
        return sb.toString();
    }

    String buildNameFallbackQuery(String niche, SearchRegion region, int limit, int overpassTimeoutSeconds) {
        NicheMapper.NicheStrategy strategy = NicheMapper.resolve(niche);
        String fallbackRegex = strategy.fallbackNameRegex();
        if (fallbackRegex == null || fallbackRegex.isBlank() || fallbackRegex.equals("''")) {
            return null;
        }
        String bbox = region.bbox();
        StringBuilder sb = new StringBuilder();
        sb.append("[out:json][timeout:")
                .append(Math.max(1, overpassTimeoutSeconds))
                .append("];(");
        sb.append(String.format("nwr[\"name\"~\"%s\",i](%s);", fallbackRegex, bbox));
        sb.append(");out center tags ").append(Math.min(limit, MAX_OUT)).append(";");
        return sb.toString();
    }

    LeadCandidate mapElement(JsonNode el, GeoScope scope) {
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
                ? firstPresent(tags, "addr:city", "addr:town", "addr:village", "addr:municipality") : scope.city());
        candidate.setState(asTextOrNull(tags, "addr:state") != null ? asTextOrNull(tags, "addr:state") : scope.state());
        candidate.setCountry(asTextOrNull(tags, "addr:country") != null ? asTextOrNull(tags, "addr:country") : scope.country());
        candidate.setAddress(buildAddress(tags, scope));
        candidate.setInstagramStatus("NOT_FOUND");

        String instagramRaw =
                firstPresent(
                        tags,
                        "contact:instagram",
                        "instagram"
                );

        if (instagramRaw != null && !instagramRaw.isBlank()) {

            String normalizedInstagram =
                    normalizer.normalizeInstagram(
                            instagramRaw
                    );

            if (normalizedInstagram != null
                    && !normalizedInstagram.isBlank()) {

                candidate.setInstagramUsername(
                        normalizedInstagram
                );

                candidate.setInstagramUrl(
                        "https://instagram.com/"
                                + normalizedInstagram
                );

                candidate.setInstagramStatus("FOUND");
            }
        }

        return candidate;
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

    private boolean cityMatches(String hitCity, String requestedCity) {
        if (hitCity == null || requestedCity == null) return false;
        return normalizeForCompare(hitCity).equals(normalizeForCompare(requestedCity));
    }

    private String normalizeForCompare(String s) {
        return java.text.Normalizer.normalize(s.trim().toLowerCase(), java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .replaceAll("\\s+", " ");
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

    static String buildCategory(JsonNode tags) {
        if (asTextOrNull(tags, "beauty") != null) return "beauty:" + asTextOrNull(tags, "beauty");
        if (asTextOrNull(tags, "shop") != null) return "shop:" + asTextOrNull(tags, "shop");
        if (asTextOrNull(tags, "amenity") != null) return "amenity:" + asTextOrNull(tags, "amenity");
        return null;
    }

    static String buildAddress(JsonNode tags, GeoScope scope) {
        String street = asTextOrNull(tags, "addr:street");
        String number = asTextOrNull(tags, "addr:housenumber");
        String suburb = asTextOrNull(tags, "addr:suburb", "addr:neighbourhood", "addr:district");
        String city = asTextOrNull(tags, "addr:city", "addr:town", "addr:village", "addr:municipality");
        String state = asTextOrNull(tags, "addr:state");
        if (city == null) city = scope.city();
        if (state == null) state = scope.state();

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

    static class OverpassCircuitBreaker {
        private static final class EndpointState {
            volatile State state = State.CLOSED;
            volatile long openSince = 0L;
            volatile boolean halfOpenInFlight = false;

            enum State {
                CLOSED,
                OPEN,
                HALF_OPEN
            }
        }

        private final Map<String, EndpointState> states = new ConcurrentHashMap<>();
        private final long openSeconds;

        OverpassCircuitBreaker(long openSeconds) {
            this.openSeconds = openSeconds;
        }

        boolean tryAcquire(String host) {
            EndpointState es = states.computeIfAbsent(host, k -> new EndpointState());

            synchronized (es) {
                if (es.state == EndpointState.State.CLOSED) {
                    return true;
                }

                if (es.state == EndpointState.State.OPEN) {
                    long elapsed = System.currentTimeMillis() - es.openSince;

                    if (elapsed < openSeconds * 1000L) {
                        return false;
                    }

                    es.state = EndpointState.State.HALF_OPEN;
                    es.halfOpenInFlight = false;
                }

                if (es.state == EndpointState.State.HALF_OPEN) {
                    if (es.halfOpenInFlight) {
                        return false;
                    }

                    es.halfOpenInFlight = true;
                    return true;
                }

                return false;
            }
        }

        void recordSuccess(String host) {
            EndpointState es = states.computeIfAbsent(host, k -> new EndpointState());

            synchronized (es) {
                es.state = EndpointState.State.CLOSED;
                es.openSince = 0L;
                es.halfOpenInFlight = false;
            }
        }

        void recordFailure(String host) {
            EndpointState es = states.computeIfAbsent(host, k -> new EndpointState());

            synchronized (es) {
                es.state = EndpointState.State.OPEN;
                es.openSince = System.currentTimeMillis();
                es.halfOpenInFlight = false;
            }
        }
    }
}