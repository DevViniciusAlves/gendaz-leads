package com.gendaz.leads.service.provider;

import com.gendaz.leads.domain.LeadCandidate;

import java.util.List;

public record LeadDiscoveryResult(
    List<LeadCandidate> candidates,
    DiscoveryOutcome outcome,
    String errorCode,
    String errorMessage,
    boolean regionExhausted
) {

    public static LeadDiscoveryResult complete(List<LeadCandidate> candidates) {
        return new LeadDiscoveryResult(candidates, DiscoveryOutcome.COMPLETE, null, null, false);
    }

    public static LeadDiscoveryResult partial(List<LeadCandidate> candidates, boolean regionExhausted) {
        return new LeadDiscoveryResult(candidates, DiscoveryOutcome.PARTIAL, null, null, regionExhausted);
    }

    public static LeadDiscoveryResult empty(String errorCode, String errorMessage) {
        return new LeadDiscoveryResult(List.of(), DiscoveryOutcome.EMPTY, errorCode, errorMessage, true);
    }

    public static LeadDiscoveryResult infraUnavailable(String errorCode, String errorMessage) {
        return new LeadDiscoveryResult(List.of(), DiscoveryOutcome.INFRA_UNAVAILABLE, errorCode, errorMessage, false);
    }

    public static LeadDiscoveryResult deadlineExceeded(List<LeadCandidate> candidates) {
        return new LeadDiscoveryResult(candidates, DiscoveryOutcome.DEADLINE_EXCEEDED, "OSM_DISCOVERY_TIMEOUT", "Budget de tempo excedido", false);
    }

    public enum DiscoveryOutcome {
        COMPLETE,
        PARTIAL,
        EMPTY,
        INFRA_UNAVAILABLE,
        DEADLINE_EXCEEDED
    }
}