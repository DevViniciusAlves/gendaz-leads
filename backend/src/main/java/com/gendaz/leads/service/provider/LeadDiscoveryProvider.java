package com.gendaz.leads.service.provider;

import com.gendaz.leads.domain.LeadCandidate;

import java.util.List;

public interface LeadDiscoveryProvider {

    String getName();

    boolean isEnabled();

    GeoScope resolveScope(LeadDiscoveryRequest request, DiscoveryBudget budget);

    AreaQueryResult queryRegion(GeoScope scope, String niche, SearchRegion region, AreaQueryPhase phase, int rawLimit, String preferredEndpointHost, DiscoveryBudget budget, Long campaignId);
}
