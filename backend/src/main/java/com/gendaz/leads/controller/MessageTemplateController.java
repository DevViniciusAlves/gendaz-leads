package com.gendaz.leads.controller;

import com.gendaz.leads.dto.template.CreateMessageTemplateRequest;
import com.gendaz.leads.dto.template.MessageTemplateResponse;
import com.gendaz.leads.dto.template.UpdateMessageTemplateRequest;
import com.gendaz.leads.service.TemplateService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/message-templates")
public class MessageTemplateController {

    private final TemplateService templateService;

    public MessageTemplateController(TemplateService templateService) {
        this.templateService = templateService;
    }

    @GetMapping("/default")
    public ResponseEntity<MessageTemplateResponse> getDefault() {
        return templateService.findDefault()
                .map(t -> ResponseEntity.ok(toResponse(t)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PutMapping("/default")
    public ResponseEntity<MessageTemplateResponse> putDefault(
            @Valid @RequestBody UpdateMessageTemplateRequest request) {
        com.gendaz.leads.entity.MessageTemplate saved = templateService.upsertDefault(request.name(), request.templateText());
        return ResponseEntity.ok(toResponse(saved));
    }

    @PostMapping
    public ResponseEntity<MessageTemplateResponse> create(@Valid @RequestBody CreateMessageTemplateRequest request) {
        com.gendaz.leads.entity.MessageTemplate template = com.gendaz.leads.entity.MessageTemplate.builder()
                .name(request.name())
                .templateText(request.templateText())
                .isDefault(false)
                .build();
        com.gendaz.leads.entity.MessageTemplate saved = templateService.save(template);
        return ResponseEntity.ok(toResponse(saved));
    }

    @GetMapping
    public ResponseEntity<List<MessageTemplateResponse>> list() {
        List<MessageTemplateResponse> response = templateService.findAll().stream()
                .map(this::toResponse)
                .toList();
        return ResponseEntity.ok(response);
    }

    @GetMapping("/{id}")
    public ResponseEntity<MessageTemplateResponse> getById(@PathVariable Long id) {
        return templateService.findById(id)
                .map(t -> ResponseEntity.ok(toResponse(t)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    private MessageTemplateResponse toResponse(com.gendaz.leads.entity.MessageTemplate t) {
        return new MessageTemplateResponse(
                t.getId(), t.getName(), t.getTemplateText(), t.isDefault(), t.getUpdatedAt());
    }
}
