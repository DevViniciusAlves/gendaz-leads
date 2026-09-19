package com.gendaz.leads.dto.campaign;

public record IneligibleLeadResponse(
    Long leadId,
    String businessName,
    String code,
    String reason
) {}
