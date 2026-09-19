package com.gendaz.leads.dto.template;

import java.time.Instant;

public record MessageTemplateResponse(
        Long id,
        String name,
        String templateText,
        boolean isDefault,
        Instant updatedAt
) {}
