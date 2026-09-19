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
        ReflectionTestUtils.setField(p, "overpassUrl", "https://overpass.test/api/interpreter");
        ReflectionTestUtils.setField(p, "fallbackUrl", "");
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
        String q = new OpenStreetMapProvider(null, mapper, null, new Normalizer())
                .buildOverpassQuery("cilios", geo, 30);
        assertFalse(q.contains("\\Q"));
        assertFalse(q.contains("\\E"));
    }

    // ---------- Bounding box ----------

    @Test
    void bboxOrderSouthWestNorthEast() {
        var geo = new OpenStreetMapProvider.Geo(0, 0, null, null, "BR",
                1.0, 2.0, 3.0, 4.0, true);
        String q = new OpenStreetMapProvider(null, mapper, null, new Normalizer())
                .buildOverpassQuery("cilios", geo, 30);
        assertTrue(q.contains("1.000000,3.000000,2.000000,4.000000"), q);
    }

    @Test
    void absurdBboxFallsBackToCenter() {
        var geo = new OpenStreetMapProvider.Geo(0, 0, null, null, "BR",
                0, 0, 0, 0, false);
        String q = new OpenStreetMapProvider(null, mapper, null, new Normalizer())
                .buildOverpassQuery("cilios", geo, 30);
        assertTrue(q.contains("-0.180000,-0.180000,0.180000,0.180000"), q);
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
    void networkFailureIsRetryable() {
        assertTrue(OpenStreetMapProvider.isRetryable(new ResourceAccessException("connect timed out")));
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
        verify(restClient, times(1)).post();
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
                new ResourceAccessException("connect timed out"));
        OpenStreetMapProvider p = providerWith(builder);
        ReflectionTestUtils.setField(p, "fallbackUrl", "https://fallback.test/api/interpreter");

        // Primary falha 3x (retry), fallback tambem falha -> erro tipado, nunca zero silencioso.
        ApiException ex = assertThrows(ApiException.class, () -> p.discover("cilios", "Cuiaba", 10));
        assertEquals("OSM_OVERPASS_ERROR", ex.getCode());
        // 3 tentativas no primary + 3 no fallback
        verify(restClient, times(6)).post();
    }
}
