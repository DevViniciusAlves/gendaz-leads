package com.gendaz.leads.service.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gendaz.leads.domain.LeadCandidate;
import com.gendaz.leads.exception.ApiException;
import com.gendaz.leads.util.CountryCodeResolver;
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

    @Value("${app.discovery.osm.nominatim-base-url:https://nominatim.openstreetmap.org}")
    private String nominatimBaseUrl;

    @Value("${app.discovery.osm.nominatim-429-backoff-ms:3000}")
    private long nominatim429BackoffMs;

    @Value("${app.discovery.osm.max-concurrency:1}")
    private int maxConcurrency;

    @Value("${app.discovery.osm.circuit-open-seconds:30}")
    private long circuitOpenSeconds;

    @Value("${app.discovery.osm.adaptive-max-depth:12}")
    private int adaptiveMaxDepth;

    @Value("${app.discovery.osm.adaptive-min-edge-km:1.0}")
    private double adaptiveMinEdgeKm;

    @Value("${app.discovery.osm.query-max-edge-km:12.0}")
    private double queryMaxEdgeKm;

    @Value("${app.discovery.osm.failure-split-threshold-km:6.0}")
    private double failureSplitThresholdKm;

    @Value("${app.discovery.osm.rate-limit-cooldown-seconds:60}")
    private long rateLimitCooldownSeconds;

    @Value("${app.discovery.osm.connection-refused-cooldown-seconds:30}")
    private long connectionRefusedCooldownSeconds;

    @Value("${app.discovery.osm.timeout-cooldown-seconds:15}")
    private long timeoutCooldownSeconds;

    private final RestClient.Builder builder;
    private final ObjectMapper objectMapper;
    private final Normalizer normalizer;
    private final SsrfGuard ssrfGuard;
    private OverpassCircuitBreaker circuitBreaker;
    private Semaphore overpassSemaphore;

    private final Map<String, TimedValue<GeoScope>> geoCache = new ConcurrentHashMap<>();

    private final Object nominatimRateLock = new Object();
    private long lastNominatimRequestAtMs = 0L;

    private final AtomicInteger overpassRoundRobinCursor = new AtomicInteger(0);

    record TimedValue<T>(T value, long expiresAtMs) {
        boolean valid() {
            return System.currentTimeMillis() < expiresAtMs;
        }
    }

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
        log.info("[osm] config enabled={} timeoutMs={} nominatimTimeoutMs={} nominatimMaxAttempts={} nominatimCacheSeconds={} nominatimMinIntervalMs={} nominatimBaseUrl={} nominatim429BackoffMs={} endpoints={} maxConcurrency={} circuitOpenSeconds={} queryMaxEdgeKm={} failureSplitThresholdKm={} adaptiveMaxDepth={} adaptiveMinEdgeKm={} rateLimitCooldownSeconds={} connectionRefusedCooldownSeconds={} timeoutCooldownSeconds={}",
                enabled, timeoutMs, nominatimTimeoutMs, nominatimMaxAttempts, nominatimCacheSeconds, nominatimMinIntervalMs, nominatimBaseUrl, nominatim429BackoffMs, endpoints.size(), maxConcurrency, circuitOpenSeconds, queryMaxEdgeKm, failureSplitThresholdKm, adaptiveMaxDepth, adaptiveMinEdgeKm, rateLimitCooldownSeconds, connectionRefusedCooldownSeconds, timeoutCooldownSeconds);
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

    private List<String> orderedEndpointsRoundRobin() {

        List<String> endpoints = buildEndpointList();

        if (endpoints.size() <= 1) {
            return endpoints;
        }

        int start = Math.floorMod(overpassRoundRobinCursor.getAndIncrement(), endpoints.size());

        List<String> ordered = new ArrayList<>(endpoints.size());

        for (int i = 0; i < endpoints.size(); i++) {
            ordered.add(endpoints.get((start + i) % endpoints.size()));
        }

        return ordered;
    }

    private String hostOf(String url) {
        try {
            return java.net.URI.create(url).getHost();
        } catch (Exception ignored) {
            return "unknown";
        }
    }

    private Optional<Long> adminAreaId(GeoScope scope) {
        if (!scope.hasAdminAreaCandidate()) {
            return Optional.empty();
        }
        if (scope.osmId() > 0L) {
            // Overpass area id for relations: 3600000000 + osmId
            return Optional.of(3600000000L + scope.osmId());
        }
        return Optional.empty();
    }

    private String buildAdminAreaPreamble(GeoScope scope) {
        if (!scope.hasAdminAreaCandidate()) {
            return "";
        }

        Optional<Long> areaId = adminAreaId(scope);
        if (areaId.isPresent()) {
            return "area(" + areaId.get() + ")->.searchArea;.searchArea out ids;";
        }

        // Fallback to map_to_area for ways/nodes if needed
        return "rel(id:"
                + scope.osmId()
                + ")->.adminBoundary;"
                + ".adminBoundary map_to_area->.searchArea;"
                + ".searchArea out ids;";
    }

    private String buildLocationFilter(GeographicStrategy strategy, SearchRegion region) {
        if (strategy == GeographicStrategy.ADMIN_AREA) {
            return "(area.searchArea)("
                    + region.bbox()
                    + ")";
        }

        return "("
                + region.bbox()
                + ")";
    }

    private static final List<String> CONTACT_KEYS = List.of(
            "phone",
            "contact:phone",
            "mobile",
            "contact:mobile",
            "website",
            "contact:website",
            "url",
            "email",
            "contact:email",
            "instagram",
            "contact:instagram"
    );

    private static final String CONTACT_KEYS_REGEX = "^(" +
            "phone|" +
            "contact:phone|" +
            "mobile|" +
            "contact:mobile|" +
            "website|" +
            "contact:website|" +
            "url|" +
            "email|" +
            "contact:email|" +
            "instagram|" +
            "contact:instagram" +
            ")$";

    private String buildStructuredTagFilter(String rawFilter) {
        StringBuilder filterBuilder = new StringBuilder();

        for (String part : rawFilter.split(",")) {
            String[] kv = part.split("=", 2);

            if (kv.length != 2 || kv[0].isBlank() || kv[1].isBlank()) {
                continue;
            }

            filterBuilder.append(
                    String.format(
                            "[\"%s\"=\"%s\"]",
                            escapeTag(kv[0].trim()),
                            escapeTag(kv[1].trim())
                    )
            );
        }

        return filterBuilder.isEmpty() ? null : filterBuilder.toString();
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
        String requestedCountryCode = CountryCodeResolver.resolveToIso2(request.country());
        String cacheKey = "geo:" + cityKey + "|" + requestedCountryCode;

        TimedValue<GeoScope> cached = geoCache.get(cacheKey);
        if (cached != null && cached.valid()) {
            log.info("[osm] geocode_cache_hit campaignId={} city={} countryCode={}", request.campaignId(), request.city(), requestedCountryCode);
            return cached.value();
        }

        log.info("[osm] country_resolved_local campaignId={} requestedCountry={} countryCode={}",
                request.campaignId(), request.country(), requestedCountryCode);

        String response;
        Exception lastFailure = null;

        for (int attempt = 1; attempt <= nominatimMaxAttempts; attempt++) {
            if (budget.expired()) {
                throw new ApiException(HttpStatus.BAD_GATEWAY, "OSM_DISCOVERY_TIMEOUT", "Deadline excedido durante geocodificação");
            }

            awaitNominatimSlot(budget);

            int effectiveTimeout = budget.clampTimeout(nominatimTimeoutMs);
            if (effectiveTimeout <= 0) {
                throw new ApiException(HttpStatus.BAD_GATEWAY, "OSM_DISCOVERY_TIMEOUT", "Budget esgotado antes da chamada ao Nominatim.");
            }

            try {
                response = client(effectiveTimeout).get()
                        .uri(uriBuilder -> uriBuilder
                                .scheme("https")
                                .host(nominatimBaseUrl.replace("https://", "").replace("http://", ""))
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

                        String osmType = hit.path("osm_type").asText(null);
                        long osmId = hit.path("osm_id").asLong(-1L);

                        log.info("[osm] geocode_success campaignId={} requestedCity={} requestedCountry={} resolvedCity={} resolvedCountry={} countryCode={} osmType={} osmId={} lat={} lon={} bboxValid={}",
                                request.campaignId(), request.city(), request.country(), hitCity, hitCountry, hitCountryCode, osmType, osmId, lat, lon, bboxValid);

                        GeoScope scope = new GeoScope(lat, lon, hitCity, state, hitCountry, hitCountryCode, south, west, north, east, bboxValid, osmType, osmId);
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
                long retryDelayMs = 0;
                if (e instanceof HttpStatusCodeException http && http.getStatusCode().value() == 429) {
                    retryDelayMs = resolveRetryAfterMs(http);
                    log.warn("[osm] nominatim_rate_limited campaignId={} city={} countryCode={} attempt={}/{} retryDelayMs={}",
                            request.campaignId(), request.city(), requestedCountryCode, attempt, nominatimMaxAttempts, retryDelayMs);
                }
                log.warn("[osm] geocode_failed campaignId={} attempt={}/{} errorType={} rootCauseType={} rootCauseMessage={} retryable={}",
                        request.campaignId(), attempt, nominatimMaxAttempts, e.getClass().getSimpleName(), fd.rootCauseType(), fd.safeMessage(), retryable);
                if (!retryable) throw new ApiException(HttpStatus.BAD_GATEWAY, "OSM_GEOCODE_ERROR", "Falha não recuperável: " + fd.safeMessage());
            }
        }

        throw new ApiException(HttpStatus.BAD_GATEWAY, "OSM_GEOCODE_ERROR",
                "Falha ao geocodificar cidade após retentativas: " + request.city() + " (" + request.country() + ")");
    }

    public AreaQueryResult queryRegionWithStrategy(
            GeoScope scope,
            GeographicStrategy geographicStrategy,
            String niche,
            SearchRegion region,
            AreaQueryPhase phase,
            int rawLimit,
            DiscoveryBudget budget,
            Long campaignId
    ) {
        List<String> endpoints = orderedEndpointsRoundRobin();
        long startNs = System.nanoTime();
        boolean anyHttpAttemptMade = false;

        while (!budget.expired()) {
            boolean anyEndpointTried = false;

            for (int endpointIdx = 0; endpointIdx < endpoints.size(); endpointIdx++) {
                String url = endpoints.get(endpointIdx);
                String host = hostOf(url);

                if (!circuitBreaker.tryAcquire(host)) {
                    log.info("[osm] endpoint_skipped campaignId={} phase={} endpointHost={} reason=circuit_open", campaignId, phase, host);
                    continue;
                }

                anyEndpointTried = true;

                long waitBudgetMs = budget.remainingMs();
                if (waitBudgetMs <= 0) {
                    circuitBreaker.releaseProbe(host);
                    return AreaQueryResult.infraUnavailable("OSM_DISCOVERY_TIMEOUT", "Budget insuficiente antes de adquirir semaphore", elapsedMs(startNs));
                }

                boolean acquired = false;
                boolean requestSent = false;
                try {
                    acquired = overpassSemaphore.tryAcquire(waitBudgetMs, TimeUnit.MILLISECONDS);
                    if (!acquired) {
                        circuitBreaker.releaseProbe(host);
                        return AreaQueryResult.infraUnavailable("OSM_SEMAPHORE_TIMEOUT", "Budget esgotado aguardando acesso ao Overpass", elapsedMs(startNs));
                    }

                    int effectiveTimeoutMs = budget.clampTimeout(timeoutMs);
                    if (effectiveTimeoutMs <= 0) {
                        circuitBreaker.releaseProbe(host);
                        return AreaQueryResult.infraUnavailable("OSM_DISCOVERY_TIMEOUT", "Budget insuficiente após adquirir semaphore", elapsedMs(startNs));
                    }

                    int overpassTimeoutSeconds = Math.max(1, (int) Math.ceil(effectiveTimeoutMs / 1000.0));

                    String query;
                    if (phase == AreaQueryPhase.STRUCTURED) {
                        query = buildStructuredQuery(niche, scope, geographicStrategy, region, rawLimit, overpassTimeoutSeconds);
                    } else {
                        query = buildNameFallbackQuery(niche, scope, geographicStrategy, region, rawLimit, overpassTimeoutSeconds);
                    }

                    if (query == null) {
                        circuitBreaker.releaseProbe(host);
                        return AreaQueryResult.success(List.of(), false, null, elapsedMs(startNs));
                    }

                    log.info("[osm] area_query_start campaignId={} depth={} phase={} geographicStrategy={} maxEdgeKm={} bbox={} rawLimit={} endpointHost={} remainingBudgetMs={} contactFirst=true",
                            campaignId, region.depth(), phase, geographicStrategy, region.maxEdgeKm(), region.bbox(), rawLimit, host, budget.remainingMs());

                    requestSent = true;
                    anyHttpAttemptMade = true;
                    String response = client(effectiveTimeoutMs).post()
                            .uri(url)
                            .header("Content-Type", "application/x-www-form-urlencoded")
                            .body("data=" + java.net.URLEncoder.encode(query, java.nio.charset.StandardCharsets.UTF_8))
                            .retrieve()
                            .body(String.class);

                    long elapsedMs = elapsedMs(startNs);

                    JsonNode root = objectMapper.readTree(response);
                    JsonNode elements = root.path("elements");
                    if (elements.isMissingNode() || !elements.isArray()) {
                        circuitBreaker.recordSuccess(host);
                        return AreaQueryResult.success(List.of(), false, host, elapsedMs);
                    }

                    boolean adminAreaResolved = geographicStrategy != GeographicStrategy.ADMIN_AREA;
                    List<JsonNode> leadElements = new ArrayList<>();

                    for (JsonNode element : elements) {
                        String elementType = element.path("type").asText("");

                        if (geographicStrategy == GeographicStrategy.ADMIN_AREA && "area".equalsIgnoreCase(elementType)) {
                            adminAreaResolved = true;
                            continue;
                        }

                        leadElements.add(element);
                    }

                    if (geographicStrategy == GeographicStrategy.ADMIN_AREA && !adminAreaResolved) {
                        circuitBreaker.recordSuccess(host);

                        log.warn("[osm] admin_area_unavailable campaignId={} osmType={} osmId={} endpointHost={} fallback=BBOX",
                                campaignId, scope.osmType(), scope.osmId(), host);

                        return AreaQueryResult.adminAreaUnavailable(host, elapsedMs);
                    }

                    if (leadElements.isEmpty()) {
                        circuitBreaker.recordSuccess(host);
                        return AreaQueryResult.success(List.of(), false, host, elapsedMs);
                    }

                    int rawLeadElements = leadElements.size();
                    boolean saturated = rawLeadElements >= rawLimit;

                    List<LeadCandidate> candidates = new ArrayList<>();
                    for (JsonNode el : leadElements) {
                        if (candidates.size() >= rawLimit) break;
                        LeadCandidate candidate = mapElement(el, scope, geographicStrategy == GeographicStrategy.ADMIN_AREA);
                        if (candidate != null) {
                            candidates.add(candidate);
                        }
                    }

                    log.info("[osm] area_query_success campaignId={} depth={} phase={} geographicStrategy={} endpointHost={} rawElements={} mapped={} saturated={} elapsedMs={}",
                            campaignId, region.depth(), phase, geographicStrategy, host, rawLeadElements, candidates.size(), saturated, elapsedMs);

                    circuitBreaker.recordSuccess(host);
                    return AreaQueryResult.success(candidates, saturated, host, elapsedMs);

                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    circuitBreaker.releaseProbe(host);

                    log.warn("[osm] overpass_interrupted campaignId={} depth={} phase={} endpointHost={} requestSent={}",
                            campaignId, region.depth(), phase, host, requestSent);

                    if (requestSent) {
                        return AreaQueryResult.infraUnavailableWithAttempt("OSM_DISCOVERY_INTERRUPTED", "Busca interrompida antes da conclusão da chamada Overpass.", elapsedMs(startNs));
                    } else {
                        return AreaQueryResult.infraUnavailable("OSM_DISCOVERY_INTERRUPTED", "Busca interrompida antes da conclusão da chamada Overpass.", elapsedMs(startNs));
                    }

                } catch (Exception e) {
                    FailureDetails fd = getFailureDetails(e);
                    long elapsedMs = elapsedMs(startNs);
                    log.warn("[osm] overpass_failed campaignId={} depth={} phase={} endpointHost={} errorType={} rootCauseType={} rootCauseMessage={} elapsedMs={}",
                            campaignId, region.depth(), phase, host, e.getClass().getSimpleName(), fd.rootCauseType(), fd.safeMessage(), elapsedMs);

                    if (isRateLimited(e)) {
                        long cooldownSeconds = resolveRetryAfterSeconds(e);
                        circuitBreaker.recordRateLimited(host, cooldownSeconds);

                        log.warn("[osm] endpoint_rate_limited campaignId={} endpointHost={} cooldownSeconds={} source=429",
                                campaignId, host, cooldownSeconds);

                        continue;
                    }

                    boolean isConnectionRefused = isConnectionRefused(e);
                    boolean isReadTimeout = isReadTimeout(e);
                    boolean is504 = is504(e);
                    boolean timeoutOr504 = isTimeoutOr504(e);

                    // Check for split BEFORE opening circuit for timeout/504 on large regions
                    boolean shouldSplitImmediately = timeoutOr504
                            && region.maxEdgeKm() > failureSplitThresholdKm
                            && region.canSplit(adaptiveMaxDepth, adaptiveMinEdgeKm);

                    if (shouldSplitImmediately) {
                        circuitBreaker.releaseProbe(host);

                        log.info("[osm] region_split_immediate campaignId={} depth={} phase={} endpointHost={} maxEdgeKm={} thresholdKm={} reason=timeout_or_504",
                                campaignId, region.depth(), phase, host, region.maxEdgeKm(), failureSplitThresholdKm);

                        return AreaQueryResult.splitRequired("OSM_SPLIT_REQUIRED", "Região ainda grande após timeout/504; subdividindo antes de failover.", elapsedMs);
                    }

                    if (isConnectionRefused) {
                        log.warn("[osm] endpoint_circuit_opened campaignId={} endpointHost={} reason=connection_refused cooldownSeconds={}",
                                campaignId, host, connectionRefusedCooldownSeconds);
                        circuitBreaker.recordRateLimited(host, connectionRefusedCooldownSeconds);
                        continue;
                    }

                    if (isReadTimeout) {
                        log.warn("[osm] endpoint_circuit_opened campaignId={} endpointHost={} reason=read_timeout cooldownSeconds={}",
                                campaignId, host, timeoutCooldownSeconds);
                        circuitBreaker.recordRateLimited(host, timeoutCooldownSeconds);
                        continue;
                    }

                    if (is504) {
                        log.warn("[osm] endpoint_circuit_opened campaignId={} endpointHost={} reason=504_gateway_timeout cooldownSeconds={}",
                                campaignId, host, timeoutCooldownSeconds);
                        circuitBreaker.recordRateLimited(host, timeoutCooldownSeconds);
                        continue;
                    }

                    boolean retryable = isRetryable(e);

                    if (retryable) {
                        circuitBreaker.recordFailure(host);
                        continue;
                    }

                    if (requestSent) {
                        circuitBreaker.recordSuccess(host);
                    } else {
                        circuitBreaker.releaseProbe(host);
                    }

                    return AreaQueryResult.queryError("OSM_OVERPASS_QUERY_ERROR", "Falha não recuperável no Overpass: " + fd.safeMessage(), elapsedMs);
                } finally {
                    if (acquired) {
                        overpassSemaphore.release();
                    }
                }
            }

            // All endpoints were skipped (circuit open) or all failed with continue
            if (!anyEndpointTried) {
                long minWaitMs = circuitBreaker.getMinWaitMsForAvailableEndpoint();
                if (minWaitMs > 0 && minWaitMs != Long.MAX_VALUE) {
                    long remainingBudgetMs = budget.remainingMs();
                    if (remainingBudgetMs <= 0) {
                        if (anyHttpAttemptMade) {
                            return AreaQueryResult.infraUnavailableWithAttempt("OSM_DISCOVERY_TIMEOUT", "Budget esgotado aguardando endpoint disponível", elapsedMs(startNs));
                        } else {
                            return AreaQueryResult.infraUnavailable("OSM_DISCOVERY_TIMEOUT", "Budget esgotado aguardando endpoint disponível", elapsedMs(startNs));
                        }
                    }

                    long waitMs = Math.min(minWaitMs, remainingBudgetMs);

                    log.info("[osm] overpass_waiting_for_endpoint campaignId={} depth={} phase={} waitMs={} remainingBudgetMs={} nextEndpointCheckMs={}",
                            campaignId, region.depth(), phase, waitMs, remainingBudgetMs, minWaitMs);

                    try {
                        Thread.sleep(waitMs);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        if (anyHttpAttemptMade) {
                            return AreaQueryResult.infraUnavailableWithAttempt("OSM_DISCOVERY_INTERRUPTED", "Busca interrompida aguardando endpoint Overpass", elapsedMs(startNs));
                        } else {
                            return AreaQueryResult.infraUnavailable("OSM_DISCOVERY_INTERRUPTED", "Busca interrompida aguardando endpoint Overpass", elapsedMs(startNs));
                        }
                    }
                    // Loop continues, re-evaluate endpoints
                    continue;
                }
            }

            // If we got here, either some endpoint was tried (and all failed with non-retryable errors)
            // or no endpoints are known. Return the failure.
            long elapsedMs = elapsedMs(startNs);
            if (anyHttpAttemptMade) {
                return AreaQueryResult.infraUnavailableWithAttempt("OSM_ALL_ENDPOINTS_FAILED", "Todos os endpoints Overpass indisponíveis", elapsedMs);
            } else {
                return AreaQueryResult.infraUnavailable("OSM_ALL_ENDPOINTS_FAILED", "Todos os endpoints Overpass indisponíveis", elapsedMs);
            }
        }

        long elapsedMs = elapsedMs(startNs);
        if (anyHttpAttemptMade) {
            return AreaQueryResult.infraUnavailableWithAttempt("OSM_DISCOVERY_TIMEOUT", "Budget de descoberta esgotado", elapsedMs);
        } else {
            return AreaQueryResult.infraUnavailable("OSM_DISCOVERY_TIMEOUT", "Budget de descoberta esgotado", elapsedMs);
        }
    }

    // Compatibilidade temporária com assinatura antiga
    public AreaQueryResult queryRegion(
            GeoScope scope,
            String niche,
            SearchRegion region,
            AreaQueryPhase phase,
            int rawLimit,
            String ignoredPreferredEndpointHost,
            DiscoveryBudget budget,
            Long campaignId
    ) {
        return queryRegionWithStrategy(
                scope,
                scope.hasAdminAreaCandidate() ? GeographicStrategy.ADMIN_AREA : GeographicStrategy.BBOX_FALLBACK,
                niche,
                region,
                phase,
                rawLimit,
                budget,
                campaignId
        );
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

    String buildStructuredQuery(
            String niche,
            GeoScope scope,
            GeographicStrategy geographicStrategy,
            SearchRegion region,
            int limit,
            int overpassTimeoutSeconds
    ) {
        NicheMapper.NicheStrategy strategy = NicheMapper.resolve(niche);

        if (strategy.tagFilters().isEmpty()) {
            return null;
        }

        StringBuilder sb = new StringBuilder();

        sb.append("[out:json][timeout:")
                .append(Math.max(1, overpassTimeoutSeconds))
                .append("];");

        if (geographicStrategy == GeographicStrategy.ADMIN_AREA) {
            sb.append(buildAdminAreaPreamble(scope));
        }

        sb.append("(");

        String locationFilter = buildLocationFilter(geographicStrategy, region);

        for (String baseFilter : strategy.tagFilters()) {

            String tagFilter = buildStructuredTagFilter(baseFilter);

            if (tagFilter == null || tagFilter.isBlank()) {
                continue;
            }

            sb.append("nwr")
                    .append(tagFilter)
                    .append("[\"~\"")
                    .append(escapeTag(CONTACT_KEYS_REGEX))
                    .append("\"]")
                    .append(locationFilter)
                    .append(";");
        }

        sb.append(");out center tags ")
                .append(Math.min(limit, MAX_OUT))
                .append(";");

        return sb.toString();
    }

    String buildNameFallbackQuery(
            String niche,
            GeoScope scope,
            GeographicStrategy geographicStrategy,
            SearchRegion region,
            int limit,
            int overpassTimeoutSeconds
    ) {
        NicheMapper.NicheStrategy strategy = NicheMapper.resolve(niche);

        String fallbackRegex = strategy.fallbackNameRegex();

        if (fallbackRegex == null || fallbackRegex.isBlank() || fallbackRegex.equals("''")) {
            return null;
        }

        StringBuilder sb = new StringBuilder();

        sb.append("[out:json][timeout:")
                .append(Math.max(1, overpassTimeoutSeconds))
                .append("];");

        if (geographicStrategy == GeographicStrategy.ADMIN_AREA) {
            sb.append(buildAdminAreaPreamble(scope));
        }

        String locationFilter = buildLocationFilter(geographicStrategy, region);

        sb.append("(");

        sb.append("nwr[\"name\"~\"")
                .append(fallbackRegex)
                .append("\",i][\"~\"")
                .append(escapeTag(CONTACT_KEYS_REGEX))
                .append("\"]")
                .append(locationFilter)
                .append(";");

        sb.append(");out center tags ")
                .append(Math.min(limit, MAX_OUT))
                .append(";");

        return sb.toString();
    }

    LeadCandidate mapElement(
            JsonNode el,
            GeoScope scope,
            boolean cityMembershipVerified
    ) {
        JsonNode tags = el.path("tags");
        if (tags.isMissingNode() || !tags.isObject()) return null;
        String name = asTextOrNull(tags, "name");
        if (name == null || name.isBlank()) return null;

        String type = el.path("type").asText("");
        String id = el.path("id").asText("");
        if (type.isBlank() || id.isBlank()) return null;

        String taggedCity = firstPresent(tags, "addr:city", "addr:town", "addr:village", "addr:municipality");

        if (!cityMembershipVerified) {
            if (taggedCity == null || !cityMatches(taggedCity, scope.city())) {
                log.info("[osm] candidate_skipped reason=bbox_city_not_verified sourceType={} sourceId={} taggedCity={} requestedCity={}",
                        type, id, taggedCity, scope.city());
                return null;
            }
        }

        LeadCandidate candidate = new LeadCandidate(name.trim(), "openstreetmap", type + "/" + id);
        candidate.setCategory(buildCategory(tags));
        candidate.setWebsite(firstPresent(tags, "contact:website", "website", "url"));
        candidate.setPhone(firstPresent(tags, "contact:phone", "phone", "contact:mobile", "mobile"));
        candidate.setEmail(firstPresent(tags, "contact:email", "email"));

        candidate.setCity(taggedCity != null ? taggedCity : scope.city());
        candidate.setState(asTextOrNull(tags, "addr:state") != null ? asTextOrNull(tags, "addr:state") : scope.state());
        candidate.setCountry(asTextOrNull(tags, "addr:country") != null ? asTextOrNull(tags, "addr:country") : scope.country());
        candidate.setAddress(buildAddress(tags, scope, cityMembershipVerified));
        candidate.setInstagramStatus("NOT_FOUND");

        String instagramRaw = firstPresent(tags, "contact:instagram", "instagram");

        if (instagramRaw != null && !instagramRaw.isBlank()) {
            String normalizedInstagram = normalizer.normalizeInstagram(instagramRaw);

            if (normalizedInstagram != null && !normalizedInstagram.isBlank()) {
                candidate.setInstagramUsername(normalizedInstagram);
                candidate.setInstagramUrl("https://instagram.com/" + normalizedInstagram);
                candidate.setInstagramStatus("FOUND");
            }
        }

        return candidate;
    }

    LeadCandidate mapElement(JsonNode el, GeoScope scope) {
        return mapElement(el, scope, false);
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

    static String buildAddress(JsonNode tags, GeoScope scope, boolean cityMembershipVerified) {
        String street = asTextOrNull(tags, "addr:street");
        String number = asTextOrNull(tags, "addr:housenumber");
        String suburb = asTextOrNull(tags, "addr:suburb", "addr:neighbourhood", "addr:district");
        String city = asTextOrNull(tags, "addr:city", "addr:town", "addr:village", "addr:municipality");
        String state = asTextOrNull(tags, "addr:state");
        if (city == null && cityMembershipVerified) {
            city = scope.city();
        }
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

    static String buildAddress(JsonNode tags, GeoScope scope) {
        return buildAddress(tags, scope, false);
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

    private long elapsedMs(long startNs) {
        return (System.nanoTime() - startNs) / 1_000_000L;
    }

    private long resolveRetryAfterMs(HttpStatusCodeException http) {
        String retryAfter = http.getResponseHeaders() != null
                ? http.getResponseHeaders().getFirst("Retry-After")
                : null;

        if (retryAfter == null || retryAfter.isBlank()) {
            return nominatim429BackoffMs;
        }

        try {
            long seconds = Long.parseLong(retryAfter.trim());
            return Math.max(1L, seconds) * 1000L;
        } catch (NumberFormatException ignored) {
        }

        try {
            java.time.ZonedDateTime retryAt = java.time.ZonedDateTime.parse(
                    retryAfter.trim(),
                    java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME
            );

            long millis = java.time.Duration.between(
                    java.time.ZonedDateTime.now(java.time.ZoneOffset.UTC),
                    retryAt.withZoneSameInstant(java.time.ZoneOffset.UTC)
            ).toMillis();

            return Math.max(1L, millis);
        } catch (RuntimeException ignored) {
            return nominatim429BackoffMs;
        }
    }

    private long resolveRetryAfterSeconds(Throwable throwable) {
        if (!(throwable instanceof HttpStatusCodeException http)) {
            return rateLimitCooldownSeconds;
        }

        String retryAfter = http.getResponseHeaders() != null
                ? http.getResponseHeaders().getFirst("Retry-After")
                : null;

        if (retryAfter == null || retryAfter.isBlank()) {
            return rateLimitCooldownSeconds;
        }

        try {
            long seconds = Long.parseLong(retryAfter.trim());
            return Math.max(1L, seconds);
        } catch (NumberFormatException ignored) {
        }

        try {
            java.time.ZonedDateTime retryAt = java.time.ZonedDateTime.parse(
                    retryAfter.trim(),
                    java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME
            );

            long seconds = java.time.Duration.between(
                    java.time.ZonedDateTime.now(java.time.ZoneOffset.UTC),
                    retryAt.withZoneSameInstant(java.time.ZoneOffset.UTC)
            ).getSeconds();

            return Math.max(1L, seconds);
        } catch (RuntimeException ignored) {
            return rateLimitCooldownSeconds;
        }
    }

    private boolean isRateLimited(Throwable throwable) {
        return throwable instanceof HttpStatusCodeException http
                && http.getStatusCode().value() == 429;
    }

    private boolean isConnectionRefused(Throwable throwable) {
        Throwable root = rootCause(throwable);
        return root instanceof ConnectException
                || (root.getMessage() != null && root.getMessage().toLowerCase().contains("connection refused"));
    }

    private boolean isReadTimeout(Throwable throwable) {
        Throwable root = rootCause(throwable);
        return root instanceof SocketTimeoutException
                || (root.getMessage() != null && root.getMessage().toLowerCase().contains("read timed out"));
    }

    private boolean is504(Throwable throwable) {
        if (throwable instanceof HttpStatusCodeException http
                && http.getStatusCode().value() == 504) {
            return true;
        }
        return false;
    }

    private boolean isTimeoutOr504(Throwable throwable) {
        if (throwable instanceof HttpStatusCodeException http
                && http.getStatusCode().value() == 504) {
            return true;
        }

        Throwable root = rootCause(throwable);
        return root instanceof SocketTimeoutException;
    }

    private Throwable rootCause(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    static class OverpassCircuitBreaker {
        private static final class EndpointState {
            volatile State state = State.CLOSED;
            volatile long openSince = 0L;
            volatile long openUntilEpochMs = 0L;
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
                    long now = System.currentTimeMillis();
                    long until = es.openUntilEpochMs > 0L
                            ? es.openUntilEpochMs
                            : es.openSince + openSeconds * 1000L;

                    if (now < until) {
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
                es.openUntilEpochMs = 0L;
                es.halfOpenInFlight = false;
            }
        }

        void recordFailure(String host) {
            EndpointState es = states.computeIfAbsent(host, k -> new EndpointState());

            synchronized (es) {
                long now = System.currentTimeMillis();
                es.state = EndpointState.State.OPEN;
                es.openSince = now;
                es.openUntilEpochMs = now + openSeconds * 1000L;
                es.halfOpenInFlight = false;
            }
        }

        void recordRateLimited(String host, long cooldownSeconds) {
            EndpointState es = states.computeIfAbsent(host, k -> new EndpointState());

            synchronized (es) {
                long now = System.currentTimeMillis();
                es.state = EndpointState.State.OPEN;
                es.openSince = now;
                es.openUntilEpochMs = now + Math.max(1L, cooldownSeconds) * 1000L;
                es.halfOpenInFlight = false;
            }
        }

        void releaseProbe(String host) {
            EndpointState es = states.get(host);

            if (es == null) {
                return;
            }

            synchronized (es) {
                if (
                        es.state == EndpointState.State.HALF_OPEN
                        && es.halfOpenInFlight
                ) {
                    es.halfOpenInFlight = false;
                }
            }
        }

        /**
         * Returns the minimum milliseconds until any known endpoint becomes available for a probe (HALF_OPEN).
         * Returns 0 if any endpoint is currently CLOSED or HALF_OPEN (no probe in flight).
         * Returns Long.MAX_VALUE if no endpoints are known.
         */
        long getMinWaitMsForAvailableEndpoint() {
            long now = System.currentTimeMillis();
            long minWait = Long.MAX_VALUE;
            boolean anyKnown = false;

            for (EndpointState es : states.values()) {
                anyKnown = true;
                synchronized (es) {
                    if (es.state == EndpointState.State.CLOSED) {
                        return 0L;
                    }
                    if (es.state == EndpointState.State.HALF_OPEN) {
                        if (!es.halfOpenInFlight) {
                            return 0L;
                        }
                        // Half-open but probe in flight, wait for it to complete (conservative: assume openSeconds)
                        minWait = Math.min(minWait, openSeconds * 1000L);
                    } else if (es.state == EndpointState.State.OPEN) {
                        long until = es.openUntilEpochMs > 0L
                                ? es.openUntilEpochMs
                                : es.openSince + openSeconds * 1000L;
                        long wait = until - now;
                        if (wait < 0) wait = 0;
                        minWait = Math.min(minWait, wait);
                    }
                }
            }

            if (!anyKnown) {
                return 0L;
            }
            return minWait;
        }
    }
}