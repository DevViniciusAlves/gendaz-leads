package com.gendaz.leads.service.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gendaz.leads.domain.LeadCandidate;
import com.gendaz.leads.exception.ApiException;
import com.gendaz.leads.service.InstagramDetector;
import com.gendaz.leads.service.WebsiteContactEnricher;
import com.gendaz.leads.util.Normalizer;
import com.gendaz.leads.util.SsrfGuard;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.util.List;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class OpenStreetMapProviderTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private OpenStreetMapProvider providerWith(RestClient.Builder builder) {
        InstagramDetector igDetector = mock(InstagramDetector.class);
        WebsiteContactEnricher enricher = mock(WebsiteContactEnricher.class);
        SsrfGuard ssrfGuard = mock(SsrfGuard.class);
        when(ssrfGuard.isSafe(anyString())).thenReturn(true);
        
        OpenStreetMapProvider p = new OpenStreetMapProvider(builder, mapper, igDetector, enricher, new Normalizer(), ssrfGuard);
        ReflectionTestUtils.setField(p, "enabled", true);
        ReflectionTestUtils.setField(p, "timeoutMs", 5000);
        ReflectionTestUtils.setField(p, "nominatimTimeoutMs", 5000);
        ReflectionTestUtils.setField(p, "nominatimMaxAttempts", 1);
        ReflectionTestUtils.setField(p, "discoveryDeadlineMs", 60000);
        ReflectionTestUtils.setField(p, "overpassEndpoints", "https://overpass.test/api/interpreter");
        ReflectionTestUtils.setField(p, "maxConcurrency", 1);
        ReflectionTestUtils.setField(p, "circuitOpenSeconds", 180);
        return p;
    }

    private OpenStreetMapProvider providerWithLegacy(RestClient.Builder builder) {
        InstagramDetector igDetector = mock(InstagramDetector.class);
        WebsiteContactEnricher enricher = mock(WebsiteContactEnricher.class);
        SsrfGuard ssrfGuard = mock(SsrfGuard.class);
        when(ssrfGuard.isSafe(anyString())).thenReturn(true);
        
        OpenStreetMapProvider p = new OpenStreetMapProvider(builder, mapper, igDetector, enricher, new Normalizer(), ssrfGuard);
        ReflectionTestUtils.setField(p, "enabled", true);
        ReflectionTestUtils.setField(p, "timeoutMs", 5000);
        ReflectionTestUtils.setField(p, "nominatimTimeoutMs", 5000);
        ReflectionTestUtils.setField(p, "nominatimMaxAttempts", 1);
        ReflectionTestUtils.setField(p, "discoveryDeadlineMs", 60000);
        ReflectionTestUtils.setField(p, "overpassUrl", "https://legacy.primary/api/interpreter");
        ReflectionTestUtils.setField(p, "fallbackUrl", "https://legacy.fallback/api/interpreter");
        ReflectionTestUtils.setField(p, "overpassEndpoints", "");
        ReflectionTestUtils.setField(p, "maxConcurrency", 1);
        ReflectionTestUtils.setField(p, "circuitOpenSeconds", 180);
        return p;
    }

    // ---------- OSM-only: Google ausente ----------

    @Test
    void googlePlacesProviderAbsent() {
        assertThrows(ClassNotFoundException.class,
                () -> Class.forName("com.gendaz.leads.service.provider.GooglePlacesProvider"));
        InstagramDetector igDetector = mock(InstagramDetector.class);
        WebsiteContactEnricher enricher = mock(WebsiteContactEnricher.class);
        SsrfGuard ssrfGuard = mock(SsrfGuard.class);
        when(ssrfGuard.isSafe(anyString())).thenReturn(true);
        assertEquals("openstreetmap",
                new OpenStreetMapProvider(null, mapper, igDetector, enricher, new Normalizer(), ssrfGuard).getName());
    }

    // ---------- NicheMapper: barbearia / determinismo ----------

    @Test
    void barbeariaCompoundTags() {
        var s = NicheMapper.resolve("barbearia");
        assertTrue(s.tagFilters().contains("shop=barber"));
        assertTrue(s.tagFilters().contains("shop=hairdresser,hairdresser=barber"));
        assertTrue(s.fallbackNameRegex().contains("barber"));
        assertTrue(s.fallbackNameRegex().contains("barbearia"));
    }

    @Test
    void partialMatchDeterministicPrefersLongestAlias() {
        var first = NicheMapper.resolve("massagem spa");
        for (int i = 0; i < 50; i++) {
            assertEquals(first, NicheMapper.resolve("massagem spa"));
        }
        assertEquals(NicheMapper.resolve("massagem").tagFilters(), first.tagFilters());
    }

    @Test
    void regexNeverHasQuoteMarkers() {
        for (String niche : List.of("barbearia", "cílios", "x\");out body;/*", "salão de beleza")) {
            String regex = NicheMapper.resolve(niche).fallbackNameRegex();
            assertFalse(regex.contains("\\Q"), "niche=" + niche);
            assertFalse(regex.contains("\\E"), "niche=" + niche);
        }
    }

    // ---------- Bounding box ----------

    @Test
    void bboxOrderSouthWestNorthEast() {
        var tile = new OpenStreetMapProvider.Tile(1.0, 3.0, 2.0, 4.0, 0.0);
        OpenStreetMapProvider provider = new OpenStreetMapProvider(null, mapper, null, null, new Normalizer(), null);
        String structuredQuery = provider.buildStructuredQuery("cilios", tile, 30);
        assertTrue(structuredQuery.contains("1.000000,3.000000,2.000000,4.000000"), structuredQuery);
    }

    @Test
    void tileBuilderWorks() {
        var tile = new OpenStreetMapProvider.Tile(1.0, 3.0, 2.0, 4.0, 0.0);
        assertEquals("1.000000,3.000000,2.000000,4.000000", tile.bbox());
    }

    // ---------- Retry ----------

    @Test
    void retryableStatuses() {
        assertTrue(OpenStreetMapProvider.isRetryable(
                HttpServerErrorException.create(HttpStatus.TOO_MANY_REQUESTS, "r", null, null, null)));
        assertTrue(OpenStreetMapProvider.isRetryable(
                HttpServerErrorException.create(HttpStatus.SERVICE_UNAVAILABLE, "r", null, null, null)));
        assertTrue(OpenStreetMapProvider.isRetryable(
                HttpServerErrorException.create(HttpStatus.BAD_GATEWAY, "r", null, null, null)));
        assertTrue(OpenStreetMapProvider.isRetryable(
                HttpServerErrorException.create(HttpStatus.GATEWAY_TIMEOUT, "r", null, null, null)));
    }

    @Test
    void nonRetryableClientError() {
        HttpStatusCodeException bad = HttpClientErrorException.create(
                HttpStatus.BAD_REQUEST, "bad", null, null, null);
        assertFalse(OpenStreetMapProvider.isRetryable(bad));
    }

    @Test
    void isRetryableClassification() {
        assertTrue(OpenStreetMapProvider.isRetryable(new ConnectException("connection refused")));
        assertTrue(OpenStreetMapProvider.isRetryable(new SocketTimeoutException("read timed out")));
        assertTrue(OpenStreetMapProvider.isRetryable(new java.net.NoRouteToHostException("no route")));
        assertTrue(OpenStreetMapProvider.isRetryable(new java.net.UnknownHostException("unknown")));
        assertTrue(OpenStreetMapProvider.isRetryable(new RuntimeException("connection reset")));
        
        assertFalse(OpenStreetMapProvider.isRetryable(new javax.net.ssl.SSLHandshakeException("handshake")));
        
        assertTrue(OpenStreetMapProvider.isRetryable(new RuntimeException("wrap", new ConnectException())));
    }

    // ---------- Barbearia query tests ----------

    @Test
    void barbeariaStructuredQueryHasCorrectTags() {
        var tile = new OpenStreetMapProvider.Tile(-24.0, -47.0, -23.0, -46.0, 0.0);
        OpenStreetMapProvider provider = new OpenStreetMapProvider(null, mapper, null, null, new Normalizer(), null);
        String structuredQuery = provider.buildStructuredQuery("barbearia", tile, 10);
        String fallbackQuery = provider.buildNameFallbackQuery("barbearia", tile, 10);

        assertTrue(structuredQuery.contains("[\"shop\"=\"barber\"]"));
        assertTrue(structuredQuery.contains("[\"shop\"=\"hairdresser\"][\"hairdresser\"=\"barber\"]"));
        assertFalse(structuredQuery.contains("name~"));
        assertTrue(fallbackQuery != null && fallbackQuery.contains("barbearia|barber|barbershop"));
    }

    @Test
    void barbeariaStructuredQueryRespectsLimit() {
        var tile = new OpenStreetMapProvider.Tile(-24.0, -47.0, -23.0, -46.0, 0.0);
        OpenStreetMapProvider provider = new OpenStreetMapProvider(null, mapper, null, null, new Normalizer(), null);
        String structuredQuery = provider.buildStructuredQuery("barbearia", tile, 13);
        String fallbackQuery = provider.buildNameFallbackQuery("barbearia", tile, 13);

        assertTrue(structuredQuery.contains("out center tags 13"));
        assertTrue(fallbackQuery.contains("out center tags 13"));
    }

    @Test
    void largeCityBboxIsClamped() {
        var tile = new OpenStreetMapProvider.Tile(-25.0, -48.0, -22.0, -45.0, 0.0);
        OpenStreetMapProvider provider = new OpenStreetMapProvider(null, mapper, null, null, new Normalizer(), null);
        String structuredQuery = provider.buildStructuredQuery("barbearia", tile, 10);

        assertTrue(structuredQuery.contains("-25.000000,-48.000000,-22.000000,-45.000000"), structuredQuery);
    }

    @Test
    void smallCityBboxPreserved() {
        var tile = new OpenStreetMapProvider.Tile(-16.0, -56.5, -15.0, -55.5, 0.0);
        OpenStreetMapProvider provider = new OpenStreetMapProvider(null, mapper, null, null, new Normalizer(), null);
        String structuredQuery = provider.buildStructuredQuery("cilios", tile, 10);

        assertTrue(structuredQuery.contains("-16.000000,-56.500000,-15.000000,-55.500000"), structuredQuery);
    }

    // ---------- Discovery result tests ----------

    private static final String GEO_JSON = """
            [{"lat":"-15.6","lon":"-56.1",
              "address":{"city":"Cuiaba","state":"Mato Grosso","country":"Brasil","country_code":"br"},
              "boundingbox":["-16.0","-15.0","-56.5","-55.5"]}]""";

    private static final String COUNTRY_BRASIL_JSON = """
            [{"lat":"-10.0","lon":"-55.0",
              "address":{"country":"Brasil","country_code":"br"},
              "boundingbox":["-33.0","5.0","-73.0","-34.0"]}]""";

    @SuppressWarnings({"unchecked", "rawtypes"})
    private RestClient stubHttp(RestClient.Builder builder, String nominatimBody,
                                Object overpassBehavior) {
        return stubHttp(builder, nominatimBody, COUNTRY_BRASIL_JSON, overpassBehavior);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private RestClient stubHttp(RestClient.Builder builder, String cityNominatimBody, String countryNominatimBody,
                                Object overpassBehavior) {
        RestClient restClient = mock(RestClient.class);
        when(builder.requestFactory(any(ClientHttpRequestFactory.class))).thenReturn(builder);
        when(builder.defaultHeader(anyString(), any(String[].class))).thenReturn(builder);
        when(builder.build()).thenReturn(restClient);

        // We need to handle two GET calls: first for country resolution, then for city geocoding
        RestClient.RequestHeadersUriSpec getSpec = mock(RestClient.RequestHeadersUriSpec.class);
        RestClient.RequestHeadersSpec headersSpec = mock(RestClient.RequestHeadersSpec.class);
        RestClient.ResponseSpec getResponse1 = mock(RestClient.ResponseSpec.class);
        RestClient.ResponseSpec getResponse2 = mock(RestClient.ResponseSpec.class);
        when(restClient.get()).thenReturn(getSpec);
        when(getSpec.uri(any(Function.class))).thenReturn(headersSpec)
                .thenReturn(headersSpec);
        when(headersSpec.retrieve())
                .thenReturn(getResponse1)
                .thenReturn(getResponse2);
        when(getResponse1.body(eq(String.class))).thenReturn(countryNominatimBody);
        when(getResponse2.body(eq(String.class))).thenReturn(cityNominatimBody);

        RestClient.RequestBodyUriSpec postSpec = mock(RestClient.RequestBodyUriSpec.class);
        RestClient.RequestBodySpec bodySpec = mock(RestClient.RequestBodySpec.class);
        RestClient.ResponseSpec postResponse = mock(RestClient.ResponseSpec.class);
        when(restClient.post()).thenReturn(postSpec);
        when(postSpec.uri(anyString())).thenReturn(bodySpec);
        when(bodySpec.header(anyString(), any(String[].class))).thenReturn(bodySpec);
        when(bodySpec.body(anyString())).thenReturn(bodySpec);
        when(bodySpec.retrieve()).thenReturn(postResponse);
        if (overpassBehavior instanceof String s) {
            when(postResponse.body(eq(String.class))).thenReturn(s);
        } else {
            when(postResponse.body(eq(String.class))).thenThrow((Throwable) overpassBehavior);
        }
        return restClient;
    }

    @Test
    void overpassZeroIsValidEmptyWithoutFallback() {
        RestClient.Builder builder = mock(RestClient.Builder.class);
        RestClient restClient = stubHttp(builder, GEO_JSON, "{\"elements\":[]}");

        LeadDiscoveryRequest request = new LeadDiscoveryRequest(1L, "cilios", "Cuiaba", "Brasil", 10);
        LeadDiscoveryResult result = providerWith(builder).discover(request);

        assertTrue(result.candidates().isEmpty());
        assertEquals(LeadDiscoveryResult.DiscoveryOutcome.EMPTY, result.outcome());
    }

    @Test
    void nominatimEmptyIsLocationNotFound() {
        RestClient.Builder builder = mock(RestClient.Builder.class);
        stubHttp(builder, "[]", "{\"elements\":[]}");

        LeadDiscoveryRequest request = new LeadDiscoveryRequest(1L, "cilios", "Lugar Inexistente Xyz", "Brasil", 10);
        ApiException ex = assertThrows(ApiException.class,
                () -> providerWith(builder).discover(request));
        assertEquals("LOCATION_NOT_FOUND", ex.getCode());
        assertEquals(HttpStatus.NOT_FOUND, ex.getStatus());
    }

    @Test
    void overpassNonRetryableErrorReturnsEmpty() {
        RestClient.Builder builder = mock(RestClient.Builder.class);
        stubHttp(builder, GEO_JSON,
                HttpClientErrorException.create(HttpStatus.BAD_REQUEST, "bad", null, null, null));

        LeadDiscoveryRequest request = new LeadDiscoveryRequest(1L, "cilios", "Cuiaba", "Brasil", 10);
        LeadDiscoveryResult result = providerWith(builder).discover(request);
        // Non-retryable error on single endpoint -> treated as empty result (no candidates)
        assertEquals(LeadDiscoveryResult.DiscoveryOutcome.EMPTY, result.outcome());
    }

    @Test
    void fallbackEndpointUsedOnlyAfterPrimaryFailure() {
        RestClient.Builder builder = mock(RestClient.Builder.class);
        // First endpoint fails with retryable error, second also fails
        RestClient restClient = stubHttp(builder, GEO_JSON,
                new org.springframework.web.client.ResourceAccessException("connect timed out", new ConnectException("connect timed out")));
        OpenStreetMapProvider p = providerWith(builder);
        ReflectionTestUtils.setField(p, "overpassEndpoints", "https://primary.test/api/interpreter,https://fallback.test/api/interpreter");

        LeadDiscoveryRequest request = new LeadDiscoveryRequest(1L, "cilios", "Cuiaba", "Brasil", 10);
        LeadDiscoveryResult result = p.discover(request);
        // Both endpoints fail with retryable error -> empty result
        assertEquals(LeadDiscoveryResult.DiscoveryOutcome.EMPTY, result.outcome());
    }

    @Test
    void legacyEnvVarsUsedWhenEndpointsNotSet() {
        RestClient.Builder builder = mock(RestClient.Builder.class);
        RestClient restClient = stubHttp(builder, GEO_JSON, "{\"elements\":[]}");

        OpenStreetMapProvider p = providerWithLegacy(builder);
        LeadDiscoveryRequest request = new LeadDiscoveryRequest(1L, "cilios", "Cuiaba", "Brasil", 10);
        LeadDiscoveryResult result = p.discover(request);

        assertTrue(result.candidates().isEmpty());
    }

    @Test
    void newEndpointsEnvTakesPriorityOverLegacy() {
        RestClient.Builder builder = mock(RestClient.Builder.class);
        RestClient restClient = stubHttp(builder, GEO_JSON, "{\"elements\":[]}");

        OpenStreetMapProvider p = providerWithLegacy(builder);
        ReflectionTestUtils.setField(p, "overpassEndpoints", "https://new.primary/api/interpreter,https://new.fallback/api/interpreter");
        LeadDiscoveryRequest request = new LeadDiscoveryRequest(1L, "cilios", "Cuiaba", "Brasil", 10);
        LeadDiscoveryResult result = p.discover(request);

        assertTrue(result.candidates().isEmpty());
    }

    // ---------- Concurrency test ----------

    @Test
    void concurrencyMaxOneOverpassRequest() throws InterruptedException {
        RestClient.Builder builder1 = mock(RestClient.Builder.class);
        RestClient.Builder builder2 = mock(RestClient.Builder.class);
        
        RestClient restClient1 = stubHttp(builder1, GEO_JSON, "{\"elements\":[]}");
        RestClient restClient2 = stubHttp(builder2, GEO_JSON, "{\"elements\":[]}");

        OpenStreetMapProvider p1 = providerWith(builder1);
        OpenStreetMapProvider p2 = providerWith(builder2);

        java.util.concurrent.CountDownLatch startLatch = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch endLatch = new java.util.concurrent.CountDownLatch(2);

        Thread t1 = new Thread(() -> {
            try { startLatch.await(); } catch (InterruptedException ignored) {}
            try {
                p1.discover(new LeadDiscoveryRequest(1L, "cilios", "Cuiaba", "Brasil", 10));
            } catch (Exception ignored) {}
            endLatch.countDown();
        });

        Thread t2 = new Thread(() -> {
            try { startLatch.await(); } catch (InterruptedException ignored) {}
            try {
                p2.discover(new LeadDiscoveryRequest(2L, "cilios", "Cuiaba", "Brasil", 10));
            } catch (Exception ignored) {}
            endLatch.countDown();
        });

        long startTime = System.currentTimeMillis();
        t1.start();
        t2.start();
        startLatch.countDown();
        endLatch.await(10, java.util.concurrent.TimeUnit.SECONDS);
        long elapsed = System.currentTimeMillis() - startTime;

        assertTrue(elapsed < 5000, "Both discoveries should complete within 5 seconds");
    }

    // ---------- Deadline test ----------

    @Test
    void deadlineExceededReturnsDeadlineExceeded() {
        RestClient.Builder builder = mock(RestClient.Builder.class);
        RestClient restClient = stubHttp(builder, GEO_JSON, "{\"elements\":[]}");
        OpenStreetMapProvider p = providerWith(builder);
        ReflectionTestUtils.setField(p, "discoveryDeadlineMs", 1);
        ReflectionTestUtils.setField(p, "timeoutMs", 5000);

        LeadDiscoveryRequest request = new LeadDiscoveryRequest(1L, "cilios", "Cuiaba", "Brasil", 10);
        // Sleep briefly to ensure deadline expires
        try { Thread.sleep(10); } catch (InterruptedException ignored) {}
        LeadDiscoveryResult result = p.discover(request);
        // With fast mock, deadline expires during country resolution -> DEADLINE_EXCEEDED
        assertEquals(LeadDiscoveryResult.DiscoveryOutcome.DEADLINE_EXCEEDED, result.outcome());
        assertEquals("OSM_DISCOVERY_TIMEOUT", result.errorCode());
    }

    // ---------- Large city + few leads test ----------

    @Test
    void largeCityFewLeadsUsesSmallLimit() {
        var tile = new OpenStreetMapProvider.Tile(-24.0, -47.0, -23.0, -46.0, 0.0);
        OpenStreetMapProvider provider = new OpenStreetMapProvider(null, mapper, null, null, new Normalizer(), null);
        
        String structuredQuery = provider.buildStructuredQuery("barbearia", tile, 3);
        String fallbackQuery = provider.buildNameFallbackQuery("barbearia", tile, 3);

        assertTrue(structuredQuery.contains("out center tags 3"), "structured: " + structuredQuery);
        assertTrue(fallbackQuery.contains("out center tags 3"), "fallback: " + fallbackQuery);
        
        assertFalse(structuredQuery.contains("80"));
        assertFalse(fallbackQuery.contains("80"));
    }

    @Test
    void invalidBboxFallsBackToCenter() {
        var tile = new OpenStreetMapProvider.Tile(-23.73, -46.81, -23.37, -46.45, 0.0);
        OpenStreetMapProvider provider = new OpenStreetMapProvider(null, mapper, null, null, new Normalizer(), null);
        String structuredQuery = provider.buildStructuredQuery("barbearia", tile, 10);

        assertTrue(structuredQuery.contains("-23.730000,-46.810000,-23.370000,-46.450000"), structuredQuery);
    }
}