package com.gendaz.leads.dto.lead;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record UpdateLeadMessageRequest(
        @NotBlank @Size(max = 4000) String messageText
) {}
