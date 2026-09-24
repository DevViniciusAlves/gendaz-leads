package com.gendaz.leads.service.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gendaz.leads.exception.ApiException;
import com.gendaz.leads.util.Normalizer;
import com.gendaz.leads.util.SsrfGuard;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.stubbing.Answer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OpenStreetMapProviderResolveScopeTest {

    @Mock
    RestClient.Builder builder;
    @Mock
    RestClient restClient;
    @Mock
    RestClient.RequestHeadersUriSpec uriSpec;
    @Mock
    RestClient.ResponseSpec responseSpec;

    private final List<Long> slept = new ArrayList<>();
    private OpenStreetMapProvider provider;

    @BeforeEach
    void setUp() throws Exception {
        lenient().when(builder.requestFactory(any())).thenReturn(builder);
        lenient().when(builder.defaultHeader(anyString(), anyString())).thenReturn(builder);
        lenient().when(builder.build()).thenReturn(restClient);
        lenient().when(restClient.get()).thenReturn(uriSpec);
        lenient().when(uriSpec.uri(any(Function.class))).thenAnswer((Answer<RestClient.RequestHeadersUriSpec>) inv -> uriSpec);
        lenient().when(uriSpec.retrieve()).thenReturn(responseSpec);

        provider = new OpenStreetMapProvider(
                builder, new ObjectMapper(), new Normalizer(), new SsrfGuard());
        provider.setCooldownSleeper(slept::add);

        setField("nominatimMaxAttempts", 2);
        setField("nominatimTimeoutMs", 8000);
        setField("nominatimCacheSeconds", 3600);
        setField("nominatimMinIntervalMs", 0L);
        setField("nominatimBaseUrl", "https://nominatim.openstreetmap.org");
        setField("nominatim429BackoffMs", 3000L);
    }

    private void setField(String name, Object value) throws Exception {
        Field field = OpenStreetMapProvider.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(provider, value);
    }

    private static String cuiabaJson() {
        return "[{\"lat\":\"-15.6\",\"lon\":\"-56.1\","
                + "\"osm_type\":\"relation\",\"osm_id\":333734,"
                + "\"address\":{\"city\":\"Cuiab\u00e1\",\"state\":\"Mato Grosso\","
                + "\"country\":\"Brasil\",\"country_code\":\"br\"},"
                + "\"boundingbox\":[\"-16.0\",\"-15.0\",\"-57.0\",\"-55.0\"]}]";
    }

    private static HttpClientErrorException tooManyRequests(String retryAfter) {
        HttpHeaders headers = new HttpHeaders();
        if (retryAfter != null) {
            headers.set("Retry-After", retryAfter);
        }
        return HttpClientErrorException.create(
                HttpStatus.TOO_MANY_REQUESTS, "Too Many Requests",
                headers, null, StandardCharsets.UTF_8);
    }

    private LeadDiscoveryRequest cuiabaRequest() {
        return new LeadDiscoveryRequest(1L, "nail designer", "Cuiabá", "Brasil", 50);
    }

    @Test
    void rateLimited429SleepsBeforeRetryAndSucceeds() {
        when(responseSpec.body(String.class))
                .thenThrow(tooManyRequests(null))
                .thenReturn(cuiabaJson());

        GeoScope scope = provider.resolveScope(cuiabaRequest(), DiscoveryBudget.unlimited());

        assertEquals("relation", scope.osmType());
        assertEquals(333734L, scope.osmId());
        assertEquals(List.of(3000L), slept);
        verify(responseSpec, times(2)).body(String.class);
    }

    @Test
    void retryAfterHeaderDelayIsUsed() throws Exception {
        setField("nominatim429BackoffMs", 1000L);
        when(responseSpec.body(String.class))
                .thenThrow(tooManyRequests("2"))
                .thenReturn(cuiabaJson());

        GeoScope scope = provider.resolveScope(cuiabaRequest(), DiscoveryBudget.unlimited());

        assertEquals(333734L, scope.osmId());
        assertEquals(List.of(2000L), slept);
    }

    @Test
    void lastAttemptDoesNotSleepAgain() throws Exception {
        setField("nominatimMaxAttempts", 1);
        when(responseSpec.body(String.class))
                .thenThrow(tooManyRequests(null));

        ApiException ex = assertThrows(ApiException.class, () ->
                provider.resolveScope(cuiabaRequest(), DiscoveryBudget.unlimited()));

        assertEquals("OSM_GEOCODE_ERROR", ex.getCode());
        assertTrue(slept.isEmpty(), "ultima tentativa nao deve dormir novamente");
    }

    @Test
    void nonRetryableDoesNotSleep() {
        when(responseSpec.body(String.class))
                .thenThrow(HttpClientErrorException.create(
                        HttpStatus.BAD_REQUEST, "Bad Request",
                        HttpHeaders.EMPTY, null, StandardCharsets.UTF_8));

        ApiException ex = assertThrows(ApiException.class, () ->
                provider.resolveScope(cuiabaRequest(), DiscoveryBudget.unlimited()));

        assertEquals("OSM_GEOCODE_ERROR", ex.getCode());
        assertTrue(slept.isEmpty(), "erro nao recuperavel nao deve dormir");
    }
}
