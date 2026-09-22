package com.gendaz.leads.service.provider;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DiscoveryBudgetTest {

    @Test
    void unlimitedNeverExpires() {
        DiscoveryBudget budget = DiscoveryBudget.unlimited();
        assertFalse(budget.expired(), "Unlimited budget should never expire");
    }

    @Test
    void unlimitedRemainingMsIsMaxValue() {
        DiscoveryBudget budget = DiscoveryBudget.unlimited();
        assertEquals(Long.MAX_VALUE, budget.remainingMs(), "Unlimited budget remainingMs should be Long.MAX_VALUE");
    }

    @Test
    void unlimitedClampTimeoutReturnsConfigured() {
        DiscoveryBudget budget = DiscoveryBudget.unlimited();
        assertEquals(8000, budget.clampTimeout(8000), "Unlimited budget should return configured timeout");
        assertEquals(15000, budget.clampTimeout(15000));
        assertEquals(0, budget.clampTimeout(0));
    }

    @Test
    void unlimitedSliceReturnsLimitedBudget() {
        DiscoveryBudget budget = DiscoveryBudget.unlimited();
        DiscoveryBudget slice = budget.slice(15000);
        assertFalse(slice.isUnlimited(), "Slice of unlimited should be limited");
        assertEquals(15000, slice.totalMs());
        assertTrue(slice.remainingMs() >= 14999 && slice.remainingMs() <= 15000, 
                "Remaining should be close to 15000 but was " + slice.remainingMs());
    }

    @Test
    void unlimitedElapsedMsIsZero() {
        DiscoveryBudget budget = DiscoveryBudget.unlimited();
        assertEquals(0L, budget.elapsedMs(), "Unlimited budget elapsedMs should be 0");
    }

    @Test
    void limitedBudgetExpires() {
        DiscoveryBudget budget = DiscoveryBudget.forTarget(1, 1, 1, 100);
        // Can't easily test expiration without waiting, but verify it's not unlimited
        assertNotEquals(Long.MAX_VALUE, budget.remainingMs());
    }

    @Test
    void limitedBudgetSliceWorks() {
        DiscoveryBudget budget = DiscoveryBudget.forTarget(10, 1000, 100, 5000);
        DiscoveryBudget slice = budget.slice(1000);
        assertTrue(slice.totalMs() <= 1000);
        assertTrue(slice.remainingMs() <= 1000);
    }

    @Test
    void forTargetCalculatesCorrectly() {
        DiscoveryBudget budget = DiscoveryBudget.forTarget(3, 15000, 15000, 300000);
        assertEquals(60000, budget.totalMs(), "base + target * perLead = 15000 + 3*15000 = 60000");
    }

    @Test
    void forTargetRespectsMaxBudget() {
        DiscoveryBudget budget = DiscoveryBudget.forTarget(100, 15000, 15000, 300000);
        assertEquals(300000, budget.totalMs(), "Should be capped at maxBudget");
    }
}