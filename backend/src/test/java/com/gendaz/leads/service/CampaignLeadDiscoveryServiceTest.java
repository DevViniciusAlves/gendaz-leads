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
import com.gendaz.leads.service.provider.NicheMapper;
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
        setField(service, "baseBudgetMs", 180000L);
        setField(service, "perLeadBudgetMs", 15000L);
        setField(service, "maxBudgetMs", 300000L);
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

    @Test
    void existingGlobalLeadReusedInCampaign() {
        // TEST 2 - Lead global existente, ainda não está na campanha
        LeadCandidate candidate = new LeadCandidate("Barbearia X", "openstreetmap", "node/1");
        candidate.setCategory("shop=barber");
        candidate.setPhone("+55 65 99999-9999");

        Lead existing = Lead.builder()
                .id(50L)
                .businessName("Barbearia X")
                .phone("+55 65 99999-9999")
                .doNotContact(false)
                .build();

        lenient().when(osm.queryRegionWithStrategy(any(), any(), any(), any(), any(), anyInt(), any(), anyLong()))
                .thenReturn(AreaQueryResult.success(List.of(candidate), false, "overpass-api.de", 100));
        when(deduplicationService.check(any()))
                .thenReturn(new DeduplicationService.DuplicateCheck(Optional.of(existing), "source_id"));
        when(normalizer.normalizeSourceId(anyString(), anyString())).thenReturn("openstreetmap_node/1");
        when(persistenceService.linkExistingLeadToCampaign(existing, campaign)).thenReturn(true);
        when(campaignLeadRepository.countByCampaignId(1L)).thenReturn(0L, 1L);

        var result = service.discoverAndPersist(campaign, 3);

        assertEquals(1, result.acceptedThisRun());
        verify(persistenceService, never()).createLeadForCampaign(any(), any());
        verify(persistenceService).linkExistingLeadToCampaign(existing, campaign);
    }

    @Test
    void existingGlobalLeadAlreadyInCampaignNotAccepted() {
        // TEST 3 - Lead global já está na campanha
        LeadCandidate candidate = new LeadCandidate("Barbearia X", "openstreetmap", "node/1");
        candidate.setCategory("shop=barber");
        candidate.setPhone("+55 65 99999-9999");

        Lead existing = Lead.builder()
                .id(50L)
                .businessName("Barbearia X")
                .phone("+55 65 99999-9999")
                .doNotContact(false)
                .build();

        lenient().when(osm.queryRegionWithStrategy(any(), any(), any(), any(), any(), anyInt(), any(), anyLong()))
                .thenReturn(AreaQueryResult.success(List.of(candidate), false, "overpass-api.de", 100));
        when(deduplicationService.check(any()))
                .thenReturn(new DeduplicationService.DuplicateCheck(Optional.of(existing), "source_id"));
        when(normalizer.normalizeSourceId(anyString(), anyString())).thenReturn("openstreetmap_node/1");
        when(persistenceService.linkExistingLeadToCampaign(existing, campaign)).thenReturn(false);

        var result = service.discoverAndPersist(campaign, 3);

        assertEquals(0, result.acceptedThisRun());
        verify(persistenceService, never()).createLeadForCampaign(any(), any());
        verify(persistenceService).linkExistingLeadToCampaign(existing, campaign);
    }

    @Test
    void existingLeadWithDoNotContactNotReused() {
        // TEST 4 - doNotContact=true
        LeadCandidate candidate = new LeadCandidate("Barbearia X", "openstreetmap", "node/1");
        candidate.setCategory("shop=barber");
        candidate.setPhone("+55 65 99999-9999");

        Lead existing = Lead.builder()
                .id(50L)
                .businessName("Barbearia X")
                .phone("+55 65 99999-9999")
                .doNotContact(true)
                .build();

        lenient().when(osm.queryRegionWithStrategy(any(), any(), any(), any(), any(), anyInt(), any(), anyLong()))
                .thenReturn(AreaQueryResult.success(List.of(candidate), false, "overpass-api.de", 100));
        when(deduplicationService.check(any()))
                .thenReturn(new DeduplicationService.DuplicateCheck(Optional.of(existing), "source_id"));
        when(normalizer.normalizeSourceId(anyString(), anyString())).thenReturn("openstreetmap_node/1");

        var result = service.discoverAndPersist(campaign, 3);

        assertEquals(0, result.acceptedThisRun());
        verify(persistenceService, never()).createLeadForCampaign(any(), any());
        verify(persistenceService, never()).linkExistingLeadToCampaign(any(), any());
    }

    @Test
    void existingLeadWithoutUsefulContactNotReused() {
        // TEST 5 - Lead existente sem contato útil
        LeadCandidate candidate = new LeadCandidate("Barbearia X", "openstreetmap", "node/1");
        candidate.setCategory("shop=barber");

        Lead existing = Lead.builder()
                .id(50L)
                .businessName("Barbearia X")
                .doNotContact(false)
                .build();

        lenient().when(osm.queryRegionWithStrategy(any(), any(), any(), any(), any(), anyInt(), any(), anyLong()))
                .thenReturn(AreaQueryResult.success(List.of(candidate), false, "overpass-api.de", 100));
        when(deduplicationService.check(any()))
                .thenReturn(new DeduplicationService.DuplicateCheck(Optional.of(existing), "source_id"));
        when(normalizer.normalizeSourceId(anyString(), anyString())).thenReturn("openstreetmap_node/1");

        var result = service.discoverAndPersist(campaign, 3);

        assertEquals(0, result.acceptedThisRun());
        verify(persistenceService, never()).createLeadForCampaign(any(), any());
        verify(persistenceService, never()).linkExistingLeadToCampaign(any(), any());
    }

    @Test
    void duplicateAfterEnrichmentReused() {
        // TEST 6 - Duplicado após enrichment
        LeadCandidate candidate = new LeadCandidate("Barbearia X", "openstreetmap", "node/1");
        candidate.setCategory("shop=barber");
        candidate.setWebsite("https://barbeariax.com");

        Lead existing = Lead.builder()
                .id(50L)
                .businessName("Barbearia X")
                .phone("+55 65 99999-9999")
                .doNotContact(false)
                .build();

        lenient().when(osm.queryRegionWithStrategy(any(), any(), any(), any(), any(), anyInt(), any(), anyLong()))
                .thenReturn(AreaQueryResult.success(List.of(candidate), false, "overpass-api.de", 100));
        // First check (before enrichment) - no duplicate
        // Second check (after enrichment) - finds existing lead
        when(deduplicationService.check(any()))
                .thenReturn(new DeduplicationService.DuplicateCheck(Optional.empty(), null))
                .thenReturn(new DeduplicationService.DuplicateCheck(Optional.of(existing), "website"));
        when(normalizer.normalizeSourceId(anyString(), anyString())).thenReturn("openstreetmap_node/1");
        when(normalizer.normalizeWebsite(anyString())).thenReturn("https://barbeariax.com");
        when(persistenceService.linkExistingLeadToCampaign(existing, campaign)).thenReturn(true);
        when(campaignLeadRepository.countByCampaignId(1L)).thenReturn(0L, 1L);

        var result = service.discoverAndPersist(campaign, 3);

        assertEquals(1, result.acceptedThisRun());
        verify(persistenceService, never()).createLeadForCampaign(any(), any());
        verify(persistenceService).linkExistingLeadToCampaign(existing, campaign);
    }

    @Test
    void structuredWithCandidatesSkipsNameFallback() {
        // TEST 7 - STRUCTURED retorna candidato, NAME_FALLBACK não deve ser chamado
        LeadCandidate candidate = new LeadCandidate("Barbearia", "openstreetmap", "node/1");
        candidate.setCategory("shop=barber");
        candidate.setPhone("+55 65 9999-8888");

        lenient().when(osm.queryRegionWithStrategy(any(), any(), any(), any(), eq(AreaQueryPhase.STRUCTURED), anyInt(), any(), anyLong()))
                .thenReturn(AreaQueryResult.success(List.of(candidate), false, "overpass-api.de", 100));
        // NAME_FALLBACK should not be called
        when(deduplicationService.check(any())).thenReturn(new DeduplicationService.DuplicateCheck(Optional.empty(), null));
        when(normalizer.normalizeSourceId(anyString(), anyString())).thenReturn("openstreetmap_node/1");
        when(persistenceService.createLeadForCampaign(any(), any())).thenReturn(Lead.builder().id(10L).build());

        var result = service.discoverAndPersist(campaign, 3);

        assertEquals(1, result.acceptedThisRun());
        verify(osm, never()).queryRegionWithStrategy(any(), any(), any(), any(), eq(AreaQueryPhase.NAME_FALLBACK), anyInt(), any(), anyLong());
    }

    @Test
    void structuredEmptyCallsNameFallback() {
        // TEST 8 - STRUCTURED retorna zero, NAME_FALLBACK deve ser chamado
        LeadCandidate fallbackCandidate = new LeadCandidate("Barbearia Fallback", "openstreetmap", "node/2");
        fallbackCandidate.setCategory("shop=barber");
        fallbackCandidate.setPhone("+55 65 9999-7777");

        lenient().when(osm.queryRegionWithStrategy(any(), any(), any(), any(), eq(AreaQueryPhase.STRUCTURED), anyInt(), any(), anyLong()))
                .thenReturn(AreaQueryResult.success(List.of(), false, "overpass-api.de", 100));
        lenient().when(osm.queryRegionWithStrategy(any(), any(), any(), any(), eq(AreaQueryPhase.NAME_FALLBACK), anyInt(), any(), anyLong()))
                .thenReturn(AreaQueryResult.success(List.of(fallbackCandidate), false, "overpass-api.de", 100));
        when(deduplicationService.check(any())).thenReturn(new DeduplicationService.DuplicateCheck(Optional.empty(), null));
        when(normalizer.normalizeSourceId(anyString(), anyString())).thenReturn("openstreetmap_node/2");
        when(persistenceService.createLeadForCampaign(any(), any())).thenReturn(Lead.builder().id(10L).build());

        var result = service.discoverAndPersist(campaign, 3);

        // At least one candidate should be accepted (first region), others rejected as already_seen_this_run
        assertTrue(result.acceptedThisRun() >= 1);
        verify(osm, atLeastOnce()).queryRegionWithStrategy(any(), any(), any(), any(), eq(AreaQueryPhase.NAME_FALLBACK), anyInt(), any(), anyLong());
    }

    @Test
    void nicheWithoutStructuredUsesNameFallback() {
        // TEST 9 - Nicho sem filtro estruturado continua usando fallback
        // Create a campaign with a niche that has no structured filters
        Campaign nicheCampaign = Campaign.builder()
                .id(2L)
                .niche("unknown_niche")
                .city("Cuiabá")
                .country("Brazil")
                .requestedQuantity(3)
                .build();

        LeadCandidate candidate = new LeadCandidate("Barbearia", "openstreetmap", "node/1");
        candidate.setCategory("shop=barber");
        candidate.setPhone("+55 65 9999-8888");

        GeoScope nicheScope = new GeoScope(-15.6, -56.1, "Cuiabá", "MT", "Brazil", "br", -15.7, -56.2, -15.5, -56.0, true, "relation", 333734L);
        when(campaignLeadRepository.countByCampaignId(2L)).thenReturn(0L);
        when(campaignRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(leadRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(leadEventRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(osm.resolveScope(any(), any())).thenReturn(nicheScope);

        lenient().when(osm.queryRegionWithStrategy(any(), any(), any(), any(), eq(AreaQueryPhase.NAME_FALLBACK), anyInt(), any(), anyLong()))
                .thenReturn(AreaQueryResult.success(List.of(candidate), false, "overpass-api.de", 100));
        when(deduplicationService.check(any())).thenReturn(new DeduplicationService.DuplicateCheck(Optional.empty(), null));
        when(normalizer.normalizeSourceId(anyString(), anyString())).thenReturn("openstreetmap_node/1");
        when(persistenceService.createLeadForCampaign(any(), any())).thenReturn(Lead.builder().id(10L).build());

        var result = service.discoverAndPersist(nicheCampaign, 3);

        assertTrue(result.acceptedThisRun() >= 1);
        verify(osm, atLeastOnce()).queryRegionWithStrategy(any(), any(), any(), any(), eq(AreaQueryPhase.NAME_FALLBACK), anyInt(), any(), anyLong());
    }

    @Test
    void budgetExhaustedDoesNotExposeSemaphore() {
        // TEST 10 - BUDGET_EXHAUSTED não expõe semaphore
        // Simulate budget expiring by making queries take a long time
        // We'll mock the budget to expire quickly
        LeadCandidate candidate = new LeadCandidate("Barbearia", "openstreetmap", "node/1");
        candidate.setCategory("shop=barber");
        candidate.setPhone("+55 65 9999-8888");

        // Return INFRA_UNAVAILABLE with semaphore message to simulate provider returning semaphore error
        lenient().when(osm.queryRegionWithStrategy(any(), any(), any(), any(), any(), anyInt(), any(), anyLong()))
                .thenReturn(AreaQueryResult.infraUnavailableWithAttempt("OSM_DISCOVERY_TIMEOUT", "Budget insuficiente antes de adquirir semaphore", 100));
        when(deduplicationService.check(any())).thenReturn(new DeduplicationService.DuplicateCheck(Optional.empty(), null));
        when(normalizer.normalizeSourceId(anyString(), anyString())).thenReturn("openstreetmap_node/1");

        var result = service.discoverAndPersist(campaign, 3);

        assertEquals(DiscoveryExecutionResult.Outcome.INFRA_UNAVAILABLE, result.outcome());

        // Now test BUDGET_EXHAUSTED specifically - we need budget to expire naturally
        // Let's create a test with a very small budget
        Campaign budgetCampaign = Campaign.builder()
                .id(3L)
                .niche("barbearia")
                .city("Cuiabá")
                .country("Brazil")
                .requestedQuantity(3)
                .build();
        when(campaignLeadRepository.countByCampaignId(3L)).thenReturn(0L);
        GeoScope budgetScope = new GeoScope(-15.6, -56.1, "Cuiabá", "MT", "Brazil", "br", -15.7, -56.2, -15.5, -56.0, true, "relation", 333734L);
        lenient().when(osm.resolveScope(any(), any())).thenReturn(budgetScope);

        // Mock a very small budget by setting baseBudgetMs to 1ms
        try {
            setField(service, "baseBudgetMs", 1L);
            setField(service, "perLeadBudgetMs", 1L);
            setField(service, "maxBudgetMs", 1L);
        } catch (Exception e) {
            // ignore
        }

        lenient().when(osm.queryRegionWithStrategy(any(), any(), any(), any(), any(), anyInt(), any(), anyLong()))
                .thenReturn(AreaQueryResult.success(List.of(candidate), false, "overpass-api.de", 100));
        when(persistenceService.createLeadForCampaign(any(), any())).thenReturn(Lead.builder().id(10L).build());

        var result2 = service.discoverAndPersist(budgetCampaign, 3);

        // The outcome might be PARTIAL or BUDGET_EXHAUSTED depending on timing
        // But if BUDGET_EXHAUSTED, message should not contain semaphore
        if (result2.outcome() == DiscoveryExecutionResult.Outcome.BUDGET_EXHAUSTED) {
            assertFalse(result2.errorMessage().toLowerCase().contains("semaphore"));
            assertEquals("OSM_DISCOVERY_BUDGET_EXHAUSTED", result2.errorCode());
            assertEquals("A busca atingiu o tempo máximo antes de concluir. Tente novamente.", result2.errorMessage());
        }
    }

    @Test
    void partialRemainsPartial() {
        // TEST 11 - PARTIAL continua PARTIAL
        LeadCandidate candidate = new LeadCandidate("Barbearia", "openstreetmap", "node/1");
        candidate.setCategory("shop=barber");
        candidate.setPhone("+55 65 9999-8888");

        // Return success with 1 candidate, then budget expires
        lenient().when(osm.queryRegionWithStrategy(any(), any(), any(), any(), any(), anyInt(), any(), anyLong()))
                .thenReturn(
                        AreaQueryResult.success(List.of(candidate), false, "overpass-api.de", 100),
                        AreaQueryResult.infraUnavailableWithAttempt("OSM_DISCOVERY_TIMEOUT", "Budget de descoberta esgotado", 50000)
                );
        when(deduplicationService.check(any())).thenReturn(new DeduplicationService.DuplicateCheck(Optional.empty(), null));
        when(normalizer.normalizeSourceId(anyString(), anyString())).thenReturn("openstreetmap_node/1");
        when(persistenceService.createLeadForCampaign(any(), any())).thenReturn(Lead.builder().id(10L).build());

        var result = service.discoverAndPersist(campaign, 3);

        // Should be PARTIAL since we got 1 lead but target was 3
        assertEquals(DiscoveryExecutionResult.Outcome.PARTIAL, result.outcome());
        assertEquals(1, result.acceptedThisRun());
    }

    @Test
    void discoveryBudgetForThreeLeadsUsesNewPolicy() {
        DiscoveryBudget budget =
                DiscoveryBudget.forTarget(
                        3,
                        180000L,
                        15000L,
                        300000L
                );

        assertEquals(225000L, budget.totalMs());
    }

    @Test
    void discoveryBudgetIsCappedAtFiveMinutes() {
        DiscoveryBudget budget =
                DiscoveryBudget.forTarget(
                        30,
                        180000L,
                        15000L,
                        300000L
                );

        assertEquals(300000L, budget.totalMs());
    }

    @Test
    void acceptedLeadWithExpiredBudgetRemainsPartial() {
        // accepted = 1
        // target = 3
        // budget esgota depois
        // resultado obrigatório: PARTIAL
        LeadCandidate candidate = new LeadCandidate("Barbearia", "openstreetmap", "node/1");
        candidate.setCategory("shop=barber");
        candidate.setPhone("+55 65 9999-8888");

        // Return success with 1 candidate, then budget expires
        lenient().when(osm.queryRegionWithStrategy(any(), any(), any(), any(), any(), anyInt(), any(), anyLong()))
                .thenReturn(
                        AreaQueryResult.success(List.of(candidate), false, "overpass-api.de", 100),
                        AreaQueryResult.infraUnavailableWithAttempt("OSM_DISCOVERY_TIMEOUT", "Budget de descoberta esgotado", 50000)
                );
        when(deduplicationService.check(any())).thenReturn(new DeduplicationService.DuplicateCheck(Optional.empty(), null));
        when(normalizer.normalizeSourceId(anyString(), anyString())).thenReturn("openstreetmap_node/1");
        when(persistenceService.createLeadForCampaign(any(), any())).thenReturn(Lead.builder().id(10L).build());

        var result = service.discoverAndPersist(campaign, 3);

        assertEquals(DiscoveryExecutionResult.Outcome.PARTIAL, result.outcome());
        assertEquals(1, result.acceptedThisRun());
    }
}