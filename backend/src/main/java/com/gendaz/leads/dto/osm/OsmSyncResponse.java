package com.gendaz.leads.dto.osm;

import com.gendaz.leads.entity.OsmSyncRun;

import java.time.Instant;

public record OsmSyncResponse(
        Long syncRunId,
        Long regionId,
        Long targetId,
        String city,
        String state,
        String country,
        String status,
        String requestedNiche,
        String canonicalNiche,
        Integer targetValid
) {
    public static OsmSyncResponse from(OsmSyncRun run) {
        return new OsmSyncResponse(
                run.getId(),
                run.getRegion().getId(),
                run.getTarget() == null ? null : run.getTarget().getId(),
                run.getRegion().getCity(),
                run.getRegion().getState(),
                run.getRegion().getCountry(),
                run.getStatus(),
                run.getRequestedNiche(),
                run.getCanonicalNiche(),
                run.getTargetValid()
        );
    }
}
