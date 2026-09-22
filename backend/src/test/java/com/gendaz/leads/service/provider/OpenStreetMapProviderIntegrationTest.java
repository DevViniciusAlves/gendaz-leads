package com.gendaz.leads.service.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gendaz.leads.domain.LeadCandidate;
import com.gendaz.leads.util.Normalizer;
import com.gendaz.leads.util.SsrfGuard;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import static org.mockito.Mockito.mock;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

import static org.junit.jupiter.api.Assertions.*;

class OpenStreetMapProviderIntegrationTest {

    private RestClient.Builder restClientBuilder;
    private ObjectMapper objectMapper;
    private Normalizer normalizer;
    private SsrfGuard ssrfGuard;

    private MutableClock clock;

    @BeforeEach
    void setUp() {
        restClientBuilder = mock(RestClient.Builder.class);
        objectMapper = new ObjectMapper();
        normalizer = new Normalizer();
        ssrfGuard = new SsrfGuard();
        clock = new MutableClock();
    }

    private TestProvider createProvider() {
        TestProvider provider = new TestProvider(
                restClientBuilder, objectMapper, normalizer, ssrfGuard
        );
        provider.setCooldownSleeper(millis -> {
            provider.sleptMs.addAndGet(millis);
            clock.advanceMillis(millis);
        });
        // Manually initialize the semaphore (don't call init() which would overwrite circuit breaker)
        provider.overpassSemaphore = new java.util.concurrent.Semaphore(1, true);
        // Use circuit breaker with fake clock
        provider.circuitBreaker = new OpenStreetMapProvider.OverpassCircuitBreaker(30, clock);
        // Set config values for tests
        provider.failureSplitThresholdKm = 6.0;
        provider.timeoutCooldownSeconds = 30;
        provider.connectionRefusedCooldownSeconds = 30;
        provider.rateLimitCooldownSeconds = 60;
        provider.adaptiveMaxDepth = 12;
        provider.adaptiveMinEdgeKm = 1.0;
        return provider;
    }

    private static final class MutableClock implements LongSupplier {
        private final AtomicLong now = new AtomicLong(1_000_000L);

        @Override
        public long getAsLong() {
            return now.get();
        }

        void advanceMillis(long millis) {
            now.addAndGet(millis);
        }
    }

    // Test provider that allows scripting responses
    static final class TestProvider extends OpenStreetMapProvider {
        private final Deque<Object> scriptedResponses = new ArrayDeque<>();
        private int requests = 0;
        private final AtomicLong sleptMs = new AtomicLong();

        TestProvider(RestClient.Builder builder, ObjectMapper objectMapper,
                     Normalizer normalizer, SsrfGuard ssrfGuard) {
            super(builder, objectMapper, normalizer, ssrfGuard);
        }

        void enqueueResponse(String response) {
            scriptedResponses.addLast(response);
        }

        void enqueueFailure(RuntimeException failure) {
            scriptedResponses.addLast(failure);
        }

        int requests() {
            return requests;
        }

        long sleptMs() {
            return sleptMs.get();
        }

        @Override
        String executeOverpassRequest(String url, String query, int effectiveTimeoutMs) {
            requests++;
            if (scriptedResponses.isEmpty()) {
                throw new IllegalStateException("No scripted response available");
            }
            Object next = scriptedResponses.removeFirst();
            if (next instanceof RuntimeException failure) {
                throw failure;
            }
            return (String) next;
        }
    }

    // Exception factories
    private static RuntimeException readTimeout() {
        return new org.springframework.web.client.ResourceAccessException(
                "I/O error",
                new java.net.SocketTimeoutException("Read timed out")
        );
    }

    private static RuntimeException connectionRefused() {
        return new org.springframework.web.client.ResourceAccessException(
                "I/O error",
                new java.net.ConnectException("Connection refused")
        );
    }

private static RuntimeException gatewayTimeout() {
        return org.springframework.web.client.HttpServerErrorException.create(
                org.springframework.http.HttpStatus.GATEWAY_TIMEOUT,
                "Gateway Timeout",
                org.springframework.http.HttpHeaders.EMPTY,
                new byte[0],
                java.nio.charset.StandardCharsets.UTF_8
        );
    }

    private static GeoScope createScope() {
        return new GeoScope(-15.6, -56.1, "Cuiabá", "MT", "Brasil", "br",
                -15.7, -56.2, -15.5, -56.0, true, "relation", 333734L);
    }

    private static SearchRegion createLargeRegion() {
        // Region > 6km (maxEdgeKm ~ 10km)
        return SearchRegion.root(-15.7, -56.2, -15.5, -56.0, -15.6, -56.1);
    }

    private static SearchRegion createSmallRegion() {
        // Region <= 6km (maxEdgeKm ~ 2km) - use 0.02 degree ≈ 2.2km
        return SearchRegion.root(-15.61, -56.11, -15.59, -56.09, -15.6, -56.1);
    }

