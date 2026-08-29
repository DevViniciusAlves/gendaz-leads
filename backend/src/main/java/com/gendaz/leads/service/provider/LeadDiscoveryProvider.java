package com.gendaz.leads.service.provider;

import com.gendaz.leads.domain.LeadCandidate;

import java.util.List;

public interface LeadDiscoveryProvider {

    String getName();

    boolean isEnabled();

    List<LeadCandidate> discover(String niche, String location, int limit);
}
