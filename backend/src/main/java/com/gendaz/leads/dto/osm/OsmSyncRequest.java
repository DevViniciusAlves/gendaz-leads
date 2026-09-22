package com.gendaz.leads.dto.osm;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record OsmSyncRequest(
        @NotBlank @Size(max = 255) String city,
        @NotBlank @Size(max = 120) String country
) {}