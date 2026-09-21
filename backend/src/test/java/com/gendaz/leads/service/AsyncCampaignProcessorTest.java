package com.gendaz.leads.service;

import com.gendaz.leads.entity.Campaign;
import com.gendaz.leads.entity.Lead;
import com.gendaz.leads.repository.CampaignRepository;
import com.gendaz.leads.repository.CampaignLeadRepository;
import com.gendaz.leads.repository.LeadRepository;
import com.gendaz.leads.repository.LeadEventRepository;
import com.gendaz.leads.service.DiscoveryExecutionResult;
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
class AsyncCampaignProcessorTest {

    @Mock CampaignRepository campaignRepository;
    @Mock LeadRepository leadRepository;
    @Mock LeadEventRepository leadEventRepository;
    @Mock LeadAnalysisService leadAnalysisService;
    @Mock CampaignLeadDiscoveryService campaignLeadDiscoveryService;
    @Mock CampaignLeadRepository campaignLeadRepository;

    private AsyncCampaignProcessor processor;
    private Campaign campaign;

    @BeforeEach
    void setUp() {
        processor = new AsyncCampaignProcessor(
                campaignRepository, leadRepository, leadEventRepository, leadAnalysisService,
                campaignLeadDiscoveryService, campaignLeadRepository
        );

        campaign = Campaign.builder()
                .id(1L)
                .requestedQuantity(3)
                .status("NEW")
                .build();
    }

