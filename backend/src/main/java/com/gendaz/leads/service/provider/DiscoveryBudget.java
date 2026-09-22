package com.gendaz.leads.service.provider;

public final class DiscoveryBudget {

    private final long totalMs;
    private final long deadlineNanos;

    private DiscoveryBudget(long totalMs) {
        if (totalMs <= 0) {
            throw new IllegalArgumentException("totalMs deve ser > 0");
        }
        this.totalMs = totalMs;
        this.deadlineNanos = System.nanoTime() + totalMs * 1_000_000L;
    }

    public static DiscoveryBudget forTarget(
            int targetQuantity,
            long baseBudgetMs,
            long perLeadBudgetMs,
            long maxBudgetMs
    ) {
        long calculated = baseBudgetMs + Math.max(0, targetQuantity) * perLeadBudgetMs;
        long total = Math.min(maxBudgetMs, calculated);
        return new DiscoveryBudget(total);
    }

    public DiscoveryBudget slice(long maxSliceMs) {
        if (maxSliceMs <= 0L) {
            throw new IllegalArgumentException("maxSliceMs deve ser > 0");
        }

        long parentRemainingMs = remainingMs();

        if (parentRemainingMs <= 0L) {
            throw new IllegalStateException("Budget global esgotado");
        }

        return new DiscoveryBudget(
                Math.min(maxSliceMs, parentRemainingMs)
        );
    }

    public long totalMs() {
        return totalMs;
    }

    public long remainingMs() {
        return Math.max(0L, (deadlineNanos - System.nanoTime()) / 1_000_000L);
    }

    public long elapsedMs() {
        return Math.max(0L, totalMs - remainingMs());
    }

    public boolean expired() {
        return remainingMs() <= 0L;
    }

    public int clampTimeout(int configuredTimeoutMs) {
        long remaining = remainingMs();
        if (remaining <= 0) return 0;
        return (int) Math.max(1, Math.min(configuredTimeoutMs, remaining));
    }
}