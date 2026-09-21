package com.gendaz.leads.service.provider;

import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class OverpassCircuitBreakerTest {

    @Test
    void circuitBreakerOpensOnFailureAndClosesOnSuccess() {
        OpenStreetMapProvider.OverpassCircuitBreaker cb = new OpenStreetMapProvider.OverpassCircuitBreaker(1); // 1 second open

        // Initially closed
        assertTrue(cb.tryAcquire("host1"));

        // Record failure
        cb.recordFailure("host1");

        // Should be open now
        assertFalse(cb.tryAcquire("host1"));

        // Wait for cooldown
        try { TimeUnit.MILLISECONDS.sleep(1100); } catch (InterruptedException ignored) {}

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
        OpenStreetMapProvider.OverpassCircuitBreaker cb = new OpenStreetMapProvider.OverpassCircuitBreaker(30);

        assertTrue(cb.tryAcquire("host1"));

        cb.recordRateLimited("host1", 10); // 10 seconds cooldown

        assertFalse(cb.tryAcquire("host1"));

        try { TimeUnit.MILLISECONDS.sleep(11000); } catch (InterruptedException ignored) {}

        assertTrue(cb.tryAcquire("host1"));
    }

    @Test
    void circuitBreakerHalfOpenOnlyAllowsOneProbe() {
        OpenStreetMapProvider.OverpassCircuitBreaker cb = new OpenStreetMapProvider.OverpassCircuitBreaker(1);

        cb.recordFailure("host1");

        try { TimeUnit.MILLISECONDS.sleep(1100); } catch (InterruptedException ignored) {}

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
        OpenStreetMapProvider.OverpassCircuitBreaker cb = new OpenStreetMapProvider.OverpassCircuitBreaker(30);

        assertTrue(cb.tryAcquire("host1"));

        // Should return 0 because host is closed
        assertEquals(0, cb.getMinWaitMsForAvailableEndpoint());
    }

    @Test
    void getMinWaitMsReturnsWaitTimeWhenOpen() {
        OpenStreetMapProvider.OverpassCircuitBreaker cb = new OpenStreetMapProvider.OverpassCircuitBreaker(30);

        cb.recordFailure("host1");

        long wait = cb.getMinWaitMsForAvailableEndpoint();
        assertTrue(wait > 0 && wait <= 30000);
    }

    @Test
    void getMinWaitMsReturnsMinAcrossMultipleHosts() {
        OpenStreetMapProvider.OverpassCircuitBreaker cb = new OpenStreetMapProvider.OverpassCircuitBreaker(30);

        cb.recordRateLimited("host1", 10); // 10 seconds
        cb.recordRateLimited("host2", 20); // 20 seconds

        long wait = cb.getMinWaitMsForAvailableEndpoint();
        assertTrue(wait > 0 && wait <= 10000, "Should return minimum wait (host1: 10s)");
    }

@Test
    void getMinWaitMsReturnsZeroWhenAnyHostClosed() {
        OpenStreetMapProvider.OverpassCircuitBreaker cb = new OpenStreetMapProvider.OverpassCircuitBreaker(30);

        cb.recordFailure("host1");
        // host2 is still closed - acquire it first to register in states
        assertTrue(cb.tryAcquire("host2"));

        long wait = cb.getMinWaitMsForAvailableEndpoint();
        assertEquals(0, wait, "Should return 0 because host2 is closed");
    }

    @Test
    void getMinWaitMsReturnsZeroWhenAnyHostHalfOpenNoProbe() {
        OpenStreetMapProvider.OverpassCircuitBreaker cb = new OpenStreetMapProvider.OverpassCircuitBreaker(1);

        cb.recordFailure("host1");
        cb.recordFailure("host2");

        try { Thread.sleep(1100); } catch (InterruptedException ignored) {}

        // Both half-open, no probe in flight
        long wait = cb.getMinWaitMsForAvailableEndpoint();
        assertEquals(0, wait);
    }

    @Test
    void getMinWaitMsReturnsPositiveWhenHalfOpenProbeInFlight() {
        OpenStreetMapProvider.OverpassCircuitBreaker cb = new OpenStreetMapProvider.OverpassCircuitBreaker(30);

        cb.recordFailure("host1");

        try { Thread.sleep(31000); } catch (InterruptedException ignored) {}

        // Acquire half-open probe
        assertTrue(cb.tryAcquire("host1"));

        // Now probe is in flight, should return positive wait
        long wait = cb.getMinWaitMsForAvailableEndpoint();
        assertTrue(wait > 0, "Should return positive wait when probe in flight");

        // Release probe
        cb.releaseProbe("host1");

        // Should return 0 again
        wait = cb.getMinWaitMsForAvailableEndpoint();
        assertEquals(0, wait);
    }
}