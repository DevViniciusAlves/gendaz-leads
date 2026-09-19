package com.gendaz.leads.controller;

import com.gendaz.leads.dto.campaign.*;
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
    private final CampaignMessagingQueryService messagingQueryService;

    public CampaignController(CampaignService campaignService,
                              CampaignSendService campaignSendService,
                              CampaignMessagingQueryService messagingQueryService) {
        this.campaignService = campaignService;
        this.campaignSendService = campaignSendService;
        this.messagingQueryService = messagingQueryService;
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
        List<Long> leadIds = request != null ? request.getLeadIds() : null;
        Boolean allEligible = request != null ? request.getAllEligible() : null;

        SendPreviewResponse preview = campaignSendService.generatePreview(
                campaignId,
                leadIds,
                allEligible
        );

        return ResponseEntity.ok(preview);
    }

    @PostMapping("/{campaignId}/send")
    public ResponseEntity<SendResultEnqueue> enqueueMessages(
            @PathVariable Long campaignId,
            @RequestBody(required = false) EnqueueRequest request) {
        List<Long> leadIds = request != null ? request.getLeadIds() : null;

        SendResultEnqueue result = campaignSendService.enqueueMessages(
                campaignId,
                leadIds,
                request != null ? request.getTemplateId() : null,
                request != null ? request.getAllEligible() : null
        );

        return ResponseEntity.ok(result);
    }

    @GetMapping("/{campaignId}/messaging-leads")
    public ResponseEntity<List<CampaignMessagingLeadResponse>> getMessagingLeads(
            @PathVariable Long campaignId) {
        return ResponseEntity.ok(messagingQueryService.listMessagingLeads(campaignId));
    }
}
