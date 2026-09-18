package com.gendaz.leads.controller;

import com.gendaz.leads.dto.campaign.*;
import com.gendaz.leads.entity.Campaign;
import com.gendaz.leads.entity.MessageTemplate;
import com.gendaz.leads.service.*;
import org.springframework.data.domain.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import jakarta.validation.*;
import java.util.*;
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

    // --- NOVOS ENDPOINTS DE TEMPLATE ---

    @GetMapping("/{campaignId}/message-templates/default")
    public ResponseEntity<MessageTemplate> getDefaultTemplate(@PathVariable Long campaignId) {
        campaignService.get(campaignId);
        return ResponseEntity.ok(new MessageTemplate());
    }

    @PutMapping("/{campaignId}/message-templates/default")
    public ResponseEntity<MessageTemplate> setDefaultTemplate(@PathVariable Long campaignId,
                                                            @RequestBody MessageTemplate template) {
        campaignService.get(campaignId);
        return ResponseEntity.ok(template);
    }

    // --- NOVOS ENDPOINTS DE PREVIEW E ENVIO ---

    @PostMapping("/{campaignId}/send-preview")
    public ResponseEntity<SendPreviewResponse> generateSendPreview(
            @PathVariable Long campaignId,
            @RequestBody(required = false) SendPreviewRequest request) {
        Long templateId = request != null && request.getTemplateId() != null ?
                request.getTemplateId() : null;
        String templateText = request != null && request.getTemplateText() != null ?
                request.getTemplateText() : null;
        Boolean allEligible = request != null && request.isAllEligible();

        SendPreviewResponse preview = campaignSendService.generatePreview(
                campaignId,
                request != null && request.getLeadIds() != null ? request.getLeadIds() : null,
                templateText,
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
                request != null && request.getTemplateId() != null ? request.getTemplateId() : null
        );

        return ResponseEntity.ok(result);
    }
}