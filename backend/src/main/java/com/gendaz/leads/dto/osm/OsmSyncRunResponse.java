package com.gendaz.leads.dto.osm;

import com.gendaz.leads.entity.OsmSyncRun;

import java.time.Instant;

public record OsmSyncRunResponse(
        Long id,
        Long regionId,
        String city,
        String state,
        String country,
        String status,
        Instant startedAt,
        Instant finishedAt,
        Long placesRead,
        Long placesStaged,
        Long placesInserted,
        Long placesUpdated,
        Long placesDeactivated,
        Long githubRunId,
        String errorMessage,
        Instant createdAt,
        Instant updatedAt
) {
    public static OsmSyncRunResponse from(OsmSyncRun run) {
        return new OsmSyncRunResponse(
                run.getId(),
                run.getRegion().getId(),
                run.getRegion().getCity(),
                run.getRegion().getState(),
                run.getRegion().getCountry(),
                run.getStatus(),
                run.getStartedAt(),
                run.getFinishedAt(),
                run.getPlacesRead(),
                run.getPlacesStaged(),
                run.getPlacesInserted(),
                run.getPlacesUpdated(),
                run.getPlacesDeactivated(),
                run.getGithubRunId(),
                run.getErrorMessage(),
                run.getCreatedAt(),
                run.getUpdatedAt()
        );
    }
}