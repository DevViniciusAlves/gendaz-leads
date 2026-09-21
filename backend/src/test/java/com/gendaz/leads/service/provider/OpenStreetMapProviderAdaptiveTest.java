package com.gendaz.leads.service.provider;

import com.gendaz.leads.util.Normalizer;
import com.gendaz.leads.util.SsrfGuard;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class OpenStreetMapProviderAdaptiveTest {

    @Test
    void adaptiveMaxDepthMinEdgeRespectsConfig() {
        var region = SearchRegion.root(-15.7, -56.2, -15.5, -56.0, -15.6, -56.1);

        assertTrue(region.canSplit(6, 2.0));

        for (int i = 0; i < 7; i++) {
            var children = region.split(-15.6, -56.1);
            if (!children.isEmpty()) {
                region = children.get(0);
            }
        }

        assertFalse(region.canSplit(6, 2.0));
    }

    @Test
    void searchRegionSplitProducesFourChildren() {
        var region = SearchRegion.root(-15.7, -56.2, -15.5, -56.0, -15.6, -56.1);

        var children = region.split(-15.6, -56.1);

        assertEquals(4, children.size());
    }

    @Test
    void searchRegionDistanceFromCityCenterCalculated() {
        var region = SearchRegion.root(-15.7, -56.2, -15.5, -56.0, -15.6, -56.1);

        assertTrue(region.distanceFromCityCenterKm() >= 0);
    }

    @Test
    void searchRegionBboxFormattedCorrectly() {
        var region = SearchRegion.root(-15.7, -56.2, -15.5, -56.0, -15.6, -56.1);

        String bbox = region.bbox();
        assertTrue(bbox.contains("-15.7"));
        assertTrue(bbox.contains("-56.2"));
        assertTrue(bbox.contains("-15.5"));
        assertTrue(bbox.contains("-56.0"));
    }

    @Test
    void geoScopeRootRegionUsesBboxWhenValid() {
        var scope = new GeoScope(-15.6, -56.1, "Cuiabá", "MT", "Brazil", "br", -15.7, -56.2, -15.5, -56.0, true);
        var region = scope.rootRegion();

        assertEquals(-15.7, region.south());
        assertEquals(-56.2, region.west());
        assertEquals(-15.5, region.north());
        assertEquals(-56.0, region.east());
    }

    @Test
    void geoScopeRootRegionUsesDefaultWhenBboxInvalid() {
        var scope = new GeoScope(-15.6, -56.1, "Cuiabá", "MT", "Brazil", "br", 0, 0, 0, 0, false);
        var region = scope.rootRegion();

        assertEquals(-15.6 - 0.18, region.south(), 0.001);
        assertEquals(-56.1 - 0.18, region.west(), 0.001);
        assertEquals(-15.6 + 0.18, region.north(), 0.001);
        assertEquals(-56.1 + 0.18, region.east(), 0.001);
    }

    @Test
    void discoveryBudgetClampTimeoutRespectsRemaining() {
        var budget = DiscoveryBudget.forTarget(1, 10000, 1000, 20000);

        int clamped = budget.clampTimeout(8000);

        assertTrue(clamped > 0);
        assertTrue(clamped <= 8000);
    }

    @Test
    void discoveryBudgetExpiredWhenTimeElapsed() throws InterruptedException {
        var budget = DiscoveryBudget.forTarget(1, 10, 10, 100);
        Thread.sleep(20);

        assertTrue(budget.expired());
    }

    @Test
    void discoveryBudgetRemainingDecreasesOverTime() throws InterruptedException {
        var budget = DiscoveryBudget.forTarget(1, 10000, 1000, 20000);
        long remaining1 = budget.remainingMs();
        Thread.sleep(50);
        long remaining2 = budget.remainingMs();

        assertTrue(remaining2 < remaining1);
    }

    @Test
    void areaQueryResultSuccessFactory() {
        var result = AreaQueryResult.success(List.of(), false, "host", 100);

        assertEquals(AreaQueryResult.Outcome.SUCCESS, result.outcome());
        assertEquals("host", result.endpointHost());
        assertEquals(100, result.elapsedMs());
    }

    @Test
    void areaQueryResultSplitRequiredFactory() {
        var result = AreaQueryResult.splitRequired("CODE", "Message", 200);

        assertEquals(AreaQueryResult.Outcome.SPLIT_REQUIRED, result.outcome());
        assertEquals("CODE", result.errorCode());
        assertEquals("Message", result.errorMessage());
        assertEquals(200, result.elapsedMs());
    }

    @Test
    void areaQueryResultInfraUnavailableFactory() {
        var result = AreaQueryResult.infraUnavailable("CODE", "Message", 300);

        assertEquals(AreaQueryResult.Outcome.INFRA_UNAVAILABLE, result.outcome());
        assertEquals("CODE", result.errorCode());
        assertEquals("Message", result.errorMessage());
        assertEquals(300, result.elapsedMs());
    }

    @Test
    void areaQueryResultQueryErrorFactory() {
        var result = AreaQueryResult.queryError("CODE", "Message", 400);

        assertEquals(AreaQueryResult.Outcome.QUERY_ERROR, result.outcome());
        assertEquals("CODE", result.errorCode());
        assertEquals("Message", result.errorMessage());
        assertEquals(400, result.elapsedMs());
    }
}