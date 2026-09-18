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

    @Transactional
    public void delete(Long id) {
        templateRepository.deleteById(id);
    }
}