package com.gendaz.leads.service;

import com.gendaz.leads.dto.campaign.SendPreviewResponse;
import com.gendaz.leads.dto.campaign.SendResultEnqueue;
import com.gendaz.leads.entity.Campaign;
import com.gendaz.leads.entity.Lead;
import com.gendaz.leads.entity.MessageSend;
import com.gendaz.leads.entity.MessageTemplate;
import com.gendaz.leads.exception.ApiException;
import com.gendaz.leads.repository.CampaignLeadRepository;
import com.gendaz.leads.repository.LeadEventRepository;
import com.gendaz.leads.repository.LeadRepository;
import com.gendaz.leads.repository.MessageSendRepository;
import com.gendaz.leads.repository.MessageTemplateRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.mockito.Mockito.lenient;

@ExtendWith(MockitoExtension.class)
class CampaignSendServiceTest {

    @Mock
    CampaignService campaignService;
    @Mock
    LeadRepository leadRepository;
    @Mock
    CampaignLeadRepository campaignLeadRepository;
    @Mock
    MessageSendRepository messageSendRepository;
    @Mock
    MessageTemplateRepository templateRepository;
    @Mock
    LeadEventRepository leadEventRepository;
    @Mock
    TemplateRenderer templateRenderer;
    @Mock
    LeadMessagingEligibilityService eligibilityService;

    @InjectMocks
    CampaignSendService sendService;

    private Campaign campaign() {
        return Campaign.builder().id(10L).ownerId(1L).niche("barbearia")
                .location("Cuiaba").requestedQuantity(5).status("COMPLETED").build();
    }

    private MessageTemplate template() {
        return MessageTemplate.builder().id(3L).name("default")
                .templateText("Ola {{nome}}").isDefault(true).build();
    }

    private Lead lead(Long id) {
        Lead l = new Lead();
        l.setId(id);
        l.setBusinessName("Empresa " + id);
        l.setPhone("11999999999");
        l.setCountry("BR");
        l.setCity("Cuiaba");
        l.setState("MT");
        l.setStatus("MESSAGE_READY");
        l.setCurrentCampaignId(10L);
        return l;
    }

    @Test
    void previewSelectedBuildsStructuredResponse() {
        when(campaignService.requireOwnedCampaign(10L)).thenReturn(campaign());
        when(templateRepository.findFirstByIsDefaultTrue()).thenReturn(Optional.of(template()));
        when(leadRepository.findAllById(List.of(1L))).thenReturn(List.of(lead(1L)));
        lenient().when(campaignLeadRepository.existsByCampaignIdAndLeadId(any(), any())).thenReturn(true);
        when(eligibilityService.loadSendsByLead(anyCollection())).thenReturn(java.util.Map.of());
        when(eligibilityService.checkEligibility(any(), eq(10L), anyList()))
                .thenReturn(new EligibilityResult(true, null, null, "5511999999999"));
        when(templateRenderer.render(any(), any(), any())).thenReturn("Ola Empresa 1");

        SendPreviewResponse res = sendService.generatePreview(10L, List.of(1L), false);

        assertEquals(1, res.eligibleCount());
        assertEquals(0, res.ineligibleCount());
        assertEquals(1, res.previews().size());
        assertEquals("Ola Empresa 1", res.previews().get(0).message());
        assertEquals("Empresa 1", res.previews().get(0).businessName());
        verify(messageSendRepository, never()).save(any());
        verify(messageSendRepository, never()).saveAndFlush(any());
    }

    @Test
    void previewAllEligibleLoadsCampaignLeads() {
        when(campaignService.requireOwnedCampaign(10L)).thenReturn(campaign());
        when(templateRepository.findFirstByIsDefaultTrue()).thenReturn(Optional.of(template()));
        when(leadRepository.findByCampaign(10L)).thenReturn(List.of(lead(1L), lead(2L)));
        lenient().when(campaignLeadRepository.existsByCampaignIdAndLeadId(any(), any())).thenReturn(true);
        when(eligibilityService.loadSendsByLead(anyCollection())).thenReturn(java.util.Map.of());
        when(eligibilityService.checkEligibility(any(), eq(10L), anyList()))
                .thenReturn(new EligibilityResult(true, null, null, "5511999999999"));
        when(templateRenderer.render(any(), any(), any())).thenReturn("msg");

        SendPreviewResponse res = sendService.generatePreview(10L, null, true);

        assertEquals(2, res.eligibleCount());
        verify(leadRepository, never()).findAllById(any());
    }

