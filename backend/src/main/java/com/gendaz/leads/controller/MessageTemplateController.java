package com.gendaz.leads.controller;

import com.gendaz.leads.entity.MessageTemplate;
import com.gendaz.leads.service.TemplateService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import jakarta.validation.*;

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
        templateService.save(template);
        if (template.isDefault()) {
            templateService.setDefault(template.getId());
        }
        return ResponseEntity.ok(template);
    }

    @PostMapping
    public ResponseEntity<MessageTemplate> create(@RequestBody MessageTemplate template) {
        return ResponseEntity.ok(templateService.save(template));
    }
}