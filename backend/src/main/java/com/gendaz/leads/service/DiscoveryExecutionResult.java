package com.gendaz.leads.service;

public record DiscoveryExecutionResult(
        Outcome outcome,
        int acceptedThisRun,
        int totalCampaignLeads,
        int areasAttempted,
        int areasSucceeded,
        int areasSplit,
        int areasSkipped,
        boolean coverageExhausted,
        String errorCode,
        String errorMessage
) {

    public enum Outcome {
        COMPLETE,
        PARTIAL,
        EMPTY,
        INFRA_UNAVAILABLE,
        BUDGET_EXHAUSTED
    }
}