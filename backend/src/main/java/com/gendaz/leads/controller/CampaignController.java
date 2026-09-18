package com.gendaz.leads.controller;

import com.gendaz.leads.dto.campaign.*;
import com.gendaz.leads.entity.LeadAnalysis;
import com.gendaz.leads.service.*;
import org.springframework.data.domain.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import jakarta.validation.*;
import java.util.*;

@RestController
@RequestMapping("/api/campaigns")
public class CampaignController {

    private final CampaignService campaignService;
    private final CampaignSendService campaignSendService;

    public CampaignController(CampaignService campaignService, CampaignSendService campaignSendService) {
        this.campaignService = campaignService;
        this.campaignSendService = campaignSendService;
    }

    @PostMapping
    public ResponseEntity<CampaignResponse> create(@Valid @RequestBody CreateCampaignRequest request) {
        return ResponseEntity.ok(campaignService.create(request));
    }

    @GetMapping
    public ResponseEntity<Page<CampaignResponse>> list(@RequestParam(defaultValue = "0") int page,
                                                       @RequestParam(defaultValue = "20") int size) {
        Pageable pageable = PageRequest.of(Math.max(0, page), Math.max(1, Math.min(100, size)));
        return ResponseEntity.ok(campaignService.list(pageable));
    }

    @GetMapping("/{id}")
    public ResponseEntity<CampaignResponse> get(@PathVariable Long id) {
        return ResponseEntity.ok(campaignService.get(id));
    }

    @PostMapping("/{id}/retry")
    public ResponseEntity<Void> retry(@PathVariable Long id) {
        campaignService.retry(id);
        return ResponseEntity.accepted().build();
    }

    @PostMapping("/{campaignId}/send-preview")
    public ResponseEntity<SendPreviewResponse> generateSendPreview(
            @PathVariable Long campaignId,
            @RequestBody(required = false) SendPreviewRequest request) {
        Long templateId = request != null && request.getTemplateId() != null ?
                request.getTemplateId() : null;
        Boolean allEligible = request != null && request.isAllEligible();

        SendPreviewResponse preview = campaignSendService.generatePreview(
                campaignId,
                request != null && request.getLeadIds() != null ? request.getLeadIds() : null,
                null,
                allEligible
        );

        return ResponseEntity.ok(preview);
    }

    @PostMapping("/{campaignId}/send")
    public ResponseEntity<SendResultEnqueue> enqueueMessages(
            @PathVariable Long campaignId,
            @RequestBody(required = false) EnqueueRequest request) {
        List<Long> leadIds = request != null && request.getLeadIds() != null
                ? request.getLeadIds() : null;

        SendResultEnqueue result = campaignSendService.enqueueMessages(
                campaignId,
                leadIds,
                request != null && request.getTemplateId() != null ? request.getTemplateId() : null,
                request != null && request.getAllEligible() != null ? request.getAllEligible() : false
        );

        return ResponseEntity.ok(result);
    }

    @GetMapping("/{campaignId}/messaging-leads")
    public ResponseEntity<List<CampaignMessagingLeadResponse>> getMessagingLeads(
            @PathVariable Long campaignId) {
        Campaign campaign = campaignRepository.findById(campaignId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Campanha nao encontrada."));
        ensureOwner(campaign);

        List<Lead> leads = leadRepository.findByCampaign(campaignId);
        List<CampaignMessagingLeadResponse> response = new ArrayList<>();

        // Batch fetch message sends for all leads
        List<Long> leadIds = leads.stream().map(Lead::getId).toList();
        Map<Long, MessageSend> latestSendByLead = new HashMap<>();
        if (!leadIds.isEmpty()) {
            leadRepository.findByIdIn(leadIds).forEach(lead -> {
                List<MessageSend> sends = messageSendRepository.findByLeadId(lead.getId());
                MessageSend latest = sends.stream()
                        .max((a, b) -> a.getCreatedAt().compareTo(b.getCreatedAt()))
                        .orElse(null);
                if (latest != null) {
                    latestSendByLead.put(lead.getId(), latest);
                }
            });
        }

        // Batch fetch lead analyses for opportunityScore and detectedSystem
        Map<Long, LeadAnalysis> analysisByLead = new HashMap<>();
        if (!leadIds.isEmpty()) {
            leadRepository.findAllById(leadIds).forEach(lead -> {
                leadAnalysisRepository.findByLeadId(lead.getId()).ifPresent(analysis -> {
                    analysisByLead.put(lead.getId(), analysis);
                });
            });
        }

        for (Lead lead : leads) {
            MessageSend latestSend = latestSendByLead.get(lead.getId());
            LeadAnalysis analysis = analysisByLead.get(lead.getId());

            String sendStatus = null;
            Integer attempts = 0;
            Instant queuedAt = null;
            Instant sentAt = null;
            String errorCode = null;
            if (latestSend != null) {
                sendStatus = latestSend.getStatus();
                attempts = latestSend.getAttempts();
                queuedAt = latestSend.getQueuedAt();
                sentAt = latestSend.getSentAt();
                errorCode = latestSend.getErrorCode();
            }

            String detectedSystem = analysis != null ? analysis.getDetectedSystem() : null;
            Integer opportunityScore = analysis != null ? analysis.getOpportunityScore() : null;

            String ineligibilityCode = null;
            String ineligibilityReason = null;
            boolean eligible = true;

            // Check eligibility using the eligibility service logic
            if (latestSend != null) {
                if ("SENT".equals(latestSend.getStatus()) || "DELIVERY_UNKNOWN".equals(latestSend.getStatus())) {
                    eligible = false;
                    ineligibilityCode = latestSend.getErrorCode();
                    ineligibilityReason = "Já enviado anteriormente.";
                } else if ("QUEUED".equals(latestSend.getStatus()) || "SENDING".equals(latestSend.getStatus())) {
                    eligible = false;
                    ineligibilityCode = latestSend.getErrorCode();
                    ineligibilityReason = "Já está na fila ou enviando.";
                }
            }

            if (lead.isDoNotContact()) {
                eligible = false;
                ineligibilityCode = "DO_NOT_CONTACT";
                ineligibilityReason = "Não prospectar.";
            }

            if (lead.getPhone() == null || lead.getPhone().isBlank()) {
                eligible = false;
                ineligibilityCode = "NO_PHONE";
                ineligibilityReason = "Sem telefone.";
            }

            String normalizedPhone = null;
            if (lead.getPhone() != null && !lead.getPhone().isBlank()) {
                normalizedPhone = lead.getPhone(); // Will be normalized by frontend/normalizeRecipient
            }

            response.add(new CampaignMessagingLeadResponse(
                    lead.getId(),
                    lead.getBusinessName(),
                    lead.getCategory(),
                    lead.getInstagramUsername(),
                    lead.getInstagramUrl() != null ? lead.getInstagramUrl() : null,
                    lead.getPhone(),
                    lead.getCity(),
                    lead.getState(),
                    lead.getWebsite(),
                    opportunityScore,
                    detectedSystem,
                    lead.getStatus(),
                    eligible,
                    ineligibilityCode,
                    ineligibilityReason,
                    latestSend != null ? latestSend.getId() : null,
                    sendStatus,
                    attempts,
                    queuedAt,
                    sentAt,
                    errorCode
            ));
        }

        return ResponseEntity.ok(response);
    }
}