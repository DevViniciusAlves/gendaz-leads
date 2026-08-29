package com.gendaz.leads.domain;

public record AnalysisResult(
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
        String reasoningSummary
) {}
