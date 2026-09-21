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
import com.gendaz.leads.service.provider.GeographicStrategy;
import com.gendaz.leads.service.provider.LeadDiscoveryRequest;
import com.gendaz.leads.service.provider.OpenStreetMapProvider;
import com.gendaz.leads.service.provider.SearchRegion;
import com.gendaz.leads.util.Normalizer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.quality.Strictness;
import org.mockito.junit.jupiter.MockitoSettings;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
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
    void setUp() throws Exception {
        service = new CampaignLeadDiscoveryService(
                campaignRepository, campaignLeadRepository, leadRepository, leadEventRepository,
                deduplicationService, persistenceService, websiteContactEnricher, normalizer, osm
        );

        // Set @Value fields via reflection
        setField(service, "baseBudgetMs", 90000L);
        setField(service, "perLeadBudgetMs", 3000L);
        setField(service, "maxBudgetMs", 180000L);
        setField(service, "queryMinRawLimit", 20);
        setField(service, "queryRawPerLead", 6);
        setField(service, "adaptiveMaxDepth", 12);
        setField(service, "adaptiveMinEdgeKm", 1.0);
        setField(service, "queryMaxEdgeKm", 12.0);
        setField(service, "failureSplitThresholdKm", 6.0);
        setField(service, "infraSplitThresholdKm", 2.0);
        setField(service, "maxConsecutiveInfraFailures", 6);
        setField(service, "adminAreaInfraFailuresBeforeBbox", 2);

        campaign = Campaign.builder()
                .id(1L)
                .niche("barbearia")
                .city("Cuiabá")
                .country("Brazil")
                .requestedQuantity(3)
                .build();

        scope = new GeoScope(-15.6, -56.1, "Cuiabá", "MT", "Brazil", "br", -15.7, -56.2, -15.5, -56.0, true, "relation", 333734L);
        region = SearchRegion.root(-15.7, -56.2, -15.5, -56.0, -15.6, -56.1);

        when(campaignLeadRepository.countByCampaignId(1L)).thenReturn(0L);
        when(campaignRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(leadRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(leadEventRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        // Use lenient stubbing for OSM mocks to avoid UnnecessaryStubbingException
        lenient().when(osm.resolveScope(any(), any())).thenReturn(scope);
    }

    private void setField(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    @Test
    void noContactNotPersistedAndContinues() {
        LeadCandidate candidate = new LeadCandidate("Barbearia Sem Contato", "openstreetmap", "node/1");
        candidate.setCategory("shop=barber");

        lenient().when(osm.queryRegionWithStrategy(any(), any(), any(), any(), any(), anyInt(), any(), anyLong()))
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

        lenient().when(osm.queryRegionWithStrategy(any(), any(), any(), any(), any(), anyInt(), any(), anyLong()))
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

        lenient().when(osm.queryRegionWithStrategy(any(), any(), any(), any(), any(), anyInt(), any(), anyLong()))
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

        lenient().when(osm.queryRegionWithStrategy(any(), any(), any(), any(), any(), anyInt(), any(), anyLong()))
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

        lenient().when(osm.queryRegionWithStrategy(any(), any(), any(), any(), any(), anyInt(), any(), anyLong()))
                .thenReturn(AreaQueryResult.success(List.of(candidate), false, "overpass-api.de", 100));
        when(deduplicationService.check(any())).thenReturn(new DeduplicationService.DuplicateCheck(Optional.empty(), null));
        when(normalizer.normalizeSourceId(anyString(), anyString())).thenReturn("openstreetmap_node/1");
        when(persistenceService.createLeadForCampaign(any(), any())).thenReturn(Lead.builder().id(10L).build());

        var result = service.discoverAndPersist(campaign, 3);

        assertEquals(1, result.acceptedThisRun());
    }

    @Test
    void budgetExhaustedNotEmpty() {
        // When provider returns timeout with HTTP attempt, it's INFRA_UNAVAILABLE
        // BUDGET_EXHAUSTED only occurs when budget naturally expires during the loop
        lenient().when(osm.queryRegionWithStrategy(any(), any(), any(), any(), any(), anyInt(), any(), anyLong()))
                .thenReturn(AreaQueryResult.infraUnavailableWithAttempt("OSM_DISCOVERY_TIMEOUT", "Budget esgotado", 100));

        var result = service.discoverAndPersist(campaign, 3);

        assertEquals(DiscoveryExecutionResult.Outcome.INFRA_UNAVAILABLE, result.outcome());
    }

    @Test
    void emptyOnlyWithCoverageExhaustedAndValidQuery() {
        lenient().when(osm.queryRegionWithStrategy(any(), any(), any(), any(), any(), anyInt(), any(), anyLong()))
                .thenReturn(AreaQueryResult.success(List.of(), false, "overpass-api.de", 100));

        var result = service.discoverAndPersist(campaign, 3);

        assertEquals(DiscoveryExecutionResult.Outcome.EMPTY, result.outcome());
    }

    @Test
    void infraUnavailableWithoutHttpAttemptDoesNotIncrementFailureCounter() {
        // This test verifies that INFRA_UNAVAILABLE without HTTP attempt
        // doesn't cause early termination when queue and budget remain
        // Note: lenient stubbing for resolveScope already in setUp

        // First call: INFRA_UNAVAILABLE without HTTP attempt (circuit skip)
        // Second call: SUCCESS with a valid lead
        LeadCandidate candidate = new LeadCandidate("Barbearia", "openstreetmap", "node/1");
        candidate.setCategory("shop=barber");
        candidate.setPhone("+55 65 9999-8888");

        lenient().when(osm.queryRegionWithStrategy(any(), any(), any(), any(), any(), anyInt(), any(), anyLong()))
                .thenReturn(
                        AreaQueryResult.infraUnavailable("OSM_ALL_ENDPOINTS_FAILED", "Todos endpoints indisponíveis", 100),
                        AreaQueryResult.success(List.of(candidate), false, "overpass-api.de", 100)
                );
        when(deduplicationService.check(any())).thenReturn(new DeduplicationService.DuplicateCheck(Optional.empty(), null));
        when(normalizer.normalizeSourceId(anyString(), anyString())).thenReturn("openstreetmap_node/1");
        when(persistenceService.createLeadForCampaign(any(), any())).thenReturn(Lead.builder().id(10L).build());

        var result = service.discoverAndPersist(campaign, 3);

        // Should not terminate early, should eventually succeed
        assertEquals(1, result.acceptedThisRun());
    }
}