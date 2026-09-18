package com.gendaz.leads.controller;

import com.gendaz.leads.entity.MessageTemplate;
import com.gendaz.leads.service.TemplateService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import jakarta.validation.*;
import java.util.List;

@RestController
@RequestMapping("/api/message-templates")
public class MessageTemplateController {

    private final TemplateService templateService;

    public MessageTemplateController(TemplateService templateService) {
        this.templateService = templateService;
    }

    @GetMapping("/default")
    public ResponseEntity<MessageTemplate> getDefault() {
        return templateService.findDefault()
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PutMapping("/default")
    public ResponseEntity<MessageTemplate> setDefault(@RequestBody MessageTemplate template) {
        MessageTemplate saved = templateService.save(template);
        if (saved.isDefault()) {
            templateService.setDefault(saved.getId());
        }
        return ResponseEntity.ok(saved);
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
}