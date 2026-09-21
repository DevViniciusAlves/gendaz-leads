package com.gendaz.leads.service.provider;

import com.gendaz.leads.domain.LeadCandidate;

import java.util.List;

public record AreaQueryResult(
        Outcome outcome,
        List<LeadCandidate> candidates,
        boolean saturated,
        String endpointHost,
        String errorCode,
        String errorMessage,
        long elapsedMs,
        boolean httpAttemptMade
) {

    public enum Outcome {
        SUCCESS,
        SPLIT_REQUIRED,
        ADMIN_AREA_UNAVAILABLE,
        INFRA_UNAVAILABLE,
        QUERY_ERROR
    }

    public static AreaQueryResult success(
            List<LeadCandidate> candidates,
            boolean saturated,
            String endpointHost,
            long elapsedMs
    ) {
        return new AreaQueryResult(
                Outcome.SUCCESS,
                candidates,
                saturated,
                endpointHost,
                null,
                null,
                elapsedMs,
                true
        );
    }

    public static AreaQueryResult splitRequired(
            String code,
            String message,
            long elapsedMs
    ) {
        return new AreaQueryResult(
                Outcome.SPLIT_REQUIRED,
                List.of(),
                false,
                null,
                code,
                message,
                elapsedMs,
                false
        );
    }

    public static AreaQueryResult adminAreaUnavailable(
            String endpointHost,
            long elapsedMs
    ) {
        return new AreaQueryResult(
                Outcome.ADMIN_AREA_UNAVAILABLE,
                List.of(),
                false,
                endpointHost,
                "OSM_ADMIN_AREA_UNAVAILABLE",
                "A relation OSM não possui area Overpass utilizável; usando bbox fallback.",
                elapsedMs,
                true
        );
    }

    public static AreaQueryResult infraUnavailable(
            String code,
            String message,
            long elapsedMs
    ) {
        return new AreaQueryResult(
                Outcome.INFRA_UNAVAILABLE,
                List.of(),
                false,
                null,
                code,
                message,
                elapsedMs,
                false
        );
    }

    public static AreaQueryResult infraUnavailableWithAttempt(
            String code,
            String message,
            long elapsedMs
    ) {
        return new AreaQueryResult(
                Outcome.INFRA_UNAVAILABLE,
                List.of(),
                false,
                null,
                code,
                message,
                elapsedMs,
                true
        );
    }

    public static AreaQueryResult queryError(
            String code,
            String message,
            long elapsedMs
    ) {
        return new AreaQueryResult(
                Outcome.QUERY_ERROR,
                List.of(),
                false,
                null,
                code,
                message,
                elapsedMs,
                true
        );
    }
}