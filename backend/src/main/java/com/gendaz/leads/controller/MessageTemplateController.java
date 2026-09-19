package com.gendaz.leads.controller;

import com.gendaz.leads.dto.template.MessageTemplateResponse;
import com.gendaz.leads.dto.template.UpdateMessageTemplateRequest;
import com.gendaz.leads.entity.MessageTemplate;
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
        MessageTemplate saved = templateService.upsertDefault(request.name(), request.templateText());
        return ResponseEntity.ok(toResponse(saved));
    }

    @PostMapping
    public ResponseEntity<MessageTemplate> create(@Valid @RequestBody MessageTemplate template) {
        return ResponseEntity.ok(templateService.save(template));
    }

    @GetMapping
    public ResponseEntity<List<MessageTemplate>> list() {
        return ResponseEntity.ok(templateService.findAll());
    }

    @GetMapping("/{id}")
    public ResponseEntity<MessageTemplate> getById(@PathVariable Long id) {
        return templateService.findById(id)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    private MessageTemplateResponse toResponse(MessageTemplate t) {
        return new MessageTemplateResponse(
                t.getId(), t.getName(), t.getTemplateText(), t.isDefault(), t.getUpdatedAt());
    }
}
