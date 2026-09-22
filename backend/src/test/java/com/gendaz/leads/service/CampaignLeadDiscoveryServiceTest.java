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
import org.mockito.ArgumentCaptor;
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
import org.mockito.InOrder;

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
        setField(service, "regionBudgetMs", 30000L);
        setField(service, "fallbackRegionBudgetMs", 20000L);
        setField(service, "maxRegionDeferrals", 1);

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
        lenient().when(osm.resolveScope(any(), any())).thenReturn(scope);
    }

    private void setField(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    @Test
    void noPhoneNotPersistedAndContinues() {
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
    void emailWithoutPhoneRejected() {
        LeadCandidate candidate = new LeadCandidate("Barbearia", "openstreetmap", "node/1");
        candidate.setCategory("shop=barber");
        candidate.setEmail("contato@barbearia.com");

        lenient().when(osm.queryRegionWithStrategy(any(), any(), any(), any(), any(), anyInt(), any(), anyLong()))
                .thenReturn(AreaQueryResult.success(List.of(candidate), false, "overpass-api.de", 100));
        when(deduplicationService.check(any())).thenReturn(new DeduplicationService.DuplicateCheck(Optional.empty(), null));
        when(normalizer.normalizeSourceId(anyString(), anyString())).thenReturn("openstreetmap_node/1");
        when(persistenceService.createLeadForCampaign(any(), any())).thenReturn(Lead.builder().id(10L).build());

        var result = service.discoverAndPersist(campaign, 3);

        assertEquals(0, result.acceptedThisRun());
    }

    @Test
    void websiteWithoutPhoneRejected() {
        LeadCandidate candidate = new LeadCandidate("Barbearia", "openstreetmap", "node/1");
        candidate.setCategory("shop=barber");
        candidate.setWebsite("https://barbearia.com");

        lenient().when(osm.queryRegionWithStrategy(any(), any(), any(), any(), any(), anyInt(), any(), anyLong()))
                .thenReturn(AreaQueryResult.success(List.of(candidate), false, "overpass-api.de", 100));
        when(deduplicationService.check(any())).thenReturn(new DeduplicationService.DuplicateCheck(Optional.empty(), null));
        when(normalizer.normalizeSourceId(anyString(), anyString())).thenReturn("openstreetmap_node/1");
        when(persistenceService.createLeadForCampaign(any(), any())).thenReturn(Lead.builder().id(10L).build());

        var result = service.discoverAndPersist(campaign, 3);

        assertEquals(0, result.acceptedThisRun());
    }

    @Test
    void instagramWithoutPhoneRejected() {
        LeadCandidate candidate = new LeadCandidate("Barbearia", "openstreetmap", "node/1");
        candidate.setCategory("shop=barber");
        candidate.setInstagramUsername("barbearia");

        lenient().when(osm.queryRegionWithStrategy(any(), any(), any(), any(), any(), anyInt(), any(), anyLong()))
                .thenReturn(AreaQueryResult.success(List.of(candidate), false, "overpass-api.de", 100));
        when(deduplicationService.check(any())).thenReturn(new DeduplicationService.DuplicateCheck(Optional.empty(), null));
        when(normalizer.normalizeSourceId(anyString(), anyString())).thenReturn("openstreetmap_node/1");
        when(persistenceService.createLeadForCampaign(any(), any())).thenReturn(Lead.builder().id(10L).build());

        var result = service.discoverAndPersist(campaign, 3);

        assertEquals(0, result.acceptedThisRun());
    }

    @Test
    void budgetExhaustedNotEmpty() {
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

        assertEquals(1, result.acceptedThisRun());
    }

    @Test
    void existingGlobalLeadIsRejectedAndNotLinked() {
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
        when(campaignLeadRepository.countByCampaignId(1L)).thenReturn(0L, 1L);

        var result = service.discoverAndPersist(campaign, 3);

        assertEquals(0, result.acceptedThisRun());
        verify(persistenceService, never()).createLeadForCampaign(any(), any());
    }

    @Test
    void existingGlobalLeadAlreadyInCampaignNotAccepted() {
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

        var result = service.discoverAndPersist(campaign, 3);

        assertEquals(0, result.acceptedThisRun());
        verify(persistenceService, never()).createLeadForCampaign(any(), any());
    }

    @Test
    void existingLeadWithDoNotContactNotReused() {
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
    }

    @Test
    void existingLeadWithoutPhoneNotReused() {
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
    }

    @Test
    void duplicateAfterEnrichmentIsRejected() {
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
        when(deduplicationService.check(any()))
                .thenReturn(new DeduplicationService.DuplicateCheck(Optional.empty(), null))
                .thenReturn(new DeduplicationService.DuplicateCheck(Optional.of(existing), "website"));
        when(normalizer.normalizeSourceId(anyString(), anyString())).thenReturn("openstreetmap_node/1");
        when(normalizer.normalizeWebsite(anyString())).thenReturn("https://barbeariax.com");
        when(campaignLeadRepository.countByCampaignId(1L)).thenReturn(0L, 1L);

        var result = service.discoverAndPersist(campaign, 3);

        assertEquals(0, result.acceptedThisRun());
        verify(persistenceService, never()).createLeadForCampaign(any(), any());
    }

    @Test
    void websiteEnrichmentFindsPhoneAndAccepted() {
        LeadCandidate candidate = new LeadCandidate("Barbearia X", "openstreetmap", "node/1");
        candidate.setCategory("shop=barber");
        candidate.setWebsite("https://barbeariax.com");

        lenient().when(osm.queryRegionWithStrategy(any(), any(), any(), any(), any(), anyInt(), any(), anyLong()))
                .thenReturn(AreaQueryResult.success(List.of(candidate), false, "overpass-api.de", 100));
        when(deduplicationService.check(any()))
                .thenReturn(new DeduplicationService.DuplicateCheck(Optional.empty(), null));
        when(normalizer.normalizeSourceId(anyString(), anyString())).thenReturn("openstreetmap_node/1");
        when(normalizer.normalizeWebsite(anyString())).thenReturn("https://barbeariax.com");
        when(websiteContactEnricher.enrich(anyString()))
                .thenReturn(new WebsiteContactEnricher.WebsiteContactData("+55 65 99999-9999", "email@test.com", "insta"));
        when(persistenceService.createLeadForCampaign(any(), any())).thenReturn(Lead.builder().id(10L).build());

        var result = service.discoverAndPersist(campaign, 3);

        assertEquals(1, result.acceptedThisRun());
        verify(persistenceService).createLeadForCampaign(any(), any());
    }

    @Test
    void structuredWithCandidatesSkipsNameFallback() {
        LeadCandidate candidate = new LeadCandidate("Barbearia", "openstreetmap", "node/1");
        candidate.setCategory("shop=barber");
        candidate.setPhone("+55 65 9999-8888");

        lenient().when(osm.queryRegionWithStrategy(any(), any(), any(), any(), eq(AreaQueryPhase.STRUCTURED), anyInt(), any(), anyLong()))
                .thenReturn(AreaQueryResult.success(List.of(candidate), false, "overpass-api.de", 100));
        when(deduplicationService.check(any())).thenReturn(new DeduplicationService.DuplicateCheck(Optional.empty(), null));
        when(normalizer.normalizeSourceId(anyString(), anyString())).thenReturn("openstreetmap_node/1");
        when(persistenceService.createLeadForCampaign(any(), any())).thenReturn(Lead.builder().id(10L).build());

        var result = service.discoverAndPersist(campaign, 3);

        assertEquals(1, result.acceptedThisRun());
        verify(osm, never()).queryRegionWithStrategy(any(), any(), any(), any(), eq(AreaQueryPhase.NAME_FALLBACK), anyInt(), any(), anyLong());
    }

    @Test
    void structuredEmptyCallsNameFallback() {
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

        assertTrue(result.acceptedThisRun() >= 1);
        verify(osm, atLeastOnce()).queryRegionWithStrategy(any(), any(), any(), any(), eq(AreaQueryPhase.NAME_FALLBACK), anyInt(), any(), anyLong());
    }

    @Test
    void nicheWithoutStructuredUsesNameFallback() {
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
    void structuredComesBeforeNameFallbackTwoPhaseOrder() {
        LeadCandidate candidate1 = new LeadCandidate("Barbearia A", "openstreetmap", "node/1");
        candidate1.setCategory("shop=barber");
        candidate1.setPhone("+55 65 9999-1111");

        LeadCandidate candidate2 = new LeadCandidate("Barbearia B", "openstreetmap", "node/2");
        candidate2.setCategory("shop=barber");
        candidate2.setPhone("+55 65 9999-2222");

        LeadCandidate fallbackCandidate = new LeadCandidate("Barbearia Fallback", "openstreetmap", "node/3");
        fallbackCandidate.setCategory("shop=barber");
        fallbackCandidate.setPhone("+55 65 9999-3333");

        lenient().when(osm.queryRegionWithStrategy(any(), any(), any(), any(), eq(AreaQueryPhase.STRUCTURED), anyInt(), any(), anyLong()))
                .thenReturn(
                        AreaQueryResult.success(List.of(candidate1), false, "overpass-api.de", 100),
                        AreaQueryResult.success(List.of(), false, "overpass-api.de", 100),
                        AreaQueryResult.success(List.of(candidate2), false, "overpass-api.de", 100),
                        AreaQueryResult.success(List.of(), false, "overpass-api.de", 100),
                        AreaQueryResult.success(List.of(), false, "overpass-api.de", 100)
                );
        lenient().when(osm.queryRegionWithStrategy(any(), any(), any(), any(), eq(AreaQueryPhase.NAME_FALLBACK), anyInt(), any(), anyLong()))
                .thenReturn(AreaQueryResult.success(List.of(fallbackCandidate), false, "overpass-api.de", 100));

        when(deduplicationService.check(any())).thenReturn(new DeduplicationService.DuplicateCheck(Optional.empty(), null));
        when(normalizer.normalizeSourceId(anyString(), anyString())).thenReturn("openstreetmap_node/1", "openstreetmap_node/2", "openstreetmap_node/3", "openstreetmap_node/4", "openstreetmap_node/5");
        when(persistenceService.createLeadForCampaign(any(), any())).thenReturn(Lead.builder().id(10L).build());

        var result = service.discoverAndPersist(campaign, 3);

        assertEquals(3, result.acceptedThisRun());

        InOrder inOrder = inOrder(osm);
        inOrder.verify(osm, atLeast(3)).queryRegionWithStrategy(any(), any(), any(), any(), eq(AreaQueryPhase.STRUCTURED), anyInt(), any(), anyLong());
        inOrder.verify(osm, atLeastOnce()).queryRegionWithStrategy(any(), any(), any(), any(), eq(AreaQueryPhase.NAME_FALLBACK), anyInt(), any(), anyLong());
    }

    @Test
    void structuredWithCandidateDoesNotQueueFallback() {
        LeadCandidate candidate = new LeadCandidate("Barbearia", "openstreetmap", "node/1");
        candidate.setCategory("shop=barber");
        candidate.setPhone("+55 65 9999-8888");

        lenient().when(osm.queryRegionWithStrategy(any(), any(), any(), any(), eq(AreaQueryPhase.STRUCTURED), anyInt(), any(), anyLong()))
                .thenReturn(AreaQueryResult.success(List.of(candidate), false, "overpass-api.de", 100));
        when(deduplicationService.check(any())).thenReturn(new DeduplicationService.DuplicateCheck(Optional.empty(), null));
        when(normalizer.normalizeSourceId(anyString(), anyString())).thenReturn("openstreetmap_node/1");
        when(persistenceService.createLeadForCampaign(any(), any())).thenReturn(Lead.builder().id(10L).build());

        var result = service.discoverAndPersist(campaign, 3);

        assertEquals(1, result.acceptedThisRun());
        verify(osm, never()).queryRegionWithStrategy(any(), any(), any(), any(), eq(AreaQueryPhase.NAME_FALLBACK), anyInt(), any(), anyLong());
    }

    @Test
    void regionWithTimeoutIsDeferred() {
        LeadCandidate candidate = new LeadCandidate("Barbearia", "openstreetmap", "node/1");
        candidate.setCategory("shop=barber");
        candidate.setPhone("+55 65 9999-8888");

        lenient().when(osm.queryRegionWithStrategy(any(), any(), any(), any(), eq(AreaQueryPhase.STRUCTURED), anyInt(), any(), anyLong()))
                .thenReturn(
                        AreaQueryResult.infraUnavailableWithAttempt("OSM_DISCOVERY_TIMEOUT", "Timeout na região A", 5000),
                        AreaQueryResult.success(List.of(candidate), false, "overpass-api.de", 100)
                );
        when(deduplicationService.check(any())).thenReturn(new DeduplicationService.DuplicateCheck(Optional.empty(), null));
        when(normalizer.normalizeSourceId(anyString(), anyString())).thenReturn("openstreetmap_node/1");
        when(persistenceService.createLeadForCampaign(any(), any())).thenReturn(Lead.builder().id(10L).build());

        var result = service.discoverAndPersist(campaign, 3);

        assertEquals(1, result.acceptedThisRun());
    }

    @Test
    void maxDeferralPreventsInfiniteLoop() {
        LeadCandidate candidate = new LeadCandidate("Barbearia", "openstreetmap", "node/1");
        candidate.setCategory("shop=barber");
        candidate.setPhone("+55 65 9999-8888");

        lenient().when(osm.queryRegionWithStrategy(any(), any(), any(), any(), eq(AreaQueryPhase.STRUCTURED), anyInt(), any(), anyLong()))
                .thenReturn(
                        AreaQueryResult.infraUnavailableWithAttempt("OSM_DISCOVERY_TIMEOUT", "Timeout 1", 5000),
                        AreaQueryResult.infraUnavailableWithAttempt("OSM_DISCOVERY_TIMEOUT", "Timeout 2", 5000),
                        AreaQueryResult.success(List.of(candidate), false, "overpass-api.de", 100)
                );
        when(deduplicationService.check(any())).thenReturn(new DeduplicationService.DuplicateCheck(Optional.empty(), null));
        when(normalizer.normalizeSourceId(anyString(), anyString())).thenReturn("openstreetmap_node/1");
        when(persistenceService.createLeadForCampaign(any(), any())).thenReturn(Lead.builder().id(10L).build());

        var result = service.discoverAndPersist(campaign, 3);

        assertEquals(1, result.acceptedThisRun());
    }

    @Test
    void regionBudgetUsedForStructured() {
        LeadCandidate candidate = new LeadCandidate("Barbearia", "openstreetmap", "node/1");
        candidate.setCategory("shop=barber");
        candidate.setPhone("+55 65 9999-8888");

        lenient().when(osm.queryRegionWithStrategy(any(), any(), any(), any(), eq(AreaQueryPhase.STRUCTURED), anyInt(), any(), anyLong()))
                .thenReturn(AreaQueryResult.success(List.of(candidate), false, "overpass-api.de", 100));
        when(deduplicationService.check(any())).thenReturn(new DeduplicationService.DuplicateCheck(Optional.empty(), null));
        when(normalizer.normalizeSourceId(anyString(), anyString())).thenReturn("openstreetmap_node/1");
        when(persistenceService.createLeadForCampaign(any(), any())).thenReturn(Lead.builder().id(10L).build());

        service.discoverAndPersist(campaign, 3);

        ArgumentCaptor<DiscoveryBudget> budgetCaptor = ArgumentCaptor.forClass(DiscoveryBudget.class);
        verify(osm, atLeastOnce()).queryRegionWithStrategy(any(), any(), any(), any(), eq(AreaQueryPhase.STRUCTURED), anyInt(), budgetCaptor.capture(), anyLong());

        DiscoveryBudget captured = budgetCaptor.getValue();
        assertTrue(captured.totalMs() <= 30000L, "Region budget for STRUCTURED should be <= 30000ms, was " + captured.totalMs());
        assertTrue(captured.totalMs() > 0L, "Region budget should be > 0");
    }

    @Test
    void fallbackBudgetUsedForNameFallback() {
        LeadCandidate candidate = new LeadCandidate("Barbearia", "openstreetmap", "node/1");
        candidate.setCategory("shop=barber");
        candidate.setPhone("+55 65 9999-8888");

        lenient().when(osm.queryRegionWithStrategy(any(), any(), any(), any(), eq(AreaQueryPhase.STRUCTURED), anyInt(), any(), anyLong()))
                .thenReturn(AreaQueryResult.success(List.of(), false, "overpass-api.de", 100));
        lenient().when(osm.queryRegionWithStrategy(any(), any(), any(), any(), eq(AreaQueryPhase.NAME_FALLBACK), anyInt(), any(), anyLong()))
                .thenReturn(AreaQueryResult.success(List.of(candidate), false, "overpass-api.de", 100));
        when(deduplicationService.check(any())).thenReturn(new DeduplicationService.DuplicateCheck(Optional.empty(), null));
        when(normalizer.normalizeSourceId(anyString(), anyString())).thenReturn("openstreetmap_node/1");
        when(persistenceService.createLeadForCampaign(any(), any())).thenReturn(Lead.builder().id(10L).build());

        service.discoverAndPersist(campaign, 3);

        ArgumentCaptor<DiscoveryBudget> budgetCaptor = ArgumentCaptor.forClass(DiscoveryBudget.class);
        verify(osm, atLeastOnce()).queryRegionWithStrategy(any(), any(), any(), any(), eq(AreaQueryPhase.NAME_FALLBACK), anyInt(), budgetCaptor.capture(), anyLong());

        DiscoveryBudget captured = budgetCaptor.getValue();
        assertTrue(captured.totalMs() <= 20000L, "Fallback budget for NAME_FALLBACK should be <= 20000ms, was " + captured.totalMs());
        assertTrue(captured.totalMs() > 0L, "Fallback budget should be > 0");
    }

    @Test
    void globalBudgetForThreeLeadsUsesNewPolicy() {
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
        LeadCandidate candidate = new LeadCandidate("Barbearia", "openstreetmap", "node/1");
        candidate.setCategory("shop=barber");
        candidate.setPhone("+55 65 9999-8888");

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