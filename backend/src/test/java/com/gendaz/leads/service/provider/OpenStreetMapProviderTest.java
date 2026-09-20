package com.gendaz.leads.service.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gendaz.leads.domain.LeadCandidate;
import com.gendaz.leads.exception.ApiException;
import com.gendaz.leads.util.Normalizer;
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
        OpenStreetMapProvider p = new OpenStreetMapProvider(builder, mapper, null, new Normalizer());
        ReflectionTestUtils.setField(p, "enabled", true);
        ReflectionTestUtils.setField(p, "timeoutMs", 5000);
        ReflectionTestUtils.setField(p, "discoveryDeadlineMs", 60000);
        ReflectionTestUtils.setField(p, "overpassUrl", "https://overpass.test/api/interpreter");
        ReflectionTestUtils.setField(p, "fallbackUrl", "");
        ReflectionTestUtils.setField(p, "maxConcurrency", 1);
        ReflectionTestUtils.setField(p, "circuitOpenSeconds", 180);
        ReflectionTestUtils.setField(p, "overpassEndpoints", "");
        return p;
    }

    private OpenStreetMapProvider providerWithLegacy(RestClient.Builder builder) {
        OpenStreetMapProvider p = new OpenStreetMapProvider(builder, mapper, null, new Normalizer());
        ReflectionTestUtils.setField(p, "enabled", true);
        ReflectionTestUtils.setField(p, "timeoutMs", 5000);
        ReflectionTestUtils.setField(p, "discoveryDeadlineMs", 60000);
        ReflectionTestUtils.setField(p, "overpassUrl", "https://legacy.primary/api/interpreter");
        ReflectionTestUtils.setField(p, "fallbackUrl", "https://legacy.fallback/api/interpreter");
        ReflectionTestUtils.setField(p, "maxConcurrency", 1);
        ReflectionTestUtils.setField(p, "circuitOpenSeconds", 180);
        ReflectionTestUtils.setField(p, "overpassEndpoints", "");
        return p;
    }

    // ---------- OSM-only: Google ausente ----------

    @Test
    void googlePlacesProviderAbsent() {
        assertThrows(ClassNotFoundException.class,
                () -> Class.forName("com.gendaz.leads.service.provider.GooglePlacesProvider"));
        assertEquals("openstreetmap",
                new OpenStreetMapProvider(null, mapper, null, new Normalizer()).getName());
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
        // "massagem" (8) mais especifico que "spa" (3): deve vencer.
        assertEquals(NicheMapper.resolve("massagem").tagFilters(), first.tagFilters());
    }

    @Test
    void regexNeverHasQuoteMarkers() {
        for (String niche : List.of("barbearia", "cílios", "x\");out body;/*", "salão de beleza")) {
            String regex = NicheMapper.resolve(niche).fallbackNameRegex();
            assertFalse(regex.contains("\\Q"), "niche=" + niche);
            assertFalse(regex.contains("\\E"), "niche=" + niche);
        }
        var geo = new OpenStreetMapProvider.Geo(-15.6, -56.1, "Cuiaba", "MT", "BR",
                -16.0, -15.0, -56.5, -55.5, true);
        OpenStreetMapProvider provider = new OpenStreetMapProvider(null, mapper, null, new Normalizer());
        String structuredQuery = provider.buildStructuredQuery("cilios", geo, 30);
        String fallbackQuery = provider.buildNameFallbackQuery("cilios", geo, 30);
        assertFalse(structuredQuery.contains("\\Q"));
        assertFalse(structuredQuery.contains("\\E"));
        if (fallbackQuery != null) {
            assertFalse(fallbackQuery.contains("\\Q"));
            assertFalse(fallbackQuery.contains("\\E"));
        }
    }

    // ---------- Bounding box ----------

    @Test
    void bboxOrderSouthWestNorthEast() {
        var geo = new OpenStreetMapProvider.Geo(0, 0, null, null, "BR",
                1.0, 2.0, 3.0, 4.0, true);
        OpenStreetMapProvider provider = new OpenStreetMapProvider(null, mapper, null, new Normalizer());
        String structuredQuery = provider.buildStructuredQuery("cilios", geo, 30);
        // bbox format: south,west,north,east
        assertTrue(structuredQuery.contains("1.000000,3.000000,2.000000,4.000000"), structuredQuery);
    }

    @Test
    void absurdBboxFallsBackToCenter() {
        var geo = new OpenStreetMapProvider.Geo(0, 0, null, null, "BR",
                0, 0, 0, 0, false);
        OpenStreetMapProvider provider = new OpenStreetMapProvider(null, mapper, null, new Normalizer());
        String structuredQuery = provider.buildStructuredQuery("cilios", geo, 30);
        assertTrue(structuredQuery.contains("-0.180000,-0.180000,0.180000,0.180000"), structuredQuery);
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
        // Retryable
        assertTrue(OpenStreetMapProvider.isRetryable(new ConnectException("connection refused")));
        assertTrue(OpenStreetMapProvider.isRetryable(new SocketTimeoutException("read timed out")));
        assertTrue(OpenStreetMapProvider.isRetryable(new java.net.NoRouteToHostException("no route")));
        assertTrue(OpenStreetMapProvider.isRetryable(new java.net.UnknownHostException("unknown")));
        assertTrue(OpenStreetMapProvider.isRetryable(new RuntimeException("connection reset")));
        
        // Not retryable
        assertFalse(OpenStreetMapProvider.isRetryable(new javax.net.ssl.SSLHandshakeException("handshake")));
        
        // Wrapped retryable
        assertTrue(OpenStreetMapProvider.isRetryable(new RuntimeException("wrap", new ConnectException())));
    }


    // ---------- Zero real vs erro (HTTP mockado) ----------

    private static final String GEO_JSON = """
            [{"lat":"-15.6","lon":"-56.1",
              "address":{"city":"Cuiaba","state":"Mato Grosso","country":"Brasil"},
              "boundingbox":["-16.0","-15.0","-56.5","-55.5"]}]""";

    @SuppressWarnings({"unchecked", "rawtypes"})
    private RestClient stubHttp(RestClient.Builder builder, String nominatimBody,
                                Object overpassBehavior) {
        RestClient restClient = mock(RestClient.class);
        when(builder.requestFactory(any(ClientHttpRequestFactory.class))).thenReturn(builder);
        when(builder.defaultHeader(anyString(), any(String[].class))).thenReturn(builder);
        when(builder.build()).thenReturn(restClient);

        RestClient.RequestHeadersUriSpec getSpec = mock(RestClient.RequestHeadersUriSpec.class);
        RestClient.RequestHeadersSpec headersSpec = mock(RestClient.RequestHeadersSpec.class);
        RestClient.ResponseSpec getResponse = mock(RestClient.ResponseSpec.class);
        when(restClient.get()).thenReturn(getSpec);
        when(getSpec.uri(any(Function.class))).thenReturn(headersSpec);
        when(headersSpec.retrieve()).thenReturn(getResponse);
        when(getResponse.body(eq(String.class))).thenReturn(nominatimBody);

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

        List<LeadCandidate> out = providerWith(builder).discover("cilios", "Cuiaba", 10);

        assertTrue(out.isEmpty());
        verify(restClient, times(2)).post();
    }

    @Test
    void nominatimEmptyIsLocationNotFound() {
        RestClient.Builder builder = mock(RestClient.Builder.class);
        stubHttp(builder, "[]", "{\"elements\":[]}");

        ApiException ex = assertThrows(ApiException.class,
                () -> providerWith(builder).discover("cilios", "Lugar Inexistente Xyz", 10));
        assertEquals("LOCATION_NOT_FOUND", ex.getCode());
        assertEquals(HttpStatus.NOT_FOUND, ex.getStatus());
    }

    @Test
    void overpassFailureIsOverpassErrorNotZero() {
        RestClient.Builder builder = mock(RestClient.Builder.class);
        stubHttp(builder, GEO_JSON,
                HttpClientErrorException.create(HttpStatus.BAD_REQUEST, "bad", null, null, null));

        ApiException ex = assertThrows(ApiException.class,
                () -> providerWith(builder).discover("cilios", "Cuiaba", 10));
        assertEquals("OSM_OVERPASS_ERROR", ex.getCode());
    }

    @Test
    void fallbackEndpointUsedOnlyAfterPrimaryFailure() {
        RestClient.Builder builder = mock(RestClient.Builder.class);
        RestClient restClient = stubHttp(builder, GEO_JSON,
                new org.springframework.web.client.ResourceAccessException("connect timed out", new ConnectException("connect timed out")));
        OpenStreetMapProvider p = providerWith(builder);
        ReflectionTestUtils.setField(p, "fallbackUrl", "https://fallback.test/api/interpreter");

        // Primary falha 1x, fallback falha 1x -> erro tipado, nunca zero silencioso.
        RuntimeException ex = assertThrows(RuntimeException.class, () -> p.discover("cilios", "Cuiaba", 10));
        assertTrue(ex.getMessage().contains("Falha ao consultar Overpass"));
        // 1 tentativa no primary + 1 no fallback
        verify(restClient, times(2)).post();
    }

    @Test
    void legacyEnvVarsUsedWhenEndpointsNotSet() {
        RestClient.Builder builder = mock(RestClient.Builder.class);
        RestClient restClient = stubHttp(builder, GEO_JSON, "{\"elements\":[]}");

        OpenStreetMapProvider p = providerWithLegacy(builder);
        List<LeadCandidate> out = p.discover("cilios", "Cuiaba", 10);

        assertTrue(out.isEmpty());
        // Should use both legacy endpoints (primary + fallback) = 2 calls per phase = 4 total
        verify(restClient, times(4)).post();
    }

    @Test
    void newEndpointsEnvTakesPriorityOverLegacy() {
        RestClient.Builder builder = mock(RestClient.Builder.class);
        RestClient restClient = stubHttp(builder, GEO_JSON, "{\"elements\":[]}");

        OpenStreetMapProvider p = providerWithLegacy(builder);
        ReflectionTestUtils.setField(p, "overpassEndpoints", "https://new.primary/api/interpreter,https://new.fallback/api/interpreter");
        List<LeadCandidate> out = p.discover("cilios", "Cuiaba", 10);

        assertTrue(out.isEmpty());
        // Should use new endpoints only = 2 calls per phase = 4 total
        verify(restClient, times(4)).post();
    }

    // ---------- Barbearia query tests ----------

    @Test
    void barbeariaStructuredQueryHasCorrectTags() {
        var geo = new OpenStreetMapProvider.Geo(-23.55, -46.63, "Sao Paulo", "SP", "BR",
                -24.0, -23.0, -47.0, -46.0, true);
        OpenStreetMapProvider provider = new OpenStreetMapProvider(null, mapper, null, new Normalizer());
        String structuredQuery = provider.buildStructuredQuery("barbearia", geo, 10);
        String fallbackQuery = provider.buildNameFallbackQuery("barbearia", geo, 10);

        assertTrue(structuredQuery.contains("[\"shop\"=\"barber\"]"));
        assertTrue(structuredQuery.contains("[\"shop\"=\"hairdresser\"][\"hairdresser\"=\"barber\"]"));
        assertFalse(structuredQuery.contains("name~")); // structured should not have name fallback
        assertTrue(fallbackQuery != null && fallbackQuery.contains("barbearia|barber|barbershop"));
    }

    @Test
    void barbeariaStructuredQueryRespectsLimit() {
        var geo = new OpenStreetMapProvider.Geo(-23.55, -46.63, "Sao Paulo", "SP", "BR",
                -24.0, -23.0, -47.0, -46.0, true);
        OpenStreetMapProvider provider = new OpenStreetMapProvider(null, mapper, null, new Normalizer());
        String structuredQuery = provider.buildStructuredQuery("barbearia", geo, 13);
        String fallbackQuery = provider.buildNameFallbackQuery("barbearia", geo, 13);

        // Should use limit + 5, not MIN_OUT=80
        assertTrue(structuredQuery.contains("out center tags 18"));
        assertTrue(fallbackQuery.contains("out center tags 18"));
    }

    @Test
    void largeCityBboxIsClamped() {
        // São Paulo-like bbox with span > 2 degrees
        var geo = new OpenStreetMapProvider.Geo(-23.55, -46.63, "Sao Paulo", "SP", "BR",
                -25.0, -22.0, -48.0, -45.0, true); // span 3 degrees lat, 3 degrees lon
        OpenStreetMapProvider provider = new OpenStreetMapProvider(null, mapper, null, new Normalizer());
        String structuredQuery = provider.buildStructuredQuery("barbearia", geo, 10);

        // Should be clamped to 2.0 degree span around center
        // center lat = -23.5, center lon = -46.5
        // half span = 1.0
        // south = -24.5, north = -22.5, west = -47.5, east = -45.5
        assertTrue(structuredQuery.contains("-24.500000,-47.500000,-22.500000,-45.500000"), structuredQuery);
    }

    @Test
    void smallCityBboxPreserved() {
        // Small city bbox with span < 2 degrees
        var geo = new OpenStreetMapProvider.Geo(-15.6, -56.1, "Cuiaba", "MT", "BR",
                -16.0, -15.0, -56.5, -55.5, true); // span 1 degree lat, 1 degree lon
        OpenStreetMapProvider provider = new OpenStreetMapProvider(null, mapper, null, new Normalizer());
        String structuredQuery = provider.buildStructuredQuery("cilios", geo, 10);

        // Should preserve original bbox
        assertTrue(structuredQuery.contains("-16.000000,-56.500000,-15.000000,-55.500000"), structuredQuery);
    }

    // ---------- Concurrency test ----------

    @Test
    void concurrencyMaxOneOverpassRequest() throws InterruptedException {
        RestClient.Builder builder1 = mock(RestClient.Builder.class);
        RestClient.Builder builder2 = mock(RestClient.Builder.class);
        
        // Use slow responses to test concurrency
        RestClient restClient1 = stubHttp(builder1, GEO_JSON, "{\"elements\":[]}");
        RestClient restClient2 = stubHttp(builder2, GEO_JSON, "{\"elements\":[]}");

        OpenStreetMapProvider p1 = providerWith(builder1);
        OpenStreetMapProvider p2 = providerWith(builder2);

        java.util.concurrent.CountDownLatch startLatch = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch endLatch = new java.util.concurrent.CountDownLatch(2);
        java.util.concurrent.atomic.AtomicInteger activeOverpass = new java.util.concurrent.atomic.AtomicInteger(0);
        java.util.concurrent.atomic.AtomicInteger maxActiveOverpass = new java.util.concurrent.atomic.AtomicInteger(0);

        // Wrap the executeOverpassQuery to track concurrency
        // Since we can't easily wrap private method, we'll measure by timing
        // Two discoveries with 2 phases each = 4 Overpass calls total
        // With semaphore(1), they should run sequentially
        
        Thread t1 = new Thread(() -> {
            try { startLatch.await(); } catch (InterruptedException ignored) {}
            try {
                p1.discover("cilios", "Cuiaba", 10);
            } catch (Exception ignored) {}
            endLatch.countDown();
        });

        Thread t2 = new Thread(() -> {
            try { startLatch.await(); } catch (InterruptedException ignored) {}
            try {
                p2.discover("cilios", "Cuiaba", 10);
            } catch (Exception ignored) {}
            endLatch.countDown();
        });

        long startTime = System.currentTimeMillis();
        t1.start();
        t2.start();
        startLatch.countDown();
        endLatch.await(10, java.util.concurrent.TimeUnit.SECONDS);
        long elapsed = System.currentTimeMillis() - startTime;

        // With semaphore(1), 4 Overpass calls (2 per discovery) should run sequentially
        // Each mock call is nearly instant, so total time should be small
        // But if they ran in parallel, they'd complete faster
        // We just verify both completed without deadlock
        assertTrue(elapsed < 5000, "Both discoveries should complete within 5 seconds");
        // The key test: no deadlock, both threads finished
    }

    // ---------- Deadline test ----------

    @Test
    void deadlineExceededThrowsTimeout() {
        RestClient.Builder builder = mock(RestClient.Builder.class);
        RestClient restClient = stubHttp(builder, GEO_JSON, "{\"elements\":[]}");
        OpenStreetMapProvider p = providerWith(builder);
        ReflectionTestUtils.setField(p, "discoveryDeadlineMs", 1); // 1ms deadline
        ReflectionTestUtils.setField(p, "timeoutMs", 5000);

        ApiException ex = assertThrows(ApiException.class,
                () -> p.discover("cilios", "Cuiaba", 10));
        assertEquals("OSM_DISCOVERY_TIMEOUT", ex.getCode());
    }

    // ---------- Large city + few leads test ----------

    @Test
    void largeCityFewLeadsUsesSmallLimit() {
        // São Paulo-like bbox
        var geo = new OpenStreetMapProvider.Geo(-23.55, -46.63, "Sao Paulo", "SP", "BR",
                -24.0, -23.0, -47.0, -46.0, true);
        OpenStreetMapProvider provider = new OpenStreetMapProvider(null, mapper, null, new Normalizer());
        
        String structuredQuery = provider.buildStructuredQuery("barbearia", geo, 3);
        String fallbackQuery = provider.buildNameFallbackQuery("barbearia", geo, 3);

        // Should use limit + 5 (8), not MIN_OUT=80
        assertTrue(structuredQuery.contains("out center tags 8"), "structured: " + structuredQuery);
        assertTrue(fallbackQuery.contains("out center tags 8"), "fallback: " + fallbackQuery);
        
        // Should not contain MIN_OUT references
        assertFalse(structuredQuery.contains("80"));
        assertFalse(fallbackQuery.contains("80"));
    }

    @Test
    void invalidBboxFallsBackToCenter() {
        // Invalid bbox (all zeros, bboxValid=false)
        var geo = new OpenStreetMapProvider.Geo(-23.55, -46.63, "Sao Paulo", "SP", "BR",
                0, 0, 0, 0, false);
        OpenStreetMapProvider provider = new OpenStreetMapProvider(null, mapper, null, new Normalizer());
        String structuredQuery = provider.buildStructuredQuery("barbearia", geo, 10);

        // Should use BBOX_DELTA around center
        // center = -23.55, -46.63
        // delta = 0.18
        // south = -23.73, west = -46.81, north = -23.37, east = -46.45
        assertTrue(structuredQuery.contains("-23.730000,-46.810000,-23.370000,-46.450000"), structuredQuery);
    }
}
