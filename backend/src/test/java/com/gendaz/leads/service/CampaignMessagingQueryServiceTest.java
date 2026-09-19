package com.gendaz.leads.service;

import com.gendaz.leads.dto.campaign.CampaignMessagingLeadResponse;
import com.gendaz.leads.entity.Campaign;
import com.gendaz.leads.entity.Lead;
import com.gendaz.leads.entity.LeadAnalysis;
import com.gendaz.leads.entity.MessageSend;
import com.gendaz.leads.repository.LeadAnalysisRepository;
import com.gendaz.leads.repository.LeadRepository;
import com.gendaz.leads.repository.MessageSendRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CampaignMessagingQueryServiceTest {

    @Mock
    CampaignService campaignService;
    @Mock
    LeadRepository leadRepository;
    @Mock
    LeadAnalysisRepository leadAnalysisRepository;
    @Mock
    MessageSendRepository messageSendRepository;
    @Mock
    LeadMessagingEligibilityService eligibilityService;

    @InjectMocks
    CampaignMessagingQueryService queryService;

    private Campaign campaign() {
        return Campaign.builder().id(10L).ownerId(1L).niche("barbearia")
                .location("Cuiaba").requestedQuantity(5).status("COMPLETED").build();
    }

    private Lead lead(Long id) {
        Lead l = new Lead();
        l.setId(id);
        l.setBusinessName("Empresa " + id);
        l.setCategory("shop:barber");
        l.setPhone("11999999999");
        l.setCountry("BR");
        l.setCity("Cuiaba");
        l.setState("MT");
        l.setStatus("MESSAGE_READY");
        l.setCurrentCampaignId(10L);
        return l;
    }

    @Test
    void listsWithBatchQueriesAndNoNPlusOne() {
        Campaign c = campaign();
        when(campaignService.requireOwnedCampaign(10L)).thenReturn(c);
        when(leadRepository.findByCampaign(10L)).thenReturn(List.of(lead(1L), lead(2L)));
        when(leadAnalysisRepository.findByLeadIdIn(anyCollection())).thenReturn(List.of(
                LeadAnalysis.builder().leadId(1L).opportunityScore(82).detectedSystem("Gendaz").build()));
        MessageSend latest = MessageSend.builder().id(7L).leadId(2L).campaignId(10L)
                .status("SENT").attempts(1).queuedAt(Instant.now()).sentAt(Instant.now()).build();
        when(messageSendRepository.findByCampaignId(10L)).thenReturn(List.of(latest));
        when(eligibilityService.loadSendsByLead(anyCollection()))
                .thenReturn(java.util.Map.of(2L, List.of(latest)));
        when(eligibilityService.checkEligibility(any(), eq(10L), anyList()))
                .thenReturn(new EligibilityResult(true, null, null, "5511999999999"));

        List<CampaignMessagingLeadResponse> out = queryService.listMessagingLeads(10L);

        assertEquals(2, out.size());
        // Score/sistema via LeadAnalysis em lote
        assertEquals(82, out.get(0).opportunityScore());
        assertEquals("Gendaz", out.get(0).detectedSystem());
        assertNull(out.get(1).opportunityScore());
        // Latest send resolvido
        assertEquals("SENT", out.get(1).sendStatus());
        assertNull(out.get(0).sendStatus());

        verify(leadAnalysisRepository, times(1)).findByLeadIdIn(anyCollection());
        verify(messageSendRepository, times(1)).findByCampaignId(10L);
        // Proibido N+1: nunca buscar por lead individual
        verify(leadAnalysisRepository, never()).findByLeadId(any());
        verify(messageSendRepository, never()).findByLeadId(any());
    }

    @Test
    void ineligibilitySurfacedFromEligibilityService() {
        Campaign c = campaign();
        when(campaignService.requireOwnedCampaign(10L)).thenReturn(c);
        when(leadRepository.findByCampaign(10L)).thenReturn(List.of(lead(1L)));
        when(leadAnalysisRepository.findByLeadIdIn(anyCollection())).thenReturn(List.of());
        when(messageSendRepository.findByCampaignId(10L)).thenReturn(List.of());
        when(eligibilityService.loadSendsByLead(anyCollection())).thenReturn(java.util.Map.of());
        when(eligibilityService.checkEligibility(any(), eq(10L), anyList()))
                .thenReturn(new EligibilityResult(false, "NO_PHONE", "Sem telefone.", null));

        List<CampaignMessagingLeadResponse> out = queryService.listMessagingLeads(10L);

        assertEquals(1, out.size());
        assertFalse(out.get(0).eligible());
        assertEquals("NO_PHONE", out.get(0).ineligibilityCode());
    }
}