    private static DiscoveryBudget createBudget() {
        return DiscoveryBudget.forTarget(3, 90000, 3000, 180000);
    }

    private static String emptyResponse() {
        return "{\"elements\":[]}";
    }

    @Test
    void timeoutInLargeRegionReturnsSplitRequired() {
        TestProvider provider = createProvider();

        // Large region > 6km, read timeout should trigger split
        provider.enqueueFailure(readTimeout());

        AreaQueryResult result = provider.queryRegionWithStrategy(
                createScope(),
                GeographicStrategy.BBOX_FALLBACK,
                "barbearia",
                createLargeRegion(),
                AreaQueryPhase.STRUCTURED,
                20,
                createBudget(),
                1L
        );

        assertEquals(AreaQueryResult.Outcome.SPLIT_REQUIRED, result.outcome());
        assertEquals(1, provider.requests());
    }

    @Test
    void gatewayTimeoutInLargeRegionReturnsSplitRequired() {
        TestProvider provider = createProvider();

        provider.enqueueFailure(gatewayTimeout());

        AreaQueryResult result = provider.queryRegionWithStrategy(
                createScope(),
                GeographicStrategy.BBOX_FALLBACK,
                "barbearia",
                createLargeRegion(),
                AreaQueryPhase.STRUCTURED,
                20,
                createBudget(),
                1L
        );

        assertEquals(AreaQueryResult.Outcome.SPLIT_REQUIRED, result.outcome());
        assertEquals(1, provider.requests());
    }

    @Test
    void timeoutInSmallRegionFailsOverToNextEndpoint() {
        TestProvider provider = createProvider();

        // Small region <= 6km, timeout should failover
        provider.enqueueFailure(readTimeout());
        provider.enqueueResponse(emptyResponse());

        AreaQueryResult result = provider.queryRegionWithStrategy(
                createScope(),
                GeographicStrategy.BBOX_FALLBACK,
                "barbearia",
                createSmallRegion(),
                AreaQueryPhase.STRUCTURED,
                20,
                createBudget(),
                1L
        );

        assertEquals(AreaQueryResult.Outcome.SUCCESS, result.outcome());
        assertEquals(2, provider.requests());
    }

    @Test
    void connectionRefusedDoesNotSplitRegion() {
        TestProvider provider = createProvider();

        // Even with large region, connection refused should failover not split
        provider.enqueueFailure(connectionRefused());
        provider.enqueueResponse(emptyResponse());

        AreaQueryResult result = provider.queryRegionWithStrategy(
                createScope(),
                GeographicStrategy.BBOX_FALLBACK,
                "barbearia",
                createLargeRegion(),
                AreaQueryPhase.STRUCTURED,
                20,
                createBudget(),
                1L
        );

        assertEquals(AreaQueryResult.Outcome.SUCCESS, result.outcome());
        assertEquals(2, provider.requests());
    }

    @Test
    void allEndpointsFailThenWaitForCooldownAndRetry() {
        TestProvider provider = createProvider();

        // Small region so no split
        // A: read timeout
        // B: 504
        // C: connection refused
        // Then A recovers after cooldown
        provider.enqueueFailure(readTimeout());
        provider.enqueueFailure(gatewayTimeout());
        provider.enqueueFailure(connectionRefused());
        provider.enqueueResponse(emptyResponse());

        AreaQueryResult result = provider.queryRegionWithStrategy(
                createScope(),
                GeographicStrategy.BBOX_FALLBACK,
                "barbearia",
                createSmallRegion(),
                AreaQueryPhase.STRUCTURED,
                20,
                createBudget(),
                1L
        );

        assertEquals(AreaQueryResult.Outcome.SUCCESS, result.outcome());
        assertEquals(4, provider.requests());
        assertTrue(provider.sleptMs() > 0L, "Should have waited for cooldown");
    }

    @Test
    void budgetInsufficientForCooldownReturnsTimeout() {
        TestProvider provider = createProvider();

        // Very small budget
        DiscoveryBudget smallBudget = DiscoveryBudget.forTarget(1, 100, 10, 200);

        provider.enqueueFailure(readTimeout());
        provider.enqueueFailure(gatewayTimeout());
        provider.enqueueFailure(connectionRefused());

        AreaQueryResult result = provider.queryRegionWithStrategy(
                createScope(),
                GeographicStrategy.BBOX_FALLBACK,
                "barbearia",
                createSmallRegion(),
                AreaQueryPhase.STRUCTURED,
                20,
                smallBudget,
                1L
        );

        assertEquals(AreaQueryResult.Outcome.INFRA_UNAVAILABLE, result.outcome());
        assertEquals("OSM_DISCOVERY_TIMEOUT", result.errorCode());
    }
}