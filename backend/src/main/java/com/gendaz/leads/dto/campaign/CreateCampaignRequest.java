package com.gendaz.leads.dto.campaign;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record CreateCampaignRequest(
        @NotBlank @jakarta.validation.constraints.Size(max = 255) String niche,
        @NotBlank @jakarta.validation.constraints.Size(max = 255) String city,
        @NotBlank @jakarta.validation.constraints.Size(max = 120) String country,
        @NotNull @Min(1) @Max(30) Integer quantity
) {}
