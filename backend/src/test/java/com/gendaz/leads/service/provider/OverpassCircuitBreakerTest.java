package com.gendaz.leads.service.provider;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

import static org.junit.jupiter.api.Assertions.*;

class OverpassCircuitBreakerTest {

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

    @Test
    void circuitBreakerOpensOnFailureAndClosesOnSuccess() {
        MutableClock clock = new MutableClock();

        OpenStreetMapProvider.OverpassCircuitBreaker cb =
                new OpenStreetMapProvider.OverpassCircuitBreaker(1, clock);

        // Initially closed
        assertTrue(cb.tryAcquire("host1"));

        // Record failure
        cb.recordFailure("host1");

        // Should be open now
        assertFalse(cb.tryAcquire("host1"));

        // Advance clock past cooldown
        clock.advanceMillis(1_001);

        // Should be half-open, allow one probe
        assertTrue(cb.tryAcquire("host1"));
        // Second probe should be rejected
        assertFalse(cb.tryAcquire("host1"));

        // Record success on probe
        cb.recordSuccess("host1");

        // Should be closed again
        assertTrue(cb.tryAcquire("host1"));
    }

    @Test
    void circuitBreakerRateLimitedOpensWithCustomCooldown() {
        MutableClock clock = new MutableClock();

        OpenStreetMapProvider.OverpassCircuitBreaker cb =
                new OpenStreetMapProvider.OverpassCircuitBreaker(30, clock);

        assertTrue(cb.tryAcquire("host1"));

        cb.recordRateLimited("host1", 10); // 10 seconds cooldown

        assertFalse(cb.tryAcquire("host1"));

        clock.advanceMillis(10_001);

        assertTrue(cb.tryAcquire("host1"));
    }

    @Test
    void circuitBreakerHalfOpenOnlyAllowsOneProbe() {
        MutableClock clock = new MutableClock();

        OpenStreetMapProvider.OverpassCircuitBreaker cb =
                new OpenStreetMapProvider.OverpassCircuitBreaker(1, clock);

        cb.recordFailure("host1");

        clock.advanceMillis(1_001);

        // First probe allowed
        assertTrue(cb.tryAcquire("host1"));
        // Second probe rejected
        assertFalse(cb.tryAcquire("host1"));

        // Release probe without success
        cb.releaseProbe("host1");

        // Should allow another probe
        assertTrue(cb.tryAcquire("host1"));
    }

    @Test
    void getMinWaitMsReturnsZeroWhenClosed() {
        MutableClock clock = new MutableClock();

        OpenStreetMapProvider.OverpassCircuitBreaker cb =
                new OpenStreetMapProvider.OverpassCircuitBreaker(30, clock);

        assertTrue(cb.tryAcquire("host1"));

        // Should return 0 because host is closed
        assertEquals(0, cb.getMinWaitMsForAvailableEndpoint(java.util.List.of("host1")));
    }

    @Test
    void getMinWaitMsReturnsWaitTimeWhenOpen() {
        MutableClock clock = new MutableClock();

        OpenStreetMapProvider.OverpassCircuitBreaker cb =
                new OpenStreetMapProvider.OverpassCircuitBreaker(30, clock);

        cb.recordFailure("host1");

        long wait = cb.getMinWaitMsForAvailableEndpoint(java.util.List.of("host1"));
        assertTrue(wait > 0 && wait <= 30000);
    }

    @Test
    void getMinWaitMsReturnsMinAcrossMultipleHosts() {
        MutableClock clock = new MutableClock();

        OpenStreetMapProvider.OverpassCircuitBreaker cb =
                new OpenStreetMapProvider.OverpassCircuitBreaker(30, clock);

        cb.recordRateLimited("host1", 10); // 10 seconds
        cb.recordRateLimited("host2", 20); // 20 seconds

        long wait = cb.getMinWaitMsForAvailableEndpoint(java.util.List.of("host1", "host2"));
        assertTrue(wait > 0 && wait <= 10000, "Should return minimum wait (host1: 10s)");
    }

    @Test
    void getMinWaitMsReturnsZeroWhenAnyHostClosed() {
        MutableClock clock = new MutableClock();

        OpenStreetMapProvider.OverpassCircuitBreaker cb =
                new OpenStreetMapProvider.OverpassCircuitBreaker(30, clock);

        cb.recordFailure("host1");
        // host2 is still closed - acquire it first to register in states
        assertTrue(cb.tryAcquire("host2"));

        long wait = cb.getMinWaitMsForAvailableEndpoint(java.util.List.of("host1", "host2"));
        assertEquals(0, wait, "Should return 0 because host2 is closed");
    }

    @Test
    void getMinWaitMsReturnsZeroWhenAnyHostHalfOpenNoProbe() {
        MutableClock clock = new MutableClock();

        OpenStreetMapProvider.OverpassCircuitBreaker cb =
                new OpenStreetMapProvider.OverpassCircuitBreaker(1, clock);

        cb.recordFailure("host1");
        cb.recordFailure("host2");

        clock.advanceMillis(1_001);

        // Both half-open, no probe in flight
        long wait = cb.getMinWaitMsForAvailableEndpoint(java.util.List.of("host1", "host2"));
        assertEquals(0, wait);
    }

    @Test
    void getMinWaitMsReturnsPositiveWhenHalfOpenProbeInFlight() {
        MutableClock clock = new MutableClock();

        OpenStreetMapProvider.OverpassCircuitBreaker cb =
                new OpenStreetMapProvider.OverpassCircuitBreaker(30, clock);

        cb.recordFailure("host1");

        clock.advanceMillis(30_001);

        // Acquire half-open probe
        assertTrue(cb.tryAcquire("host1"));

        // Now probe is in flight, should return positive wait
        long wait = cb.getMinWaitMsForAvailableEndpoint(java.util.List.of("host1"));
        assertTrue(wait > 0, "Should return positive wait when probe in flight");

        // Release probe
        cb.releaseProbe("host1");

        // Should return 0 again
        wait = cb.getMinWaitMsForAvailableEndpoint(java.util.List.of("host1"));
        assertEquals(0, wait);
    }
}