package com.gendaz.leads.service.provider;

public final class DiscoveryBudget {

    private final long totalMs;
    private final long deadlineNanos;
    private final boolean unlimited;

    private DiscoveryBudget(long totalMs, boolean unlimited) {
        this.totalMs = totalMs;
        this.unlimited = unlimited;
        if (unlimited) {
            this.deadlineNanos = Long.MAX_VALUE;
        } else {
            if (totalMs <= 0) {
                throw new IllegalArgumentException("totalMs deve ser > 0");
            }
            this.deadlineNanos = System.nanoTime() + totalMs * 1_000_000L;
        }
    }

    public static DiscoveryBudget forTarget(
            int targetQuantity,
            long baseBudgetMs,
            long perLeadBudgetMs,
            long maxBudgetMs
    ) {
        long calculated = baseBudgetMs + Math.max(0, targetQuantity) * perLeadBudgetMs;
        long total = Math.min(maxBudgetMs, calculated);
        return new DiscoveryBudget(total, false);
    }

    public DiscoveryBudget slice(long maxSliceMs) {
        if (maxSliceMs <= 0L) {
            throw new IllegalArgumentException("maxSliceMs deve ser > 0");
        }

        if (unlimited) {
            return new DiscoveryBudget(maxSliceMs, false);
        }

        long parentRemainingMs = remainingMs();

        if (parentRemainingMs <= 0L) {
            throw new IllegalStateException("Budget global esgotado");
        }

        return new DiscoveryBudget(
                Math.min(maxSliceMs, parentRemainingMs),
                false
        );
    }

    public long totalMs() {
        return totalMs;
    }

    public long remainingMs() {
        if (unlimited) {
            return Long.MAX_VALUE;
        }
        return Math.max(0L, (deadlineNanos - System.nanoTime()) / 1_000_000L);
    }

    public long elapsedMs() {
        if (unlimited) {
            return 0L;
        }
        return Math.max(0L, totalMs - remainingMs());
    }

    public boolean expired() {
        if (unlimited) {
            return false;
        }
        return remainingMs() <= 0L;
    }

    public static DiscoveryBudget unlimited() {
        return new DiscoveryBudget(Long.MAX_VALUE, true);
    }

    public int clampTimeout(int configuredTimeoutMs) {
        if (unlimited) {
            return configuredTimeoutMs;
        }
        long remaining = remainingMs();
        if (remaining <= 0) return 0;
        return (int) Math.max(1, Math.min(configuredTimeoutMs, remaining));
    }

    public boolean isUnlimited() {
        return unlimited;
    }
}