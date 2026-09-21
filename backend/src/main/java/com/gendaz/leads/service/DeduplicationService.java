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

        String sourceId =
                normalizer.normalizeSourceId(
                        candidate.getSource(),
                        candidate.getSourceId()
                );

        String instagram =
                normalizer.normalizeInstagram(
                        candidate.getInstagramUsername() != null
                                ? candidate.getInstagramUsername()
                                : candidate.getInstagramUrl()
                );

        String website =
                normalizer.normalizeWebsite(candidate.getWebsite());

        String phone =
                normalizer.normalizePhone(candidate.getPhone());

        String email =
                normalizer.normalizeEmail(candidate.getEmail());

        String name =
                normalizer.normalizeName(candidate.getBusinessName());

        if (sourceId != null) {
            Optional<Lead> found =
                    leadRepository.findFirstByNormalizedSourceIdIgnoreCase(sourceId);

            if (found.isPresent()) {
                return new DuplicateCheck(found, "source_id");
            }
        }

        if (instagram != null) {
            Optional<Lead> found =
                    leadRepository.findFirstByNormalizedInstagramIgnoreCase(instagram);

            if (found.isPresent()) {
                return new DuplicateCheck(found, "instagram");
            }
        }

        if (website != null) {
            Optional<Lead> found =
                    leadRepository.findFirstByNormalizedWebsiteIgnoreCase(website);

            if (found.isPresent()) {
                return new DuplicateCheck(found, "website");
            }
        }

        if (phone != null) {
            Optional<Lead> found =
                    leadRepository.findFirstByNormalizedPhoneIgnoreCase(phone);

            if (found.isPresent()) {
                return new DuplicateCheck(found, "phone");
            }
        }

        if (name != null
                && candidate.getCity() != null
                && candidate.getCountry() != null) {

            Optional<Lead> found =
                    leadRepository
                            .findFirstByNormalizedNameAndCityAndCountryIgnoreCase(
                                    name,
                                    candidate.getCity(),
                                    candidate.getCountry()
                            );

            if (found.isPresent()) {
                return new DuplicateCheck(
                        found,
                        "name_city_country"
                );
            }
        }

        return new DuplicateCheck(
                Optional.empty(),
                null
        );
    }
}
