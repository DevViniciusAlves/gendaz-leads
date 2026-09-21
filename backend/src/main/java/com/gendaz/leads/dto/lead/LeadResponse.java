package com.gendaz.leads.dto.lead;

import com.gendaz.leads.dto.lead.LeadAnalysisResponse;
import com.gendaz.leads.dto.lead.LeadMessageResponse;

import java.time.Instant;

public record LeadResponse(
        Long id,
        String businessName,
        String category,
        String address,
        String city,
        String state,
        String country,
        String phone,
        String email,
        String website,
        String instagramUsername,
        String instagramUrl,
        String instagramStatus,
        String source,
        String sourceId,
        boolean doNotContact,
        String status,
        Long campaignId,
        String campaignName,
        Instant createdAt,
        LeadAnalysisResponse analysis,
        LeadMessageResponse message
) {}
