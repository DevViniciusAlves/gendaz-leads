package com.gendaz.leads.controller;

import com.gendaz.leads.dto.campaign.CampaignResponse;
import com.gendaz.leads.dto.campaign.CreateCampaignRequest;
import com.gendaz.leads.service.CampaignService;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/campaigns")
public class CampaignController {

    private final CampaignService campaignService;

    public CampaignController(CampaignService campaignService) {
        this.campaignService = campaignService;
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
}
