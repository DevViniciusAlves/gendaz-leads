package com.gendaz.leads.dto.osm;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record OsmRegionSyncRequest(
        @NotBlank
        @Size(max = 255)
        String niche
) {
}
