package com.gendaz.leads.osm.discovery;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Candidato OSM lido do export GeoJSONSeq (streaming, sem carregar tudo em RAM).
 */
public record OsmCandidate(
        String osmType,
        long osmId,
        String name,
        String normalizedName,
        JsonNode tags,
        double latitude,
        double longitude,
        String timestamp,
        String address,
        String city,
        String state,
        String country,
        String countryCode
) {
    public String stableKey() {
        return (osmType == null ? "?" : osmType) + "/" + osmId;
    }
}
