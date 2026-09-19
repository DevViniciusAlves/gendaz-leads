package com.gendaz.leads.dto.campaign;

import java.time.Instant;

public record CampaignMessagingLeadResponse(
    Long leadId,
    String businessName,
    String category,
    String instagramUsername,
    String instagramUrl,
    String phone,
    String city,
    String state,
    String website,
    Integer opportunityScore,
    String detectedSystem,
    String leadStatus,
    boolean eligible,
    String ineligibilityCode,
    String ineligibilityReason,
    Long messageSendId,
    String sendStatus,
    Integer attempts,
    Instant queuedAt,
    Instant sentAt,
    String errorCode
) {}
