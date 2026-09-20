package com.gendaz.leads.service;

import com.gendaz.leads.domain.LeadCandidate;
import com.gendaz.leads.entity.Lead;
import com.gendaz.leads.repository.LeadRepository;
import com.gendaz.leads.util.Normalizer;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Service
public class DeduplicationService {

    private final LeadRepository leadRepository;
    private final Normalizer normalizer;

    public DeduplicationService(LeadRepository leadRepository, Normalizer normalizer) {
        this.leadRepository = leadRepository;
        this.normalizer = normalizer;
    }

    public record DuplicateCheck(Optional<Lead> existing, String reason) {}

    public DuplicateCheck check(LeadCandidate candidate) {
        String instagram = normalizer.normalizeInstagram(
                candidate.getInstagramUsername() != null ? candidate.getInstagramUsername() : candidate.getInstagramUrl());
        String sourceId = normalizer.normalizeSourceId(candidate.getSource(), candidate.getSourceId());
        String website = normalizer.normalizeWebsite(candidate.getWebsite());
        String phone = normalizer.normalizePhone(candidate.getPhone());
        String email = normalizer.normalizeEmail(candidate.getEmail());
        String name = normalizer.normalizeName(candidate.getBusinessName());
        String city = candidate.getCity();
        String state = candidate.getState();
        String country = candidate.getCountry();

        if (instagram != null) {
            Optional<Lead> found = leadRepository.findFirstByNormalizedInstagramIgnoreCase(instagram);
            if (found.isPresent()) return new DuplicateCheck(found, "instagram");
        }
        if (sourceId != null) {
            Optional<Lead> found = leadRepository.findFirstByNormalizedSourceIdIgnoreCase(sourceId);
            if (found.isPresent()) return new DuplicateCheck(found, "source_id");
        }
        if (website != null) {
            Optional<Lead> found = leadRepository.findFirstByNormalizedWebsiteIgnoreCase(website);
            if (found.isPresent()) return new DuplicateCheck(found, "website");
        }
        if (phone != null) {
            Optional<Lead> found = leadRepository.findFirstByNormalizedPhoneIgnoreCase(phone);
            if (found.isPresent()) return new DuplicateCheck(found, "phone");
        }
        if (email != null) {
            Optional<Lead> found = leadRepository.findFirstByNormalizedEmailIgnoreCase(email);
            if (found.isPresent()) return new DuplicateCheck(found, "email");
        }
        if (name != null && city != null && country != null) {
            Optional<Lead> found = leadRepository.findFirstByNormalizedNameAndCityAndCountryIgnoreCase(name, city, country);
            if (found.isPresent()) return new DuplicateCheck(found, "name_city_country");
        }
        return new DuplicateCheck(Optional.empty(), null);
    }
}
