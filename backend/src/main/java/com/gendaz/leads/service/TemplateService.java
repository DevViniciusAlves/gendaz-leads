package com.gendaz.leads.service;

import com.gendaz.leads.entity.MessageTemplate;
import com.gendaz.leads.repository.MessageTemplateRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Service
public class TemplateService {

    private final MessageTemplateRepository templateRepository;

    public TemplateService(MessageTemplateRepository templateRepository) {
        this.templateRepository = templateRepository;
    }

    @Transactional
    public MessageTemplate save(MessageTemplate template) {
        return templateRepository.save(template);
    }

    @Transactional(readOnly = true)
    public Optional<MessageTemplate> findById(Long id) {
        return templateRepository.findById(id);
    }

    @Transactional(readOnly = true)
    public Optional<MessageTemplate> findDefault() {
        return templateRepository.findFirstByIsDefaultTrue();
    }

    @Transactional(readOnly = true)
    public List<MessageTemplate> findAll() {
        return templateRepository.findAll();
    }

    @Transactional
    public void setDefault(Long id) {
        templateRepository.updateDefaultFalse();
        templateRepository.updateDefaultTrue(id);
    }

    /**
     * PUT /default: se existe default atualiza; senão cria
     * name="Abordagem padrão", isDefault=true. Garante um único default
     * (índice parcial uq_message_template_default).
     */
    @Transactional
    public com.gendaz.leads.entity.MessageTemplate upsertDefault(String name, String templateText) {
        return findDefault()
                .map(existing -> {
                    existing.setTemplateText(templateText);
                    if (name != null && !name.isBlank()) {
                        existing.setName(name.trim());
                    }
                    return templateRepository.save(existing);
                })
                .orElseGet(() -> {
                    com.gendaz.leads.entity.MessageTemplate created =
                            com.gendaz.leads.entity.MessageTemplate.builder()
                                    .name(name != null && !name.isBlank() ? name.trim() : "Abordagem padrão")
                                    .templateText(templateText)
                                    .isDefault(true)
                                    .build();
                    return templateRepository.save(created);
                });
    }

    @Transactional
    public void delete(Long id) {
        templateRepository.deleteById(id);
    }
}