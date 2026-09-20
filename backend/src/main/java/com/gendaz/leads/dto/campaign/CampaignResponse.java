package com.gendaz.leads.dto.campaign;

import java.time.Instant;

public record CampaignResponse(
        Long id,
        String name,
        String niche,
        String city,
        String country,
        String location,
        int requestedQuantity,
        String status,
        int discoveredCount,
        int analyzedCount,
        int messageCount,
        int approvedCount,
        int sentCount,
        int repliedCount,
        int interestedCount,
        int convertedCount,
        int blockedCount,
        String progressStage,
        int progressCurrent,
        int progressTotal,
        String errorMessage,
        Instant createdAt,
        Instant updatedAt
) {}
