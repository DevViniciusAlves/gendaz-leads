package com.gendaz.leads.dto.osm;

import com.gendaz.leads.entity.OsmSyncRun;

import java.time.Instant;

public record OsmSyncResponse(
        Long syncRunId,
        Long regionId,
        String city,
        String state,
        String country,
        String status
) {
    public static OsmSyncResponse from(OsmSyncRun run) {
        return new OsmSyncResponse(
                run.getId(),
                run.getRegion().getId(),
                run.getRegion().getCity(),
                run.getRegion().getState(),
                run.getRegion().getCountry(),
                run.getStatus()
        );
    }
}