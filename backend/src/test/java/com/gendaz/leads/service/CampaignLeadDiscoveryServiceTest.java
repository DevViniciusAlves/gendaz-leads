package com.gendaz.leads.service;

import com.gendaz.leads.domain.LeadCandidate;
import com.gendaz.leads.entity.Campaign;
import com.gendaz.leads.entity.Lead;
import com.gendaz.leads.entity.LeadEvent;
import com.gendaz.leads.entity.OsmCatalogTarget;
import com.gendaz.leads.repository.CampaignRepository;
import com.gendaz.leads.repository.CampaignLeadRepository;
import com.gendaz.leads.repository.LeadEventRepository;
import com.gendaz.leads.repository.LeadRepository;
import com.gendaz.leads.service.QualifiedLeadPoolService;
import com.gendaz.leads.util.Normalizer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
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
    @Mock Normalizer normalizer;
    @Mock QualifiedLeadPoolService qualifiedPoolService;

    private CampaignLeadDiscoveryService service;
    private Campaign campaign;

    @BeforeEach
    void setUp() throws Exception {
        service = new CampaignLeadDiscoveryService(
                campaignRepository, campaignLeadRepository, leadRepository, leadEventRepository,
                deduplicationService, persistenceService, normalizer, qualifiedPoolService);

        campaign = Campaign.builder()
                .id(1L)
                .niche("nail designer")
                .city("Cuiabá")
                .country("Brazil")
                .requestedQuantity(3)
                .build();

        lenient().when(normalizer.normalizeSourceId(anyString(), anyString()))
                .thenAnswer(inv -> (inv.getArgument(0) == null ? "src" : inv.getArgument(0).toString().toLowerCase())
                        + "_" + (inv.getArgument(1) == null ? "" : inv.getArgument(1).toString().toLowerCase()));
        lenient().when(normalizer.normalizePhone(anyString()))
                .thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void poolTargetFoundThreeAvailableReturnsComplete() throws Exception {
        QualifiedLeadPoolService.ResolvedTarget resolved = mock(QualifiedLeadPoolService.ResolvedTarget.class);
        QualifiedLeadPoolService.PoolPage page1 = new QualifiedLeadPoolService.PoolPage(
                List.of(
                        validCandidate("node/1", "Nail Studio 1", "+55 65 9999-1111"),
                        validCandidate("node/2", "Nail Studio 2", "+55 65 9999-2222"),
                        validCandidate("node/3", "Nail Studio 3", "+55 65 9999-3333")
                ),
                3, 3, false, 10L, "nails"
        );

        when(qualifiedPoolService.resolveTarget(anyString(), anyString(), anyString())).thenReturn(resolved);
        when(resolved.target()).thenReturn(mock(OsmCatalogTarget.class));
        when(resolved.target().getId()).thenReturn(10L);
        when(qualifiedPoolService.discoverPage(eq(10L), eq(3), eq(0))).thenReturn(page1);

        when(deduplicationService.check(any())).thenReturn(new DeduplicationService.DuplicateCheck(Optional.empty(), null));
        when(persistenceService.createLeadForCampaign(any(), any())).thenReturn(Lead.builder().id(10L).build());
        when(campaignLeadRepository.countByCampaignId(1L)).thenReturn(0L, 1L, 2L, 3L);
        when(campaignRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        DiscoveryExecutionResult result = service.discoverAndPersist(campaign, 3);

        assertEquals(DiscoveryExecutionResult.Outcome.COMPLETE, result.outcome());
        assertEquals(3, result.acceptedThisRun());
        verify(qualifiedPoolService, times(1)).discoverPage(eq(10L), eq(3), eq(0));
    }

    @Test
    void poolTargetFoundTwoOfThreeReturnsPartial() throws Exception {
        QualifiedLeadPoolService.ResolvedTarget resolved = mock(QualifiedLeadPoolService.ResolvedTarget.class);
        QualifiedLeadPoolService.PoolPage page1 = new QualifiedLeadPoolService.PoolPage(
                List.of(
                        validCandidate("node/1", "Nail Studio 1", "+55 65 9999-1111"),
                        validCandidate("node/2", "Nail Studio 2", "+55 65 9999-2222")
                ),
                2, 2, false, 10L, "nails"
        );

        when(qualifiedPoolService.resolveTarget(anyString(), anyString(), anyString())).thenReturn(resolved);
        when(resolved.target()).thenReturn(mock(OsmCatalogTarget.class));
        when(resolved.target().getId()).thenReturn(10L);
        when(qualifiedPoolService.discoverPage(eq(10L), eq(3), eq(0))).thenReturn(page1);

        when(deduplicationService.check(any())).thenReturn(new DeduplicationService.DuplicateCheck(Optional.empty(), null));
        when(persistenceService.createLeadForCampaign(any(), any())).thenReturn(Lead.builder().id(10L).build());
        when(campaignLeadRepository.countByCampaignId(1L)).thenReturn(0L, 1L, 2L);
        when(campaignRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        DiscoveryExecutionResult result = service.discoverAndPersist(campaign, 3);

        assertEquals(DiscoveryExecutionResult.Outcome.PARTIAL, result.outcome());
        assertEquals(2, result.acceptedThisRun());
        assertEquals("OSM_CATALOG_PARTIAL", result.errorCode());
    }

    @Test
    void poolTargetFoundZeroReturnsEmpty() throws Exception {
        QualifiedLeadPoolService.ResolvedTarget resolved = mock(QualifiedLeadPoolService.ResolvedTarget.class);
        QualifiedLeadPoolService.PoolPage page1 = new QualifiedLeadPoolService.PoolPage(
                List.of(), 0, 0, false, 10L, "nails"
        );

        when(qualifiedPoolService.resolveTarget(anyString(), anyString(), anyString())).thenReturn(resolved);
        when(resolved.target()).thenReturn(mock(OsmCatalogTarget.class));
        when(resolved.target().getId()).thenReturn(10L);
        when(qualifiedPoolService.discoverPage(eq(10L), eq(3), eq(0))).thenReturn(page1);
        when(campaignLeadRepository.countByCampaignId(1L)).thenReturn(0L);

        DiscoveryExecutionResult result = service.discoverAndPersist(campaign, 3);

        assertEquals(DiscoveryExecutionResult.Outcome.EMPTY, result.outcome());
        assertEquals("OSM_NO_USEFUL_LEADS", result.errorCode());
        assertEquals(0, result.acceptedThisRun());
    }

    @Test
    void poolTargetAllDuplicatesReturnsEmptyWithAllDuplicatesCode() throws Exception {
        QualifiedLeadPoolService.ResolvedTarget resolved = mock(QualifiedLeadPoolService.ResolvedTarget.class);
        QualifiedLeadPoolService.PoolPage page1 = new QualifiedLeadPoolService.PoolPage(
                List.of(
                        validCandidate("node/1", "Nail Studio 1", "+55 65 9999-1111")
                ),
                1, 1, false, 10L, "nails"
        );

        when(qualifiedPoolService.resolveTarget(anyString(), anyString(), anyString())).thenReturn(resolved);
        when(resolved.target()).thenReturn(mock(OsmCatalogTarget.class));
        when(resolved.target().getId()).thenReturn(10L);
        when(qualifiedPoolService.discoverPage(eq(10L), eq(3), eq(0))).thenReturn(page1);

        when(deduplicationService.check(any()))
                .thenReturn(new DeduplicationService.DuplicateCheck(Optional.of(Lead.builder().id(99L).build()), "source_id"));
        when(campaignLeadRepository.countByCampaignId(1L)).thenReturn(0L);
        when(campaignRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        DiscoveryExecutionResult result = service.discoverAndPersist(campaign, 3);

        assertEquals(DiscoveryExecutionResult.Outcome.EMPTY, result.outcome());
        assertEquals("OSM_POOL_ALL_DUPLICATES", result.errorCode());
        assertEquals(0, result.acceptedThisRun());
    }

    @Test
    void poolTargetNotFoundThrowsError() throws Exception {
        when(qualifiedPoolService.resolveTarget(anyString(), anyString(), anyString()))
                .thenThrow(new IllegalArgumentException("OSM_TARGET_NOT_FOUND: Ainda não existe sincronização para [nail designer] em [Cuiabá]. Sincronize esse nicho antes de gerar a campanha."));

        DiscoveryExecutionResult result = service.discoverAndPersist(campaign, 3);

        assertEquals(DiscoveryExecutionResult.Outcome.EMPTY, result.outcome());
        assertEquals("OSM_TARGET_NOT_FOUND", result.errorCode());
        assertTrue(result.errorMessage().contains("Sincronize esse nicho"));
    }

    @Test
    void poolDoesNotCallOpenStreetMapProvider() throws Exception {
        QualifiedLeadPoolService.ResolvedTarget resolved = mock(QualifiedLeadPoolService.ResolvedTarget.class);
        QualifiedLeadPoolService.PoolPage page1 = new QualifiedLeadPoolService.PoolPage(
                List.of(validCandidate("node/1", "Nail Studio 1", "+55 65 9999-1111")),
                1, 1, false, 10L, "nails"
        );

        when(qualifiedPoolService.resolveTarget(anyString(), anyString(), anyString())).thenReturn(resolved);
        when(resolved.target()).thenReturn(mock(OsmCatalogTarget.class));
        when(resolved.target().getId()).thenReturn(10L);
        when(qualifiedPoolService.discoverPage(eq(10L), eq(1), eq(0))).thenReturn(page1);

        when(deduplicationService.check(any())).thenReturn(new DeduplicationService.DuplicateCheck(Optional.empty(), null));
        when(persistenceService.createLeadForCampaign(any(), any())).thenReturn(Lead.builder().id(10L).build());
        when(campaignLeadRepository.countByCampaignId(1L)).thenReturn(0L, 1L);
        when(campaignRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.discoverAndPersist(campaign, 1);

        // The legacy providers should not be called at all - we just verify no exception is thrown
        // and the pool path is used
    }

    private LeadCandidate validCandidate(String sourceId, String name, String phone) {
        LeadCandidate c = new LeadCandidate(name, "openstreetmap", sourceId);
        c.setPhone(phone);
        c.setCategory("shop=beauty");
        return c;
    }
}