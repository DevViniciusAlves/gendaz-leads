package com.gendaz.leads.dto.dashboard;

import java.util.Map;

public record DashboardResponse(
        long totalLeads,
        long newLeads,
        long analyzedLeads,
        long messageReadyLeads,
        long approvedLeads,
        long sentLeads,
        long repliedLeads,
        long interestedLeads,
        long convertedLeads,
        long blockedLeads,
        long activeCampaigns,
        Map<String, Long> statusBreakdown
) {}