    @Test
    void initialPartialAnalyzesLeadsFound() {
        when(campaignRepository.findById(1L)).thenReturn(Optional.of(campaign));
        when(campaignLeadRepository.countByCampaignId(1L)).thenReturn(2L);
        when(leadRepository.findByCampaign(1L)).thenReturn(List.of(
                Lead.builder().id(10L).status("NEW").build(),
                Lead.builder().id(11L).status("NEW").build()
        ));
        when(campaignLeadDiscoveryService.discoverAndPersist(any(), any()))
                .thenReturn(new DiscoveryExecutionResult(
                        DiscoveryExecutionResult.Outcome.PARTIAL, 2, 2, 1, 1, 0, 0, true, null, null
                ));
        when(campaignRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(leadRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(leadRepository.countByCampaignIdWithAnalysis(1L)).thenReturn(2L);
        when(leadRepository.countByCampaignIdAndStatusIn(eq(1L), anyList())).thenReturn(2L);
        when(leadRepository.countByCampaignIdAndStatusIn(eq(1L), anyList())).thenReturn(0L);

        processor.processCampaign(1L);

        verify(leadAnalysisService, times(2)).analyzeAndGenerate(any(), eq(1L));
    }

    @Test
    void retryPartialWithInfraAndExistingLeadsContinuesPartial() {
        when(campaignRepository.findById(1L)).thenReturn(Optional.of(campaign));
        when(campaignLeadRepository.countByCampaignId(1L)).thenReturn(2L);
        when(leadRepository.findByCampaign(1L)).thenReturn(List.of(
                Lead.builder().id(10L).status("NEW").build(),
                Lead.builder().id(11L).status("NEW").build()
        ));
        when(campaignLeadDiscoveryService.discoverAndPersist(any(), any()))
                .thenReturn(new DiscoveryExecutionResult(
                        DiscoveryExecutionResult.Outcome.PARTIAL, 0, 2, 1, 0, 0, 0, true, "OSM_DISCOVERY_TIMEOUT", "Budget esgotado"
                ));
        when(campaignRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(leadRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(leadRepository.countByCampaignIdWithAnalysis(1L)).thenReturn(2L);
        when(leadRepository.countByCampaignIdAndStatusIn(eq(1L), anyList())).thenReturn(2L);
        when(leadRepository.countByCampaignIdAndStatusIn(eq(1L), anyList())).thenReturn(0L);

        processor.processPartialCampaign(1L, 1);

        assertEquals("PARTIAL", campaign.getStatus());
    }

    @Test
    void retryWithoutAnyLeadCanBecomeFailed() {
        Campaign emptyCampaign = Campaign.builder()
                .id(2L)
                .requestedQuantity(3)
                .status("PARTIAL")
                .build();

        when(campaignRepository.findById(2L)).thenReturn(Optional.of(emptyCampaign));
        when(campaignLeadRepository.countByCampaignId(2L)).thenReturn(0L);
        when(campaignLeadDiscoveryService.discoverAndPersist(any(), any()))
                .thenReturn(new DiscoveryExecutionResult(
                        DiscoveryExecutionResult.Outcome.INFRA_UNAVAILABLE, 0, 0, 1, 0, 0, 0, true, "OSM_DISCOVERY_TIMEOUT", "Budget esgotado"
                ));
        when(campaignRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(leadRepository.countByCampaignIdWithAnalysis(2L)).thenReturn(0L);
        when(leadRepository.countByCampaignIdAndStatusIn(eq(2L), anyList())).thenReturn(0L);
        when(leadRepository.countByCampaignIdAndStatusIn(eq(2L), anyList())).thenReturn(0L);

        processor.processPartialCampaign(2L, 3);

        assertEquals("FAILED", emptyCampaign.getStatus());
    }

    @Test
    void retryFailedLeadsCallsFinalize() {
        when(campaignRepository.findById(1L)).thenReturn(Optional.of(campaign));
        when(leadRepository.findByCampaign(1L)).thenReturn(List.of(
                Lead.builder().id(10L).status("ERROR").build(),
                Lead.builder().id(11L).status("NEW").build()
        ));
        when(campaignRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(leadRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(campaignLeadRepository.countByCampaignId(1L)).thenReturn(2L);
        when(leadRepository.countByCampaignIdWithAnalysis(1L)).thenReturn(2L);
        when(leadRepository.countByCampaignIdAndStatusIn(eq(1L), anyList())).thenReturn(2L);
        when(leadRepository.countByCampaignIdAndStatusIn(eq(1L), anyList())).thenReturn(0L);

        processor.retryFailedLeads(1L);

        verify(leadAnalysisService).analyzeAndGenerate(any(), eq(1L));
        verify(campaignRepository).save(any());
    }

    @Test
    void campaignCanGoFromPartialToCompleted() {
        Campaign partialCampaign = Campaign.builder()
                .id(1L)
                .requestedQuantity(3)
                .status("PARTIAL")
                .build();

        when(campaignRepository.findById(1L)).thenReturn(Optional.of(partialCampaign));
        when(leadRepository.findByCampaign(1L)).thenReturn(List.of(
                Lead.builder().id(10L).status("ERROR").build()
        ));
        when(campaignLeadRepository.countByCampaignId(1L)).thenReturn(3L);
        when(leadRepository.countByCampaignIdWithAnalysis(1L)).thenReturn(3L);
        when(leadRepository.countByCampaignIdAndStatusIn(eq(1L), anyList())).thenReturn(3L);
        when(leadRepository.countByCampaignIdAndStatusIn(eq(1L), anyList())).thenReturn(0L);
        when(campaignRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(leadRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        processor.retryFailedLeads(1L);

        assertEquals("COMPLETED", partialCampaign.getStatus());
    }

    @Test
    void analyzedCountComesFromLeadAnalysisReal() {
        when(campaignRepository.findById(1L)).thenReturn(Optional.of(campaign));
        when(campaignLeadRepository.countByCampaignId(1L)).thenReturn(3L);
        when(leadRepository.findByCampaign(1L)).thenReturn(List.of(
                Lead.builder().id(10L).status("NEW").build(),
                Lead.builder().id(11L).status("NEW").build(),
                Lead.builder().id(12L).status("NEW").build()
        ));
        when(campaignLeadDiscoveryService.discoverAndPersist(any(), any()))
                .thenReturn(new DiscoveryExecutionResult(
                        DiscoveryExecutionResult.Outcome.COMPLETE, 3, 3, 1, 1, 0, 0, true, null, null
                ));
        when(campaignRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(leadRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(leadRepository.countByCampaignIdWithAnalysis(1L)).thenReturn(3L);
        when(leadRepository.countByCampaignIdAndStatusIn(eq(1L), anyList())).thenReturn(3L);
        when(leadRepository.countByCampaignIdAndStatusIn(eq(1L), anyList())).thenReturn(0L);

        processor.processCampaign(1L);

        assertEquals(3, campaign.getAnalyzedCount());
    }
}