package com.gendaz.leads.dto.osm;

import com.gendaz.leads.entity.OsmCatalogRegion;

import java.time.Instant;

public record OsmCatalogRegionResponse(
        Long id,
        String city,
        String state,
        String country,
        String countryCode,
        String osmType,
        Long osmId,
        String geofabrikRegion,
        String catalogStatus,
        Integer placeCount,
        Instant lastSuccessAt,
        Instant lastAttemptAt,
        String lastError,
        Instant createdAt,
        Instant updatedAt
) {
    public static OsmCatalogRegionResponse from(OsmCatalogRegion region) {
        return new OsmCatalogRegionResponse(
                region.getId(),
                region.getCity(),
                region.getState(),
                region.getCountry(),
                region.getCountryCode(),
                region.getOsmType(),
                region.getOsmId(),
                region.getGeofabrikRegion(),
                region.getCatalogStatus(),
                region.getPlaceCount(),
                region.getLastSuccessAt(),
                region.getLastAttemptAt(),
                region.getLastError(),
                region.getCreatedAt(),
                region.getUpdatedAt()
        );
    }
}