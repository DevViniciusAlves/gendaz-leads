package com.gendaz.leads.service.provider;

public record LeadDiscoveryRequest(
    Long campaignId,
    String niche,
    String city,
    String country,
    int targetQuantity
) {}