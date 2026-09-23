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
import com.gendaz.leads.service.provider.LocalOsmCatalogProvider;
import com.gendaz.leads.service.provider.OpenStreetMapProvider;
import com.gendaz.leads.service.provider.SearchRegion;
import com.gendaz.leads.service.WhatsAppRecipientNormalizer;
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
    @Mock OpenStreetMapProvider osm;
    @Mock LocalOsmCatalogProvider localCatalogProvider;

    private Normalizer normalizer;
    private WhatsAppRecipientNormalizer whatsAppRecipientNormalizer;

    private CampaignLeadDiscoveryService service;
    private Campaign campaign;
    private GeoScope scope;
    private SearchRegion region;

    @BeforeEach
    void setUp() throws Exception {
        normalizer = new Normalizer();
        whatsAppRecipientNormalizer = new WhatsAppRecipientNormalizer();
        service = new CampaignLeadDiscoveryService(
                campaignRepository, campaignLeadRepository, leadRepository, leadEventRepository,
                deduplicationService, persistenceService, websiteContactEnricher, whatsAppRecipientNormalizer, normalizer, osm,
                localCatalogProvider
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
        setField(service, "regionBudgetMs", 15000L);
        setField(service, "fallbackRegionBudgetMs", 12000L);
        setField(service, "maxRegionDeferrals", 1);
        setField(service, "structuredBatchSize", 2);
        setField(service, "fallbackBatchSize", 2);

        campaign = Campaign.builder()
                .id(1L)
                .niche("barbearia")
                .city("CuiabÃƒÂ¡")
                .country("Brazil")
                .requestedQuantity(3)
                .build();

        scope = new GeoScope(-15.6, -56.1, "CuiabÃƒÂ¡", "MT", "Brazil", "br", -15.7, -56.2, -15.5, -56.0, true, "relation", 333734L);
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
                        AreaQueryResult.infraUnavailable("OSM_ALL_ENDPOINTS_FAILED", "Todos endpoints indisponÃƒÂ­veis", 100),
                        AreaQueryResult.success(List.of(candidate), false, "overpass-api.de", 100)
                );
        when(deduplicationService.check(any())).thenReturn(new DeduplicationService.DuplicateCheck(Optional.empty(), null));
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
        lenient().when(osm.queryRegionWithStrategy(any(), any(), any(), any(), eq(AreaQueryPhase.NAME_FALLBACK), anyInt(), any(), anyLong()))
                .thenReturn(AreaQueryResult.success(List.of(), false, "overpass-api.de", 100));
        when(deduplicationService.check(any())).thenReturn(new DeduplicationService.DuplicateCheck(Optional.empty(), null));
        when(persistenceService.createLeadForCampaign(any(), any())).thenReturn(Lead.builder().id(10L).build());

        // With target=1, we exit after first acceptance, so no fallback should be called
        var result = service.discoverAndPersist(campaign, 1);

        assertEquals(1, result.acceptedThisRun());
        verify(osm, never()).queryRegionWithStrategy(any(), any(), any(), any(), eq(AreaQueryPhase.NAME_FALLBACK), anyInt(), any(), anyLong());
    }

    @Test
    void structuredWithCandidatesButNoneAcceptedQueuesFallback() {
        // Candidate without phone - will be rejected
        LeadCandidate candidate = new LeadCandidate("Barbearia", "openstreetmap", "node/1");
        candidate.setCategory("shop=barber");
        // No phone set

        lenient().when(osm.queryRegionWithStrategy(any(), any(), any(), any(), eq(AreaQueryPhase.STRUCTURED), anyInt(), any(), anyLong()))
                .thenReturn(AreaQueryResult.success(List.of(candidate), false, "overpass-api.de", 100));
        lenient().when(osm.queryRegionWithStrategy(any(), any(), any(), any(), eq(AreaQueryPhase.NAME_FALLBACK), anyInt(), any(), anyLong()))
                .thenReturn(AreaQueryResult.success(List.of(), false, "overpass-api.de", 100));
        when(deduplicationService.check(any())).thenReturn(new DeduplicationService.DuplicateCheck(Optional.empty(), null));

        var result = service.discoverAndPersist(campaign, 3);

        assertEquals(0, result.acceptedThisRun());
        // When STRUCTURED returns candidates but NONE are accepted (no phone), fallback SHOULD be queued
        verify(osm, atLeastOnce()).queryRegionWithStrategy(any(), any(), any(), any(), eq(AreaQueryPhase.NAME_FALLBACK), anyInt(), any(), anyLong());
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
                .city("CuiabÃƒÂ¡")
                .country("Brazil")
                .requestedQuantity(3)
                .build();

        LeadCandidate candidate = new LeadCandidate("Barbearia", "openstreetmap", "node/1");
        candidate.setCategory("shop=barber");
        candidate.setPhone("+55 65 9999-8888");

        GeoScope nicheScope = new GeoScope(-15.6, -56.1, "CuiabÃƒÂ¡", "MT", "Brazil", "br", -15.7, -56.2, -15.5, -56.0, true, "relation", 333734L);
        when(campaignLeadRepository.countByCampaignId(2L)).thenReturn(0L);
        when(campaignRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(leadRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(leadEventRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(osm.resolveScope(any(), any())).thenReturn(nicheScope);

        lenient().when(osm.queryRegionWithStrategy(any(), any(), any(), any(), eq(AreaQueryPhase.NAME_FALLBACK), anyInt(), any(), anyLong()))
                .thenReturn(AreaQueryResult.success(List.of(candidate), false, "overpass-api.de", 100));
        when(deduplicationService.check(any())).thenReturn(new DeduplicationService.DuplicateCheck(Optional.empty(), null));
        when(persistenceService.createLeadForCampaign(any(), any())).thenReturn(Lead.builder().id(10L).build());

        var result = service.discoverAndPersist(nicheCampaign, 3);

        assertTrue(result.acceptedThisRun() >= 1);
        verify(osm, atLeastOnce()).queryRegionWithStrategy(any(), any(), any(), any(), eq(AreaQueryPhase.NAME_FALLBACK), anyInt(), any(), anyLong());
    }

@Test
    void structuredBatchYieldsToFallbackBeforeNextStructuredBatch() {
        // Test the batching behavior: 2 STRUCTURED -> 2 FALLBACK -> 2 STRUCTURED -> 2 FALLBACK...
        LeadCandidate structuredCandidate1 = new LeadCandidate("Barbearia A", "openstreetmap", "node/1");
        structuredCandidate1.setCategory("shop=barber");
        structuredCandidate1.setPhone("+55 65 9999-1111");

        LeadCandidate structuredCandidate2 = new LeadCandidate("Barbearia B", "openstreetmap", "node/2");
        structuredCandidate2.setCategory("shop=barber");
        structuredCandidate2.setPhone("+55 65 9999-2222");

        LeadCandidate fallbackCandidate = new LeadCandidate("Barbearia Fallback", "openstreetmap", "node/3");
        fallbackCandidate.setCategory("shop=barber");
        fallbackCandidate.setPhone("+55 65 99999-3333");
        lenient().when(osm.queryRegionWithStrategy(any(), any(), any(), any(), eq(AreaQueryPhase.STRUCTURED), anyInt(), any(), anyLong()))
                .thenReturn(
                        // Round 1 batch: 2 empty structured (both queue fallback)
                        AreaQueryResult.success(List.of(), false, "overpass-api.de", 100),
                        AreaQueryResult.success(List.of(), false, "overpass-api.de", 100),
                        // Round 2 batch: 2 structured with candidates
                        AreaQueryResult.success(List.of(structuredCandidate1), false, "overpass-api.de", 100),
                        AreaQueryResult.success(List.of(structuredCandidate2), false, "overpass-api.de", 100)
                );
        lenient().when(osm.queryRegionWithStrategy(any(), any(), any(), any(), eq(AreaQueryPhase.NAME_FALLBACK), anyInt(), any(), anyLong()))
                .thenReturn(
                        // Round 1 fallback batch: 2 fallback calls
                        AreaQueryResult.success(List.of(fallbackCandidate), false, "overpass-api.de", 100),
                        AreaQueryResult.success(List.of(), false, "overpass-api.de", 100)
                );

        when(deduplicationService.check(any())).thenReturn(new DeduplicationService.DuplicateCheck(Optional.empty(), null));
        when(persistenceService.createLeadForCampaign(any(), any())).thenReturn(Lead.builder().id(10L).build());

        var result = service.discoverAndPersist(campaign, 3);

        assertEquals(3, result.acceptedThisRun());

        ArgumentCaptor<AreaQueryPhase> phaseCaptor = ArgumentCaptor.forClass(AreaQueryPhase.class);
        verify(osm, atLeast(5)).queryRegionWithStrategy(any(), any(), any(), any(), phaseCaptor.capture(), anyInt(), any(), anyLong());

        List<AreaQueryPhase> phases = phaseCaptor.getAllValues();

        // Expected sequence: STRUCTURED, STRUCTURED, NAME_FALLBACK, NAME_FALLBACK, STRUCTURED, STRUCTURED
        assertEquals(AreaQueryPhase.STRUCTURED, phases.get(0), "First call should be STRUCTURED");
        assertEquals(AreaQueryPhase.STRUCTURED, phases.get(1), "Second call should be STRUCTURED");
        assertEquals(AreaQueryPhase.NAME_FALLBACK, phases.get(2), "Third call should be NAME_FALLBACK (batch yields to fallback)");
        assertEquals(AreaQueryPhase.NAME_FALLBACK, phases.get(3), "Fourth call should be NAME_FALLBACK");
        assertEquals(AreaQueryPhase.STRUCTURED, phases.get(4), "Fifth call should be STRUCTURED (next batch)");
    }

    @Test
    void structuredBatchThenFallbackBatchInSameRound() {
        // Test that within a round, we process up to 2 structured, then up to 2 fallback
        // before moving to next round
        LeadCandidate fallbackCandidate1 = new LeadCandidate("Barbearia Fallback 1", "openstreetmap", "node/3");
        fallbackCandidate1.setCategory("shop=barber");
        fallbackCandidate1.setPhone("+55 65 99999-3333");
        LeadCandidate fallbackCandidate2 = new LeadCandidate("Barbearia Fallback 2", "openstreetmap", "node/4");
        fallbackCandidate2.setCategory("shop=barber");
        fallbackCandidate2.setPhone("+55 65 99999-4444");
        lenient().when(osm.queryRegionWithStrategy(any(), any(), any(), any(), eq(AreaQueryPhase.STRUCTURED), anyInt(), any(), anyLong()))
                .thenReturn(
                        AreaQueryResult.success(List.of(), false, "overpass-api.de", 100),
                        AreaQueryResult.success(List.of(), false, "overpass-api.de", 100),
                        AreaQueryResult.success(List.of(), false, "overpass-api.de", 100),
                        AreaQueryResult.success(List.of(), false, "overpass-api.de", 100)
                );
        lenient().when(osm.queryRegionWithStrategy(any(), any(), any(), any(), eq(AreaQueryPhase.NAME_FALLBACK), anyInt(), any(), anyLong()))
                .thenReturn(
                        AreaQueryResult.success(List.of(fallbackCandidate1), false, "overpass-api.de", 100),
                        AreaQueryResult.success(List.of(fallbackCandidate2), false, "overpass-api.de", 100),
                        AreaQueryResult.success(List.of(fallbackCandidate1), false, "overpass-api.de", 100),
                        AreaQueryResult.success(List.of(fallbackCandidate2), false, "overpass-api.de", 100)
                );

        when(deduplicationService.check(any())).thenReturn(new DeduplicationService.DuplicateCheck(Optional.empty(), null));
        when(persistenceService.createLeadForCampaign(any(), any())).thenReturn(Lead.builder().id(10L).build());

        // target=2 so we exit after round 1 (2 fallback accepted)
        var result = service.discoverAndPersist(campaign, 2);

        assertEquals(2, result.acceptedThisRun());

        ArgumentCaptor<AreaQueryPhase> phaseCaptor = ArgumentCaptor.forClass(AreaQueryPhase.class);
        verify(osm, atLeast(4)).queryRegionWithStrategy(any(), any(), any(), any(), phaseCaptor.capture(), anyInt(), any(), anyLong());

        List<AreaQueryPhase> phases = phaseCaptor.getAllValues();

        long structuredCount = phases.stream().filter(p -> p == AreaQueryPhase.STRUCTURED).count();
        long fallbackCount = phases.stream().filter(p -> p == AreaQueryPhase.NAME_FALLBACK).count();

        // In round 1: 2 structured (both empty, queue fallback), then 2 fallback (both return candidates, 2 accepted)
        assertEquals(2, structuredCount, "Should run 2 STRUCTURED in round 1");
        assertEquals(2, fallbackCount, "Should run 2 NAME_FALLBACK in round 1");
    }

    @Test
    void structuredWithCandidateDoesNotQueueFallback() {
        // This test is now covered by structuredWithCandidatesSkipsNameFallback
        // With batching: first region in batch accepts a lead, so no fallback queued for that region
        // But second region in batch might queue fallback if it returns empty
        // For target=1, we only need 1 lead, so loop exits after first acceptance
        LeadCandidate candidate = new LeadCandidate("Barbearia", "openstreetmap", "node/1");
        candidate.setCategory("shop=barber");
        candidate.setPhone("+55 65 9999-8888");

        lenient().when(osm.queryRegionWithStrategy(any(), any(), any(), any(), eq(AreaQueryPhase.STRUCTURED), anyInt(), any(), anyLong()))
                .thenReturn(AreaQueryResult.success(List.of(candidate), false, "overpass-api.de", 100));
        lenient().when(osm.queryRegionWithStrategy(any(), any(), any(), any(), eq(AreaQueryPhase.NAME_FALLBACK), anyInt(), any(), anyLong()))
                .thenReturn(AreaQueryResult.success(List.of(), false, "overpass-api.de", 100));
        when(deduplicationService.check(any())).thenReturn(new DeduplicationService.DuplicateCheck(Optional.empty(), null));
        when(persistenceService.createLeadForCampaign(any(), any())).thenReturn(Lead.builder().id(10L).build());

        var result = service.discoverAndPersist(campaign, 1); // target = 1, so exits after first acceptance

        assertEquals(1, result.acceptedThisRun());
        verify(osm, never()).queryRegionWithStrategy(any(), any(), any(), any(), eq(AreaQueryPhase.NAME_FALLBACK), anyInt(), any(), anyLong());
    }

    @Test
    void regionWithTimeoutIsDeferred() {
        // Use a region that won't split (small enough)
        LeadCandidate candidate = new LeadCandidate("Barbearia", "openstreetmap", "node/1");
        candidate.setCategory("shop=barber");
        candidate.setPhone("+55 65 9999-8888");

        // Create a campaign with a small scope to avoid splitting
        Campaign smallCampaign = Campaign.builder()
                .id(1L)
                .niche("barbearia")
                .city("CuiabÃƒÂ¡")
                .country("Brazil")
                .requestedQuantity(1)
                .build();

        // Small scope that won't split
        GeoScope smallScope = new GeoScope(-15.6, -56.1, "CuiabÃƒÂ¡", "MT", "Brazil", "br", -15.61, -56.11, -15.59, -56.09, true, "relation", 333734L);
        lenient().when(osm.resolveScope(any(), any())).thenReturn(smallScope);

        lenient().when(osm.queryRegionWithStrategy(any(), any(), any(), any(), eq(AreaQueryPhase.STRUCTURED), anyInt(), any(), anyLong()))
                .thenReturn(
                        AreaQueryResult.infraUnavailableWithAttempt("OSM_DISCOVERY_TIMEOUT", "Timeout na regiÃƒÂ£o A", 5000),
                        AreaQueryResult.success(List.of(candidate), false, "overpass-api.de", 100)
                );
        lenient().when(osm.queryRegionWithStrategy(any(), any(), any(), any(), eq(AreaQueryPhase.NAME_FALLBACK), anyInt(), any(), anyLong()))
                .thenReturn(AreaQueryResult.success(List.of(), false, "overpass-api.de", 100));
        when(deduplicationService.check(any())).thenReturn(new DeduplicationService.DuplicateCheck(Optional.empty(), null));
        when(persistenceService.createLeadForCampaign(any(), any())).thenReturn(Lead.builder().id(10L).build());

        var result = service.discoverAndPersist(smallCampaign, 1);

        assertEquals(1, result.acceptedThisRun());

        ArgumentCaptor<SearchRegion> regionCaptor = ArgumentCaptor.forClass(SearchRegion.class);
        verify(osm, times(2)).queryRegionWithStrategy(any(), any(), any(), regionCaptor.capture(), eq(AreaQueryPhase.STRUCTURED), anyInt(), any(), anyLong());

        List<SearchRegion> regions = regionCaptor.getAllValues();
        assertEquals(2, regions.size(), "Region should be queried twice (initial + retry)");
    }

    @Test
    void maxDeferralPreventsInfiniteLoop() {
        // Use a region that won't split
        LeadCandidate candidate = new LeadCandidate("Barbearia", "openstreetmap", "node/1");
        candidate.setCategory("shop=barber");
        candidate.setPhone("+55 65 9999-8888");

        Campaign smallCampaign = Campaign.builder()
                .id(1L)
                .niche("barbearia")
                .city("CuiabÃƒÂ¡")
                .country("Brazil")
                .requestedQuantity(1)
                .build();

        GeoScope smallScope = new GeoScope(-15.6, -56.1, "CuiabÃƒÂ¡", "MT", "Brazil", "br", -15.61, -56.11, -15.59, -56.09, true, "relation", 333734L);
        lenient().when(osm.resolveScope(any(), any())).thenReturn(smallScope);

        lenient().when(osm.queryRegionWithStrategy(any(), any(), any(), any(), eq(AreaQueryPhase.STRUCTURED), anyInt(), any(), anyLong()))
                .thenReturn(
                        AreaQueryResult.infraUnavailableWithAttempt("OSM_DISCOVERY_TIMEOUT", "Timeout 1", 5000),
                        AreaQueryResult.infraUnavailableWithAttempt("OSM_DISCOVERY_TIMEOUT", "Timeout 2", 5000),
                        AreaQueryResult.success(List.of(candidate), false, "overpass-api.de", 100)
                );
        lenient().when(osm.queryRegionWithStrategy(any(), any(), any(), any(), eq(AreaQueryPhase.NAME_FALLBACK), anyInt(), any(), anyLong()))
                .thenReturn(AreaQueryResult.success(List.of(), false, "overpass-api.de", 100));
        when(deduplicationService.check(any())).thenReturn(new DeduplicationService.DuplicateCheck(Optional.empty(), null));
        when(persistenceService.createLeadForCampaign(any(), any())).thenReturn(Lead.builder().id(10L).build());

        var result = service.discoverAndPersist(smallCampaign, 1);

        assertEquals(1, result.acceptedThisRun());

        ArgumentCaptor<SearchRegion> regionCaptor = ArgumentCaptor.forClass(SearchRegion.class);
        verify(osm, times(3)).queryRegionWithStrategy(any(), any(), any(), regionCaptor.capture(), eq(AreaQueryPhase.STRUCTURED), anyInt(), any(), anyLong());

        List<SearchRegion> regions = regionCaptor.getAllValues();
        // First call: ADMIN_AREA timeout -> deferred
        // Second call: BBOX_FALLBACK (strategy changed) timeout -> deferred (new key)
        // Third call: BBOX_FALLBACK retry -> success
        assertEquals(3, regions.size(), "Region queried 3 times: initial ADMIN_AREA, retry BBOX_FALLBACK, retry BBOX_FALLBACK success");
    }

    @Test
    void candidateWithoutPhoneButWithWebsiteCanBeEnrichedAndAccepted() {
        LeadCandidate candidate = new LeadCandidate("Barbearia X", "openstreetmap", "node/1");
        candidate.setCategory("shop=barber");
        candidate.setWebsite("https://barbeariax.com");

        lenient().when(osm.queryRegionWithStrategy(any(), any(), any(), any(), eq(AreaQueryPhase.STRUCTURED), anyInt(), any(), anyLong()))
                .thenReturn(AreaQueryResult.success(List.of(candidate), false, "overpass-api.de", 100));
        when(deduplicationService.check(any())).thenReturn(new DeduplicationService.DuplicateCheck(Optional.empty(), null));
        when(websiteContactEnricher.enrich(anyString()))
                .thenReturn(new WebsiteContactEnricher.WebsiteContactData("+55 65 99999-9999", "email@test.com", "insta"));
        when(persistenceService.createLeadForCampaign(any(), any())).thenReturn(Lead.builder().id(10L).build());

        var result = service.discoverAndPersist(campaign, 3);

        assertEquals(1, result.acceptedThisRun());
        verify(persistenceService).createLeadForCampaign(any(), any());
    }

    @Test
    void deterministicThreeLeadsComplete() {
        LeadCandidate candidateA = new LeadCandidate("Barbearia A", "openstreetmap", "node/1");
        candidateA.setCategory("shop=barber");
        candidateA.setPhone("+55 65 9999-1111");

        LeadCandidate candidateB = new LeadCandidate("Barbearia B", "openstreetmap", "node/2");
        candidateB.setCategory("shop=barber");
        candidateB.setWebsite("https://barbeariab.com");

        LeadCandidate candidateC = new LeadCandidate("Barbearia C", "openstreetmap", "node/3");
        candidateC.setCategory("shop=barber");
        candidateC.setPhone("+55 65 9999-3333");

        LeadCandidate fallbackCandidateB = new LeadCandidate("Barbearia B Fallback", "openstreetmap", "node/4");
        fallbackCandidateB.setCategory("shop=barber");
        fallbackCandidateB.setPhone("+55 65 9999-4444");

        lenient().when(osm.queryRegionWithStrategy(any(), any(), any(), any(), eq(AreaQueryPhase.STRUCTURED), anyInt(), any(), anyLong()))
                .thenReturn(
                        AreaQueryResult.success(List.of(candidateA), false, "overpass-api.de", 100),
                        AreaQueryResult.success(List.of(), false, "overpass-api.de", 100),
                        AreaQueryResult.success(List.of(candidateC), false, "overpass-api.de", 100)
                );
        lenient().when(osm.queryRegionWithStrategy(any(), any(), any(), any(), eq(AreaQueryPhase.NAME_FALLBACK), anyInt(), any(), anyLong()))
                .thenReturn(AreaQueryResult.success(List.of(fallbackCandidateB), false, "overpass-api.de", 100));

        when(deduplicationService.check(any())).thenReturn(new DeduplicationService.DuplicateCheck(Optional.empty(), null));
        when(websiteContactEnricher.enrich("https://barbeariab.com"))
                .thenReturn(new WebsiteContactEnricher.WebsiteContactData("+55 65 9999-2222", "email@test.com", "insta"));
        when(persistenceService.createLeadForCampaign(any(), any())).thenReturn(Lead.builder().id(10L).build());

        var result = service.discoverAndPersist(campaign, 3);

        assertEquals(DiscoveryExecutionResult.Outcome.COMPLETE, result.outcome());
        assertEquals(3, result.acceptedThisRun());
    }

    @Test
    void regionBudgetUsedForStructured() {
        LeadCandidate candidate = new LeadCandidate("Barbearia", "openstreetmap", "node/1");
        candidate.setCategory("shop=barber");
        candidate.setPhone("+55 65 9999-8888");

        lenient().when(osm.queryRegionWithStrategy(any(), any(), any(), any(), eq(AreaQueryPhase.STRUCTURED), anyInt(), any(), anyLong()))
                .thenReturn(AreaQueryResult.success(List.of(candidate), false, "overpass-api.de", 100));
        when(deduplicationService.check(any())).thenReturn(new DeduplicationService.DuplicateCheck(Optional.empty(), null));
        when(persistenceService.createLeadForCampaign(any(), any())).thenReturn(Lead.builder().id(10L).build());

        service.discoverAndPersist(campaign, 3);

        ArgumentCaptor<DiscoveryBudget> budgetCaptor = ArgumentCaptor.forClass(DiscoveryBudget.class);
        verify(osm, atLeastOnce()).queryRegionWithStrategy(any(), any(), any(), any(), eq(AreaQueryPhase.STRUCTURED), anyInt(), budgetCaptor.capture(), anyLong());

        DiscoveryBudget captured = budgetCaptor.getValue();
        assertTrue(captured.totalMs() <= 15000L, "Region budget for STRUCTURED should be <= 15000ms, was " + captured.totalMs());
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
        when(persistenceService.createLeadForCampaign(any(), any())).thenReturn(Lead.builder().id(10L).build());

        service.discoverAndPersist(campaign, 3);

        ArgumentCaptor<DiscoveryBudget> budgetCaptor = ArgumentCaptor.forClass(DiscoveryBudget.class);
        verify(osm, atLeastOnce()).queryRegionWithStrategy(any(), any(), any(), any(), eq(AreaQueryPhase.NAME_FALLBACK), anyInt(), budgetCaptor.capture(), anyLong());

        DiscoveryBudget captured = budgetCaptor.getValue();
        assertTrue(captured.totalMs() <= 12000L, "Fallback budget for NAME_FALLBACK should be <= 12000ms, was " + captured.totalMs());
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
        when(persistenceService.createLeadForCampaign(any(), any())).thenReturn(Lead.builder().id(10L).build());

        var result = service.discoverAndPersist(campaign, 3);

        assertEquals(DiscoveryExecutionResult.Outcome.PARTIAL, result.outcome());
        assertEquals(1, result.acceptedThisRun());
    }

    @Test
    void localCatalogExhaustiveLoopPage1NoPhonePage2ValidAccepted() throws Exception {
        setField(service, "catalogDiscoveryEnabled", true);

        LeadCandidate noPhone1 = new LeadCandidate("Barbearia Sem Contato 1", "openstreetmap", "node/1");
        noPhone1.setCategory("shop=barber");
        LeadCandidate noPhone2 = new LeadCandidate("Barbearia Sem Contato 2", "openstreetmap", "node/2");
        noPhone2.setCategory("shop=barber");
        LeadCandidate noPhone3 = new LeadCandidate("Barbearia Sem Contato 3", "openstreetmap", "node/3");
        noPhone3.setCategory("shop=barber");
        LeadCandidate noPhone4 = new LeadCandidate("Barbearia Sem Contato 4", "openstreetmap", "node/4");
        noPhone4.setCategory("shop=barber");
        LeadCandidate noPhone5 = new LeadCandidate("Barbearia Sem Contato 5", "openstreetmap", "node/5");
        noPhone5.setCategory("shop=barber");

        LeadCandidate valid1 = new LeadCandidate("Barbearia Com Contato 1", "openstreetmap", "node/10");
        valid1.setCategory("shop=barber");
        valid1.setPhone("+55 65 9999-1111");
        LeadCandidate valid2 = new LeadCandidate("Barbearia Com Contato 2", "openstreetmap", "node/11");
        valid2.setCategory("shop=barber");
        valid2.setPhone("+55 65 9999-2222");
        LeadCandidate valid3 = new LeadCandidate("Barbearia Com Contato 3", "openstreetmap", "node/12");
        valid3.setCategory("shop=barber");
        valid3.setPhone("+55 65 9999-3333");

        // Page 1: 30 candidates without phone, hasMore=true
        // Page 2: 3 candidates with phone, hasMore=false
        when(localCatalogProvider.discoverPage(anyString(), anyString(), anyString(), anyInt(), eq(0)))
                .thenReturn(new LocalOsmCatalogProvider.CatalogPage(
                        List.of(noPhone1, noPhone2, noPhone3, noPhone4, noPhone5),
                        30, 30, true
                ));
        when(localCatalogProvider.discoverPage(anyString(), anyString(), anyString(), anyInt(), eq(30)))
                .thenReturn(new LocalOsmCatalogProvider.CatalogPage(
                        List.of(valid1, valid2, valid3),
                        3, 33, false
                ));

        when(deduplicationService.check(any())).thenReturn(new DeduplicationService.DuplicateCheck(Optional.empty(), null));
        when(persistenceService.createLeadForCampaign(any(), any())).thenReturn(Lead.builder().id(10L).build());
        when(campaignLeadRepository.countByCampaignId(1L)).thenReturn(0L);
        when(campaignRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        var result = service.discoverAndPersist(campaign, 3);

        assertEquals(DiscoveryExecutionResult.Outcome.COMPLETE, result.outcome());
        assertEquals(3, result.acceptedThisRun());

        // Verify discoverPage called twice
        verify(localCatalogProvider, times(2)).discoverPage(anyString(), anyString(), anyString(), anyInt(), anyInt());
    }

    @Test
    void localCatalogDuplicateAndNoPhonePage1ValidPage2Accepted() throws Exception {
        setField(service, "catalogDiscoveryEnabled", true);

        LeadCandidate duplicate = new LeadCandidate("Barbearia Duplicada", "openstreetmap", "node/1");
        duplicate.setCategory("shop=barber");
        duplicate.setPhone("+55 65 9999-1111");

        LeadCandidate noPhone = new LeadCandidate("Barbearia Sem Contato", "openstreetmap", "node/2");
        noPhone.setCategory("shop=barber");

        LeadCandidate valid1 = new LeadCandidate("Barbearia VÃƒÂ¡lida 1", "openstreetmap", "node/10");
        valid1.setCategory("shop=barber");
        valid1.setPhone("+55 65 9999-2222");
        LeadCandidate valid2 = new LeadCandidate("Barbearia VÃƒÂ¡lida 2", "openstreetmap", "node/11");
        valid2.setCategory("shop=barber");
        valid2.setPhone("+55 65 9999-3333");
        LeadCandidate valid3 = new LeadCandidate("Barbearia VÃƒÂ¡lida 3", "openstreetmap", "node/12");
        valid3.setCategory("shop=barber");
        valid3.setPhone("+55 65 9999-4444");

        // Page 1: duplicate + no phone, hasMore=true
        when(localCatalogProvider.discoverPage(anyString(), anyString(), anyString(), anyInt(), eq(0)))
                .thenReturn(new LocalOsmCatalogProvider.CatalogPage(
                        List.of(duplicate, noPhone),
                        2, 2, true
                ));
        // Page 2: 3 valid, hasMore=false
        when(localCatalogProvider.discoverPage(anyString(), anyString(), anyString(), anyInt(), eq(2)))
                .thenReturn(new LocalOsmCatalogProvider.CatalogPage(
                        List.of(valid1, valid2, valid3),
                        3, 5, false
                ));

        Lead existing = Lead.builder()
                .id(50L)
                .businessName("Barbearia Duplicada")
                .phone("+55 65 9999-1111")
                .doNotContact(false)
                .build();

        when(deduplicationService.check(any()))
                .thenReturn(new DeduplicationService.DuplicateCheck(Optional.of(existing), "source_id"))
                .thenReturn(new DeduplicationService.DuplicateCheck(Optional.empty(), null))
                .thenReturn(new DeduplicationService.DuplicateCheck(Optional.empty(), null))
                .thenReturn(new DeduplicationService.DuplicateCheck(Optional.empty(), null));
        when(persistenceService.createLeadForCampaign(any(), any())).thenReturn(Lead.builder().id(10L).build());
        when(campaignLeadRepository.countByCampaignId(1L)).thenReturn(0L);
        when(campaignRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        var result = service.discoverAndPersist(campaign, 3);

        assertEquals(DiscoveryExecutionResult.Outcome.COMPLETE, result.outcome());
        assertEquals(3, result.acceptedThisRun());
        verify(localCatalogProvider, times(2)).discoverPage(anyString(), anyString(), anyString(), anyInt(), anyInt());
    }

    @Test
    void localCatalogExhaustedWithTwoValidReturnsPartial() throws Exception {
        setField(service, "catalogDiscoveryEnabled", true);

        LeadCandidate valid1 = new LeadCandidate("Barbearia VÃƒÂ¡lida 1", "openstreetmap", "node/10");
        valid1.setCategory("shop=barber");
        valid1.setPhone("+55 65 9999-1111");
        LeadCandidate valid2 = new LeadCandidate("Barbearia VÃƒÂ¡lida 2", "openstreetmap", "node/11");
        valid2.setCategory("shop=barber");
        valid2.setPhone("+55 65 9999-2222");

        // Page 1: 2 valid, hasMore=false (catalog exhausted)
        when(localCatalogProvider.discoverPage(anyString(), anyString(), anyString(), anyInt(), eq(0)))
                .thenReturn(new LocalOsmCatalogProvider.CatalogPage(
                        List.of(valid1, valid2),
                        2, 2, false
                ));

        when(deduplicationService.check(any())).thenReturn(new DeduplicationService.DuplicateCheck(Optional.empty(), null));
        when(persistenceService.createLeadForCampaign(any(), any())).thenReturn(Lead.builder().id(10L).build());
        when(campaignLeadRepository.countByCampaignId(1L)).thenReturn(0L);
        when(campaignRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        var result = service.discoverAndPersist(campaign, 3);

        assertEquals(DiscoveryExecutionResult.Outcome.PARTIAL, result.outcome());
        assertEquals(2, result.acceptedThisRun());
        assertEquals("OSM_CATALOG_PARTIAL", result.errorCode());
    }

    @Test
    void localCatalogExhaustedWithZeroValidReturnsEmpty() throws Exception {
        setField(service, "catalogDiscoveryEnabled", true);

        LeadCandidate noPhone1 = new LeadCandidate("Barbearia Sem Contato 1", "openstreetmap", "node/1");
        noPhone1.setCategory("shop=barber");
        LeadCandidate noPhone2 = new LeadCandidate("Barbearia Sem Contato 2", "openstreetmap", "node/2");
        noPhone2.setCategory("shop=barber");

        // Page 1: 2 without phone, hasMore=false (catalog exhausted)
        when(localCatalogProvider.discoverPage(anyString(), anyString(), anyString(), anyInt(), eq(0)))
                .thenReturn(new LocalOsmCatalogProvider.CatalogPage(
                        List.of(noPhone1, noPhone2),
                        2, 2, false
                ));

        when(deduplicationService.check(any())).thenReturn(new DeduplicationService.DuplicateCheck(Optional.empty(), null));
        when(campaignLeadRepository.countByCampaignId(1L)).thenReturn(0L);
        when(campaignRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        var result = service.discoverAndPersist(campaign, 3);

        assertEquals(DiscoveryExecutionResult.Outcome.EMPTY, result.outcome());
        assertEquals(0, result.acceptedThisRun());
        assertEquals("OSM_NO_USEFUL_LEADS", result.errorCode());
    }

    @Test
    void localCatalogTarget3WithDuplicatesProvesDynamicTarget() throws Exception {
        setField(service, "catalogDiscoveryEnabled", true);

        LeadCandidate dup1 = new LeadCandidate("Barbearia Dup 1", "openstreetmap", "node/1");
        dup1.setCategory("shop=barber");
        dup1.setPhone("+55 65 9999-1111");
        LeadCandidate dup2 = new LeadCandidate("Barbearia Dup 2", "openstreetmap", "node/2");
        dup2.setCategory("shop=barber");
        dup2.setPhone("+55 65 9999-2222");
        LeadCandidate novo1 = new LeadCandidate("Barbearia Novo 1", "openstreetmap", "node/3");
        novo1.setCategory("shop=barber");
        novo1.setPhone("+55 65 9999-3333");

        LeadCandidate novo2 = new LeadCandidate("Barbearia Novo 2", "openstreetmap", "node/10");
        novo2.setCategory("shop=barber");
        novo2.setPhone("+55 65 9999-4444");
        LeadCandidate novo3 = new LeadCandidate("Barbearia Novo 3", "openstreetmap", "node/11");
        novo3.setCategory("shop=barber");
        novo3.setPhone("+55 65 9999-5555");

        when(localCatalogProvider.discoverPage(anyString(), anyString(), anyString(), eq(3), eq(0)))
                .thenReturn(new LocalOsmCatalogProvider.CatalogPage(
                        List.of(dup1, dup2, novo1),
                        3, 3, true
                ));
        when(localCatalogProvider.discoverPage(anyString(), anyString(), anyString(), eq(3), eq(3)))
                .thenReturn(new LocalOsmCatalogProvider.CatalogPage(
                        List.of(novo2, novo3),
                        2, 5, false
                ));

        Lead existing1 = Lead.builder().id(50L).businessName("Barbearia Dup 1").phone("+55 65 9999-1111").doNotContact(false).build();
        Lead existing2 = Lead.builder().id(51L).businessName("Barbearia Dup 2").phone("+55 65 9999-2222").doNotContact(false).build();

        when(deduplicationService.check(any()))
                .thenReturn(new DeduplicationService.DuplicateCheck(Optional.of(existing1), "source_id"))
                .thenReturn(new DeduplicationService.DuplicateCheck(Optional.of(existing2), "source_id"))
                .thenReturn(new DeduplicationService.DuplicateCheck(Optional.empty(), null))
                .thenReturn(new DeduplicationService.DuplicateCheck(Optional.empty(), null))
                .thenReturn(new DeduplicationService.DuplicateCheck(Optional.empty(), null));
        when(persistenceService.createLeadForCampaign(any(), any())).thenReturn(Lead.builder().id(10L).build());
        when(campaignLeadRepository.countByCampaignId(1L)).thenReturn(0L);
        when(campaignRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        var result = service.discoverAndPersist(campaign, 3);

        assertEquals(DiscoveryExecutionResult.Outcome.COMPLETE, result.outcome());
        assertEquals(3, result.acceptedThisRun());
        // duplicates did not consume quota: page1 had 2 dups (rejected) +1 new = 1 accepted, page2 had 2 new = total 3
        verify(localCatalogProvider, times(2)).discoverPage(anyString(), anyString(), anyString(), anyInt(), anyInt());
    }

    @Test
    void localCatalogTarget10AcrossMultiplePages() throws Exception {
        setField(service, "catalogDiscoveryEnabled", true);
        campaign.setRequestedQuantity(10);

        List<LeadCandidate> page1Candidates = new java.util.ArrayList<>();
        for (int i = 0; i < 5; i++) {
            LeadCandidate c = new LeadCandidate("Barbearia P1-" + i, "openstreetmap", "node/" + i);
            c.setCategory("shop=barber");
            c.setPhone("+55 65 9000-000" + i);
            page1Candidates.add(c);
        }
        // add 2 duplicates mixed
        LeadCandidate dupA = new LeadCandidate("Dup A", "openstreetmap", "node/100");
        dupA.setCategory("shop=barber");
        dupA.setPhone("+55 65 9999-9999");
        LeadCandidate dupB = new LeadCandidate("Dup B", "openstreetmap", "node/101");
        dupB.setCategory("shop=barber");
        dupB.setPhone("+55 65 9998-8888");

        when(localCatalogProvider.discoverPage(anyString(), anyString(), anyString(), eq(10), eq(0)))
                .thenReturn(new LocalOsmCatalogProvider.CatalogPage(
                        List.of(dupA, page1Candidates.get(0), page1Candidates.get(1), dupB, page1Candidates.get(2)),
                        5, 5, true
                ));
        List<LeadCandidate> page2 = new java.util.ArrayList<>();
        for (int i = 3; i < 8; i++) {
            LeadCandidate c = new LeadCandidate("Barbearia P2-" + i, "openstreetmap", "node/" + (10 + i));
            c.setCategory("shop=barber");
            c.setPhone("+55 65 9001-000" + i);
            page2.add(c);
        }
        when(localCatalogProvider.discoverPage(anyString(), anyString(), anyString(), eq(10), eq(5)))
                .thenReturn(new LocalOsmCatalogProvider.CatalogPage(
                        page2,
                        5, 10, true
                ));
        List<LeadCandidate> page3 = new java.util.ArrayList<>();
        for (int i = 8; i < 11; i++) {
            LeadCandidate c = new LeadCandidate("Barbearia P3-" + i, "openstreetmap", "node/" + (20 + i));
            c.setCategory("shop=barber");
            c.setPhone("+55 65 9002-000" + i);
            page3.add(c);
        }
        when(localCatalogProvider.discoverPage(anyString(), anyString(), anyString(), eq(10), eq(10)))
                .thenReturn(new LocalOsmCatalogProvider.CatalogPage(
                        page3,
                        3, 13, false
                ));

        Lead existingA = Lead.builder().id(60L).businessName("Dup A").phone("+55 65 9999-9999").build();
        Lead existingB = Lead.builder().id(61L).businessName("Dup B").phone("+55 65 9998-8888").build();
        when(deduplicationService.check(any()))
                .thenReturn(new DeduplicationService.DuplicateCheck(Optional.of(existingA), "source_id"))
                .thenReturn(new DeduplicationService.DuplicateCheck(Optional.empty(), null))
                .thenReturn(new DeduplicationService.DuplicateCheck(Optional.empty(), null))
                .thenReturn(new DeduplicationService.DuplicateCheck(Optional.of(existingB), "source_id"))
                .thenReturn(new DeduplicationService.DuplicateCheck(Optional.empty(), null))
                .thenReturn(new DeduplicationService.DuplicateCheck(Optional.empty(), null))
                .thenReturn(new DeduplicationService.DuplicateCheck(Optional.empty(), null))
                .thenReturn(new DeduplicationService.DuplicateCheck(Optional.empty(), null))
                .thenReturn(new DeduplicationService.DuplicateCheck(Optional.empty(), null))
                .thenReturn(new DeduplicationService.DuplicateCheck(Optional.empty(), null))
                .thenReturn(new DeduplicationService.DuplicateCheck(Optional.empty(), null))
                .thenReturn(new DeduplicationService.DuplicateCheck(Optional.empty(), null));
        when(persistenceService.createLeadForCampaign(any(), any())).thenReturn(Lead.builder().id(10L).build());
        when(campaignLeadRepository.countByCampaignId(1L)).thenReturn(0L);
        when(campaignRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        var result = service.discoverAndPersist(campaign, 10);

        assertEquals(DiscoveryExecutionResult.Outcome.COMPLETE, result.outcome());
        assertEquals(10, result.acceptedThisRun());
    }

    @Test
    void localCatalogTarget30AcrossManyPages() throws Exception {
        setField(service, "catalogDiscoveryEnabled", true);
        campaign.setRequestedQuantity(30);

        // Simulate 3 pages: 10 each with phones, plus one partial to reach 30
        when(localCatalogProvider.discoverPage(anyString(), anyString(), anyString(), eq(30), eq(0)))
                .thenReturn(new LocalOsmCatalogProvider.CatalogPage(
                        makeCandidates(0, 10),
                        10, 10, true
                ));
        when(localCatalogProvider.discoverPage(anyString(), anyString(), anyString(), eq(30), eq(10)))
                .thenReturn(new LocalOsmCatalogProvider.CatalogPage(
                        makeCandidates(10, 10),
                        10, 20, true
                ));
        when(localCatalogProvider.discoverPage(anyString(), anyString(), anyString(), eq(30), eq(20)))
                .thenReturn(new LocalOsmCatalogProvider.CatalogPage(
                        makeCandidates(20, 10),
                        10, 30, false
                ));

        when(deduplicationService.check(any())).thenReturn(new DeduplicationService.DuplicateCheck(Optional.empty(), null));
        when(persistenceService.createLeadForCampaign(any(), any())).thenReturn(Lead.builder().id(10L).build());
        when(campaignLeadRepository.countByCampaignId(1L)).thenReturn(0L);
        when(campaignRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        var result = service.discoverAndPersist(campaign, 30);

        assertEquals(DiscoveryExecutionResult.Outcome.COMPLETE, result.outcome());
        assertEquals(30, result.acceptedThisRun());
        verify(localCatalogProvider, times(3)).discoverPage(anyString(), anyString(), anyString(), eq(30), anyInt());
    }

    private List<LeadCandidate> makeCandidates(int start, int count) {
        List<LeadCandidate> list = new java.util.ArrayList<>();
        for (int i = start; i < start + count; i++) {
            LeadCandidate c = new LeadCandidate("Barbearia " + i, "openstreetmap", "node/" + (1000 + i));
            c.setCategory("shop=barber");
            c.setPhone("+55 65 9" + String.format("%03d", i) + "-0000");
            list.add(c);
        }
        return list;
    }

    @Test
    void localCatalogInsufficientReturnsPartial18Of30() throws Exception {
        setField(service, "catalogDiscoveryEnabled", true);
        campaign.setRequestedQuantity(30);

        when(localCatalogProvider.discoverPage(anyString(), anyString(), anyString(), eq(30), eq(0)))
                .thenReturn(new LocalOsmCatalogProvider.CatalogPage(
                        makeCandidates(0, 10),
                        10, 10, true
                ));
        when(localCatalogProvider.discoverPage(anyString(), anyString(), anyString(), eq(30), eq(10)))
                .thenReturn(new LocalOsmCatalogProvider.CatalogPage(
                        makeCandidates(10, 8),
                        8, 18, false
                ));

        when(deduplicationService.check(any())).thenReturn(new DeduplicationService.DuplicateCheck(Optional.empty(), null));
        when(persistenceService.createLeadForCampaign(any(), any())).thenReturn(Lead.builder().id(10L).build());
        when(campaignLeadRepository.countByCampaignId(1L)).thenReturn(0L);
        when(campaignRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        var result = service.discoverAndPersist(campaign, 30);

        assertEquals(DiscoveryExecutionResult.Outcome.PARTIAL, result.outcome());
        assertEquals(18, result.acceptedThisRun());
        assertEquals("OSM_CATALOG_PARTIAL", result.errorCode());
    }

    @Test
    void duplicateDoesNotConsumeTargetProven() throws Exception {
        setField(service, "catalogDiscoveryEnabled", true);
        // target 5 but first page has 3 duplicates, next page has 5 new
        when(localCatalogProvider.discoverPage(anyString(), anyString(), anyString(), eq(5), eq(0)))
                .thenReturn(new LocalOsmCatalogProvider.CatalogPage(
                        List.of(
                                dupCandidate("dup1", "node/1", "+55 65 9999-1111"),
                                dupCandidate("dup2", "node/2", "+55 65 9999-2222"),
                                dupCandidate("dup3", "node/3", "+55 65 9999-3333"),
                                newCandidate("new1", "node/10", "+55 65 9000-1111"),
                                newCandidate("new2", "node/11", "+55 65 9000-2222")
                        ),
                        5, 5, true
                ));
        when(localCatalogProvider.discoverPage(anyString(), anyString(), anyString(), eq(5), eq(5)))
                .thenReturn(new LocalOsmCatalogProvider.CatalogPage(
                        List.of(
                                newCandidate("new3", "node/12", "+55 65 9000-3333"),
                                newCandidate("new4", "node/13", "+55 65 9000-4444"),
                                newCandidate("new5", "node/14", "+55 65 9000-5555")
                        ),
                        3, 8, false
                ));
        Lead ex1 = Lead.builder().id(1L).businessName("dup1").phone("+55 65 9999-1111").build();
        Lead ex2 = Lead.builder().id(2L).businessName("dup2").phone("+55 65 9999-2222").build();
        Lead ex3 = Lead.builder().id(3L).businessName("dup3").phone("+55 65 9999-3333").build();
        when(deduplicationService.check(any()))
                .thenReturn(new DeduplicationService.DuplicateCheck(Optional.of(ex1), "source_id"))
                .thenReturn(new DeduplicationService.DuplicateCheck(Optional.of(ex2), "source_id"))
                .thenReturn(new DeduplicationService.DuplicateCheck(Optional.of(ex3), "source_id"))
                .thenReturn(new DeduplicationService.DuplicateCheck(Optional.empty(), null))
                .thenReturn(new DeduplicationService.DuplicateCheck(Optional.empty(), null))
                .thenReturn(new DeduplicationService.DuplicateCheck(Optional.empty(), null))
                .thenReturn(new DeduplicationService.DuplicateCheck(Optional.empty(), null))
                .thenReturn(new DeduplicationService.DuplicateCheck(Optional.empty(), null));
        when(persistenceService.createLeadForCampaign(any(), any())).thenReturn(Lead.builder().id(10L).build());
        when(campaignLeadRepository.countByCampaignId(1L)).thenReturn(0L);
        when(campaignRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        var result = service.discoverAndPersist(campaign, 5);
        assertEquals(5, result.acceptedThisRun());
        assertEquals(DiscoveryExecutionResult.Outcome.COMPLETE, result.outcome());
    }

    private LeadCandidate dupCandidate(String name, String sourceId, String phone) {
        LeadCandidate c = new LeadCandidate(name, "openstreetmap", sourceId);
        c.setCategory("shop=barber");
        c.setPhone(phone);
        return c;
    }

    private LeadCandidate newCandidate(String name, String sourceId, String phone) {
        LeadCandidate c = new LeadCandidate(name, "openstreetmap", sourceId);
        c.setCategory("shop=barber");
        c.setPhone(phone);
        return c;
    }

    @Test
    void expandedClassifierProviderReturnsMoreCandidatesWithFirstDuplicateThenThreeNewComplete() throws Exception {
        setField(service, "catalogDiscoveryEnabled", true);
        // Simulates expanded classifier: 4 candidates where first is duplicate, next 3 new
        LeadCandidate dup = newCandidate("Bella Esmalteria", "node/1", "+55 65 9999-1111");
        LeadCandidate n1 = newCandidate("Studio Nail 2", "node/2", "+55 65 9999-2222");
        LeadCandidate n2 = newCandidate("Manicure Top", "node/3", "+55 65 9999-3333");
        LeadCandidate n3 = newCandidate("Esmalteria Bella 3", "node/4", "+55 65 9999-4444");
        when(localCatalogProvider.discoverPage(anyString(), anyString(), anyString(), eq(3), eq(0)))
                .thenReturn(new LocalOsmCatalogProvider.CatalogPage(
                        List.of(dup, n1, n2, n3),
                        4, 4, false
                ));
        Lead existing = Lead.builder().id(99L).businessName("Bella Esmalteria").phone("+55 65 9999-1111").build();
        when(deduplicationService.check(any()))
                .thenReturn(new DeduplicationService.DuplicateCheck(Optional.of(existing), "source_id"))
                .thenReturn(new DeduplicationService.DuplicateCheck(Optional.empty(), null))
                .thenReturn(new DeduplicationService.DuplicateCheck(Optional.empty(), null))
                .thenReturn(new DeduplicationService.DuplicateCheck(Optional.empty(), null));
        when(persistenceService.createLeadForCampaign(any(), any())).thenReturn(Lead.builder().id(10L).build());
        when(campaignLeadRepository.countByCampaignId(1L)).thenReturn(0L);
        when(campaignRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        var result = service.discoverAndPersist(campaign, 3);
        assertEquals(DiscoveryExecutionResult.Outcome.COMPLETE, result.outcome());
        assertEquals(3, result.acceptedThisRun());
    }
}
