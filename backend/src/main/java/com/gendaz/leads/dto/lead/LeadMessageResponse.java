package com.gendaz.leads.dto.lead;

import java.time.Instant;

public record LeadMessageResponse(
        String messageText,
        boolean edited,
        boolean approved,
        Instant generatedAt,
        Instant editedAt
) {}
