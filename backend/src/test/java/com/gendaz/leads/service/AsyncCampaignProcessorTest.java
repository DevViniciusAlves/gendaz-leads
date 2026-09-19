package com.gendaz.leads.service;

import com.gendaz.leads.domain.LeadCandidate;
import com.gendaz.leads.entity.Campaign;
import com.gendaz.leads.exception.ApiException;
import com.gendaz.leads.repository.CampaignLeadRepository;
import com.gendaz.leads.repository.CampaignRepository;
import com.gendaz.leads.repository.LeadEventRepository;
import com.gendaz.leads.repository.LeadRepository;
import com.gendaz.leads.repository.LeadSourceRepository;
import com.gendaz.leads.service.provider.OpenStreetMapProvider;
import com.gendaz.leads.service.CampaignLeadPersistenceService;
import com.gendaz.leads.util.Normalizer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AsyncCampaignProcessorTest {

    @Mock
    CampaignRepository campaignRepository;
    @Mock
    LeadRepository leadRepository;
    @Mock
    CampaignLeadRepository campaignLeadRepository;
    @Mock
    LeadSourceRepository leadSourceRepository;
    @Mock
    LeadEventRepository leadEventRepository;
    @Mock
    LeadAnalysisService leadAnalysisService;
    @Mock
    DeduplicationService deduplicationService;
    @Mock
    Normalizer normalizer;
    @Mock
    OpenStreetMapProvider openStreetMapProvider;
    @Mock
    CampaignLeadPersistenceService persistenceService;

    @InjectMocks
    AsyncCampaignProcessor processor;

    private Campaign campaign() {
        Campaign c = Campaign.builder().id(1L).ownerId(9L).niche("cilios")
                .location("Cuiaba").requestedQuantity(5).status("CREATED").build();
        return c;
    }

    @Test
    void providerFailureMarksFailedWithoutFinalizeOverwrite() {
        Campaign c = campaign();
        when(campaignRepository.findById(1L)).thenReturn(Optional.of(c));
        when(openStreetMapProvider.isEnabled()).thenReturn(true);
        when(openStreetMapProvider.discover(any(), any(), anyInt()))
                .thenThrow(new ApiException(HttpStatus.BAD_GATEWAY, "OSM_OVERPASS_ERROR", "overpass down"));
        when(campaignRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        processor.processCampaign(1L);

        assertEquals("FAILED", c.getStatus());
        assertEquals("overpass down", c.getErrorMessage());
        // finalizeCampaign normal nunca executou: status nao virou COMPLETED/PARTIAL
        assertNotEquals("COMPLETED", c.getStatus());
        assertNotEquals("PARTIAL", c.getStatus());
        // analyzeStage nunca executou
        verify(leadAnalysisService, never()).analyzeAndGenerate(any(), any());
    }

    @Test
    void geocodeFailureSurfacesCorrectCode() {
        Campaign c = campaign();
        when(campaignRepository.findById(1L)).thenReturn(Optional.of(c));
        when(openStreetMapProvider.isEnabled()).thenReturn(true);
        when(openStreetMapProvider.discover(any(), any(), anyInt()))
                .thenThrow(new ApiException(HttpStatus.BAD_GATEWAY, "OSM_GEOCODE_ERROR", "geocode down"));
        when(campaignRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        processor.processCampaign(1L);

        assertEquals("FAILED", c.getStatus());
        assertEquals("geocode down", c.getErrorMessage());
    }

    @Test
    void realZeroKeepsNotFoundMessage() {
        Campaign c = campaign();
        when(campaignRepository.findById(1L)).thenReturn(Optional.of(c));
        when(openStreetMapProvider.isEnabled()).thenReturn(true);
        when(openStreetMapProvider.discover(any(), any(), anyInt())).thenReturn(List.of());
        when(campaignRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        processor.processCampaign(1L);

        // Zero real: finalize normal com mensagem canonica.
        assertEquals("FAILED", c.getStatus());
        assertEquals("Nenhum lead encontrado para os parametros informados.", c.getErrorMessage());
    }
}
