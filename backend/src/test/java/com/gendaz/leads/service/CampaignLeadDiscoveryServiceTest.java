package com.gendaz.leads.service;

import com.gendaz.leads.domain.LeadCandidate;
import com.gendaz.leads.entity.Campaign;
import com.gendaz.leads.entity.Lead;
import com.gendaz.leads.entity.LeadEvent;
import com.gendaz.leads.repository.CampaignRepository;
import com.gendaz.leads.repository.CampaignLeadRepository;
import com.gendaz.leads.repository.LeadEventRepository;
import com.gendaz.leads.repository.LeadRepository;
import com.gendaz.leads.service.provider.AreaQueryPhase;
import com.gendaz.leads.service.provider.AreaQueryResult;
import com.gendaz.leads.service.provider.DiscoveryBudget;
import com.gendaz.leads.service.provider.GeoScope;
import com.gendaz.leads.service.provider.LeadDiscoveryRequest;
import com.gendaz.leads.service.provider.OpenStreetMapProvider;
import com.gendaz.leads.service.provider.SearchRegion;
import com.gendaz.leads.util.Normalizer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CampaignLeadDiscoveryServiceTest {

    @Mock CampaignRepository campaignRepository;
    @Mock CampaignLeadRepository campaignLeadRepository;
    @Mock LeadRepository leadRepository;
    @Mock LeadEventRepository leadEventRepository;
    @Mock DeduplicationService deduplicationService;
    @Mock CampaignLeadPersistenceService persistenceService;
    @Mock WebsiteContactEnricher websiteContactEnricher;
    @Mock Normalizer normalizer;
    @Mock OpenStreetMapProvider osm;

    private CampaignLeadDiscoveryService service;
    private Campaign campaign;
    private GeoScope scope;
    private SearchRegion region;

    @BeforeEach
    void setUp() {
        service = new CampaignLeadDiscoveryService(
                campaignRepository, campaignLeadRepository, leadRepository, leadEventRepository,
                deduplicationService, persistenceService, websiteContactEnricher, normalizer, osm
        );

        campaign = Campaign.builder()
                .id(1L)
                .niche("barbearia")
                .city("Cuiabá")
                .country("Brazil")
                .requestedQuantity(3)
                .build();

        scope = new GeoScope(-15.6, -56.1, "Cuiabá", "MT", "Brazil", "br", -15.7, -56.2, -15.5, -56.0, true);
        region = SearchRegion.root(-15.7, -56.2, -15.5, -56.0, -15.6, -56.1);

        when(campaignLeadRepository.countByCampaignId(1L)).thenReturn(0L);
        when(campaignRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(leadRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(leadEventRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void noContactNotPersistedAndContinues() {
        LeadCandidate candidate = new LeadCandidate("Barbearia Sem Contato", "openstreetmap", "node/1");
        candidate.setCategory("shop=barber");

        when(osm.resolveScope(any(), any())).thenReturn(scope);
        when(osm.queryRegion(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(AreaQueryResult.success(List.of(candidate), false, "overpass-api.de", 100));
        when(deduplicationService.check(any())).thenReturn(new DeduplicationService.DuplicateCheck(Optional.empty(), null));
        when(normalizer.normalizeSourceId(anyString(), anyString())).thenReturn("openstreetmap_node/1");
        when(persistenceService.createLeadForCampaign(any(), any())).thenReturn(Lead.builder().id(10L).build());

        var result = service.discoverAndPersist(campaign, 3);

        assertEquals(0, result.acceptedThisRun());
    }

    @Test
    void usefulPhoneAccepted() {
        LeadCandidate candidate = new LeadCandidate("Barbearia", "openstreetmap", "node/1");
        candidate.setCategory("shop=barber");
        candidate.setPhone("+55 65 9999-8888");

        when(osm.resolveScope(any(), any())).thenReturn(scope);
        when(osm.queryRegion(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(AreaQueryResult.success(List.of(candidate), false, "overpass-api.de", 100));
        when(deduplicationService.check(any())).thenReturn(new DeduplicationService.DuplicateCheck(Optional.empty(), null));
        when(normalizer.normalizeSourceId(anyString(), anyString())).thenReturn("openstreetmap_node/1");
        when(persistenceService.createLeadForCampaign(any(), any())).thenReturn(Lead.builder().id(10L).build());

        var result = service.discoverAndPersist(campaign, 3);

        assertEquals(1, result.acceptedThisRun());
    }

    @Test
    void usefulEmailAccepted() {
        LeadCandidate candidate = new LeadCandidate("Barbearia", "openstreetmap", "node/1");
        candidate.setCategory("shop=barber");
        candidate.setEmail("contato@barbearia.com");

        when(osm.resolveScope(any(), any())).thenReturn(scope);
        when(osm.queryRegion(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(AreaQueryResult.success(List.of(candidate), false, "overpass-api.de", 100));
        when(deduplicationService.check(any())).thenReturn(new DeduplicationService.DuplicateCheck(Optional.empty(), null));
        when(normalizer.normalizeSourceId(anyString(), anyString())).thenReturn("openstreetmap_node/1");
        when(persistenceService.createLeadForCampaign(any(), any())).thenReturn(Lead.builder().id(10L).build());

        var result = service.discoverAndPersist(campaign, 3);

        assertEquals(1, result.acceptedThisRun());
    }

    @Test
    void usefulWebsiteAccepted() {
        LeadCandidate candidate = new LeadCandidate("Barbearia", "openstreetmap", "node/1");
        candidate.setCategory("shop=barber");
        candidate.setWebsite("https://barbearia.com");

        when(osm.resolveScope(any(), any())).thenReturn(scope);
        when(osm.queryRegion(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(AreaQueryResult.success(List.of(candidate), false, "overpass-api.de", 100));
        when(deduplicationService.check(any())).thenReturn(new DeduplicationService.DuplicateCheck(Optional.empty(), null));
        when(normalizer.normalizeSourceId(anyString(), anyString())).thenReturn("openstreetmap_node/1");
        when(persistenceService.createLeadForCampaign(any(), any())).thenReturn(Lead.builder().id(10L).build());

        var result = service.discoverAndPersist(campaign, 3);

        assertEquals(1, result.acceptedThisRun());
    }

    @Test
    void usefulInstagramAccepted() {
        LeadCandidate candidate = new LeadCandidate("Barbearia", "openstreetmap", "node/1");
        candidate.setCategory("shop=barber");
        candidate.setInstagramUsername("barbearia");

        when(osm.resolveScope(any(), any())).thenReturn(scope);
        when(osm.queryRegion(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(AreaQueryResult.success(List.of(candidate), false, "overpass-api.de", 100));
        when(deduplicationService.check(any())).thenReturn(new DeduplicationService.DuplicateCheck(Optional.empty(), null));
        when(normalizer.normalizeSourceId(anyString(), anyString())).thenReturn("openstreetmap_node/1");
        when(persistenceService.createLeadForCampaign(any(), any())).thenReturn(Lead.builder().id(10L).build());

        var result = service.discoverAndPersist(campaign, 3);

        assertEquals(1, result.acceptedThisRun());
    }

    @Test
    void budgetExhaustedNotEmpty() {
        when(osm.resolveScope(any(), any())).thenReturn(scope);
        when(osm.queryRegion(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(AreaQueryResult.infraUnavailable("OSM_DISCOVERY_TIMEOUT", "Budget esgotado", 100));

        var result = service.discoverAndPersist(campaign, 3);

        assertEquals(DiscoveryExecutionResult.Outcome.BUDGET_EXHAUSTED, result.outcome());
    }

    @Test
    void emptyOnlyWithCoverageExhaustedAndValidQuery() {
        when(osm.resolveScope(any(), any())).thenReturn(scope);
        when(osm.queryRegion(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(AreaQueryResult.success(List.of(), false, "overpass-api.de", 100));

        var result = service.discoverAndPersist(campaign, 3);

        assertEquals(DiscoveryExecutionResult.Outcome.EMPTY, result.outcome());
    }
}