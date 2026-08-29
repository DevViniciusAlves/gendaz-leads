package com.gendaz.leads.dto.lead;

import jakarta.validation.constraints.NotBlank;

public record StatusRequest(
        @NotBlank String status
) {}
