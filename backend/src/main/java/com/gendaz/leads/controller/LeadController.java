package com.gendaz.leads.controller;

import com.gendaz.leads.dto.PageResponse;
import com.gendaz.leads.dto.lead.LeadResponse;
import com.gendaz.leads.dto.lead.StatusRequest;
import com.gendaz.leads.dto.lead.UpdateLeadMessageRequest;
import com.gendaz.leads.dto.common.ApiError;
import com.gendaz.leads.entity.LeadEvent;
import com.gendaz.leads.service.LeadService;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/leads")
public class LeadController {

    private final LeadService leadService;

    public LeadController(LeadService leadService) {
        this.leadService = leadService;
    }

    @GetMapping
    public ResponseEntity<PageResponse<LeadResponse>> list(@RequestParam(required = false) Long campaignId,
                                                   @RequestParam(required = false) String status,
                                                   @RequestParam(required = false) String search,
                                                   @RequestParam(defaultValue = "0") int page,
                                                   @RequestParam(defaultValue = "20") int size) {
        Pageable pageable = PageRequest.of(Math.max(0, page), Math.max(1, Math.min(100, size)));
        return ResponseEntity.ok(PageResponse.from(leadService.list(campaignId, status, search, pageable)));
    }

    @GetMapping("/{id}")
    public ResponseEntity<LeadResponse> get(@PathVariable Long id) {
        return ResponseEntity.ok(leadService.get(id));
    }

    @GetMapping("/{id}/events")
    public ResponseEntity<PageResponse<LeadEvent>> events(@PathVariable Long id,
                                                  @RequestParam(defaultValue = "0") int page,
                                                  @RequestParam(defaultValue = "30") int size) {
        Pageable pageable = PageRequest.of(Math.max(0, page), Math.max(1, Math.min(100, size)));
        return ResponseEntity.ok(PageResponse.from(leadService.events(id, pageable)));
    }

    @PatchMapping("/{id}/message")
    public ResponseEntity<LeadResponse> editMessage(@PathVariable Long id, @Valid @RequestBody UpdateLeadMessageRequest request) {
        return ResponseEntity.ok(leadService.editMessage(id, request));
    }

    @PostMapping("/{id}/approve")
    public ResponseEntity<LeadResponse> approve(@PathVariable Long id) {
        return ResponseEntity.ok(leadService.approve(id));
    }

    @PostMapping("/{id}/do-not-contact")
    public ResponseEntity<LeadResponse> doNotContact(@PathVariable Long id) {
        return ResponseEntity.ok(leadService.doNotContact(id));
    }

    @PostMapping("/{id}/status")
    public ResponseEntity<LeadResponse> setStatus(@PathVariable Long id, @Valid @RequestBody StatusRequest request) {
        return ResponseEntity.ok(leadService.setStatus(id, request.status()));
    }

    @PostMapping("/{id}/regenerate")
    public ResponseEntity<LeadResponse> regenerate(@PathVariable Long id) {
        return ResponseEntity.ok(leadService.regenerate(id));
    }
}
