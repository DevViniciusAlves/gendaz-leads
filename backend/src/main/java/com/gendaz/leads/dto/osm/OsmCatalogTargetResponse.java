package com.gendaz.leads.dto.osm;

import com.gendaz.leads.entity.OsmCatalogTarget;
import com.gendaz.leads.entity.OsmSyncRun;

import java.time.Instant;

public record OsmCatalogTargetResponse(
        Long id,
        Long regionId,
        String city,
        String state,
        String country,
        String countryCode,
        String requestedNiche,
        String canonicalNiche,
        Integer targetValid,
        Integer qualifiedCount,
        Integer availableNewCount,
        String poolStatus,
        Instant lastSuccessAt,
        Instant lastAttemptAt,
        String lastError,
        OsmSyncRunResponse latestRun
) {
    public static OsmCatalogTargetResponse from(OsmCatalogTarget target, OsmSyncRun latestRun) {
        return new OsmCatalogTargetResponse(
                target.getId(),
                target.getRegion().getId(),
                target.getRegion().getCity(),
                target.getRegion().getState(),
                target.getRegion().getCountry(),
                target.getRegion().getCountryCode(),
                target.getRequestedNiche(),
                target.getCanonicalNiche(),
                target.getTargetValid(),
                target.getQualifiedCount(),
                target.getAvailableNewCount(),
                target.getPoolStatus(),
                target.getLastSuccessAt(),
                target.getLastAttemptAt(),
                target.getLastError(),
                latestRun == null ? null : OsmSyncRunResponse.from(latestRun)
        );
    }
}
