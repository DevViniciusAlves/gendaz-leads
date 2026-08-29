package com.gendaz.leads.dto.lead;

import java.time.Instant;

public record LeadAnalysisResponse(
        String businessType,
        String services,
        String digitalPresence,
        Boolean usesBookingSystem,
        String bookingSystemStatus,
        String detectedSystem,
        String manualAttendanceSignals,
        String painPoints,
        String commercialOpportunity,
        Integer opportunityScore,
        String reasoningSummary,
        String model,
        Instant analyzedAt
) {}