    @Test
    void previewOwnershipPropagates() {
        when(campaignService.requireOwnedCampaign(99L))
                .thenThrow(new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "negado"));

        assertThrows(ApiException.class, () -> sendService.generatePreview(99L, List.of(1L), false));
    }

    @Test
    void previewWrongCampaignStructured() {
        when(campaignService.requireOwnedCampaign(10L)).thenReturn(campaign());
        when(templateRepository.findFirstByIsDefaultTrue()).thenReturn(Optional.of(template()));
        Lead other = lead(5L);
        other.setCurrentCampaignId(77L);
        when(leadRepository.findAllById(List.of(5L))).thenReturn(List.of(other));
        when(campaignLeadRepository.existsByCampaignIdAndLeadId(10L, 5L)).thenReturn(false);
        when(eligibilityService.loadSendsByLead(anyCollection())).thenReturn(java.util.Map.of());

        SendPreviewResponse res = sendService.generatePreview(10L, List.of(5L), false);

        assertEquals(0, res.eligibleCount());
        assertEquals(1, res.ineligibleCount());
        assertEquals("WRONG_CAMPAIGN", res.ineligible().get(0).code());
    }

    @Test
    void previewIneligibleStructuredWithCodeReason() {
        when(campaignService.requireOwnedCampaign(10L)).thenReturn(campaign());
        when(templateRepository.findFirstByIsDefaultTrue()).thenReturn(Optional.of(template()));
        when(leadRepository.findAllById(List.of(1L))).thenReturn(List.of(lead(1L)));
        lenient().when(campaignLeadRepository.existsByCampaignIdAndLeadId(any(), any())).thenReturn(true);
        when(eligibilityService.loadSendsByLead(anyCollection())).thenReturn(java.util.Map.of());
        when(eligibilityService.checkEligibility(any(), eq(10L), anyList()))
                .thenReturn(new EligibilityResult(false, "DO_NOT_CONTACT", "Nao prospectar.", null));

        SendPreviewResponse res = sendService.generatePreview(10L, List.of(1L), false);

        assertEquals(0, res.eligibleCount());
        assertEquals(1, res.ineligibleCount());
        assertEquals("DO_NOT_CONTACT", res.ineligible().get(0).code());
        assertEquals("Nao prospectar.", res.ineligible().get(0).reason());
    }

    @Test
    void previewTemplateErrorFails() {
        when(campaignService.requireOwnedCampaign(10L)).thenReturn(campaign());
        when(templateRepository.findFirstByIsDefaultTrue()).thenReturn(Optional.of(template()));
        when(leadRepository.findAllById(List.of(1L))).thenReturn(List.of(lead(1L)));
        lenient().when(campaignLeadRepository.existsByCampaignIdAndLeadId(any(), any())).thenReturn(true);
        when(eligibilityService.loadSendsByLead(anyCollection())).thenReturn(java.util.Map.of());
        when(eligibilityService.checkEligibility(any(), eq(10L), anyList()))
                .thenReturn(new EligibilityResult(true, null, null, "5511999999999"));
        when(templateRenderer.render(any(), any(), any()))
                .thenThrow(new IllegalArgumentException("Variável desconhecida no template: {{foo}}"));

        assertThrows(IllegalArgumentException.class,
                () -> sendService.generatePreview(10L, List.of(1L), false));
    }

    @Test
    void enqueuePersistsSnapshotRecipientRequestId() {
        when(campaignService.requireOwnedCampaign(10L)).thenReturn(campaign());
        when(templateRepository.findFirstByIsDefaultTrue()).thenReturn(Optional.of(template()));
        when(leadRepository.findAllById(List.of(1L))).thenReturn(List.of(lead(1L)));
        lenient().when(campaignLeadRepository.existsByCampaignIdAndLeadId(any(), any())).thenReturn(true);
        when(eligibilityService.loadSendsByLead(anyCollection())).thenReturn(java.util.Map.of());
        when(eligibilityService.checkEligibility(any(), eq(10L), anyList()))
                .thenReturn(new EligibilityResult(true, null, null, "5511999999999"));
        when(templateRenderer.render(any(), any(), any())).thenReturn("Ola Empresa 1");
        when(messageSendRepository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));

        // defaultProvider field is @Value; set via reflection
        org.springframework.test.util.ReflectionTestUtils.setField(sendService, "defaultProvider", "whatsapp");

        SendResultEnqueue res = sendService.enqueueMessages(10L, List.of(1L), null, false);

        assertEquals(1, res.getSent().size());
        ArgumentCaptor<MessageSend> captor = ArgumentCaptor.forClass(MessageSend.class);
        verify(messageSendRepository).saveAndFlush(captor.capture());
        MessageSend saved = captor.getValue();
        assertEquals("Ola Empresa 1", saved.getMessageTextSnapshot());
        assertEquals("5511999999999", saved.getRecipientSnapshot());
        assertNotNull(saved.getRequestId());
        assertFalse(saved.getRequestId().isBlank());
        assertEquals("whatsapp", saved.getProvider());
        assertEquals("QUEUED", saved.getStatus());
        assertEquals(0, saved.getAttempts());
        assertNotNull(saved.getQueuedAt());
        assertEquals(3L, saved.getTemplateId());
        // message_queued event without phone/message
        verify(leadEventRepository).save(argThat(e ->
                "message_queued".equals(e.getEventType())
                        && (e.getEventMetadata() == null || !e.getEventMetadata().contains("5511999999999"))));
    }

    @Test
    void enqueueDoubleClickSecondIsSkipped() {
        when(campaignService.requireOwnedCampaign(10L)).thenReturn(campaign());
        when(templateRepository.findFirstByIsDefaultTrue()).thenReturn(Optional.of(template()));
        when(leadRepository.findAllById(List.of(1L))).thenReturn(List.of(lead(1L)));
        lenient().when(campaignLeadRepository.existsByCampaignIdAndLeadId(any(), any())).thenReturn(true);
        when(eligibilityService.loadSendsByLead(anyCollection())).thenReturn(java.util.Map.of());
        when(eligibilityService.checkEligibility(any(), eq(10L), anyList()))
                .thenReturn(new EligibilityResult(false, "ALREADY_QUEUED", "Ja esta na fila.", "5511999999999"));

        SendResultEnqueue res = sendService.enqueueMessages(10L, List.of(1L), null, false);

        assertTrue(res.getSent().isEmpty());
        assertEquals(1, res.getSkipped().size());
        verify(messageSendRepository, never()).saveAndFlush(any());
    }

    @Test
    void enqueueConstraintRaceMapsTo409() {
        when(campaignService.requireOwnedCampaign(10L)).thenReturn(campaign());
        when(templateRepository.findFirstByIsDefaultTrue()).thenReturn(Optional.of(template()));
        when(leadRepository.findAllById(List.of(1L))).thenReturn(List.of(lead(1L)));
        lenient().when(campaignLeadRepository.existsByCampaignIdAndLeadId(any(), any())).thenReturn(true);
        when(eligibilityService.loadSendsByLead(anyCollection())).thenReturn(java.util.Map.of());
        when(eligibilityService.checkEligibility(any(), eq(10L), anyList()))
                .thenReturn(new EligibilityResult(true, null, null, "5511999999999"));
        when(templateRenderer.render(any(), any(), any())).thenReturn("msg");
        when(messageSendRepository.saveAndFlush(any()))
                .thenThrow(new DataIntegrityViolationException("duplicate"));

        ApiException ex = assertThrows(ApiException.class,
                () -> sendService.enqueueMessages(10L, List.of(1L), null, false));
        assertEquals(HttpStatus.CONFLICT, ex.getStatus());
        assertEquals("ALREADY_QUEUED_OR_SENT", ex.getCode());
    }
}
