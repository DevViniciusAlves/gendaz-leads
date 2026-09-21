package com.gendaz.leads.service;

import com.gendaz.leads.domain.LeadCandidate;
import com.gendaz.leads.entity.Lead;
import com.gendaz.leads.repository.LeadRepository;
import com.gendaz.leads.util.Normalizer;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DeduplicationServiceTest {

    private final LeadRepository leadRepository = mock(LeadRepository.class);
    private final Normalizer normalizer = new Normalizer();
    private final DeduplicationService service = new DeduplicationService(leadRepository, normalizer);

    @Test
    void detectsDuplicateByInstagram() {
        LeadCandidate candidate = new LeadCandidate("Barbearia Alpha", "google", "abc");
        candidate.setInstagramUsername("@barbeariaalpha");

        Lead existing = Lead.builder().id(10L).businessName("Barbearia Alpha").normalizedInstagram("barbeariaalpha").build();
        when(leadRepository.findFirstByNormalizedInstagramIgnoreCase("barbeariaalpha")).thenReturn(Optional.of(existing));

        var result = service.check(candidate);
        assertTrue(result.existing().isPresent());
        assertEquals("instagram", result.reason());
        assertEquals(10L, result.existing().get().getId());
    }

    @Test
    void detectsDuplicateBySourceId() {
        LeadCandidate candidate = new LeadCandidate("Clinica Sorriso", "openstreetmap", "node/123");
        candidate.setCity("Cuiaba");
        candidate.setCountry("Brasil");

        when(leadRepository.findFirstByNormalizedInstagramIgnoreCase(any())).thenReturn(Optional.empty());
        when(leadRepository.findFirstByNormalizedSourceIdIgnoreCase("openstreetmap_node/123"))
                .thenReturn(Optional.of(Lead.builder().id(30L).build()));

        var result = service.check(candidate);
        assertTrue(result.existing().isPresent());
        assertEquals("source_id", result.reason());
    }

    @Test
    void detectsDuplicateByNameAndLocation() {
        LeadCandidate candidate = new LeadCandidate("Clinica Sorriso", "google", "xyz");
        candidate.setCity("Cuiaba");
        candidate.setCountry("Brasil");

        when(leadRepository.findFirstByNormalizedInstagramIgnoreCase(any())).thenReturn(Optional.empty());
        when(leadRepository.findFirstByNormalizedSourceIdIgnoreCase(any())).thenReturn(Optional.empty());
        when(leadRepository.findFirstByNormalizedWebsiteIgnoreCase(any())).thenReturn(Optional.empty());
        when(leadRepository.findFirstByNormalizedPhoneIgnoreCase(any())).thenReturn(Optional.empty());
        when(leadRepository.findFirstByNormalizedEmailIgnoreCase(any())).thenReturn(Optional.empty());

        Lead existing = Lead.builder().id(20L).businessName("Clinica Sorriso").normalizedName("clinica sorriso").build();
        when(leadRepository.findFirstByNormalizedNameAndCityAndCountryIgnoreCase("clinica sorriso", "Cuiaba", "Brasil"))
                .thenReturn(Optional.of(existing));

        var result = service.check(candidate);
        assertTrue(result.existing().isPresent());
        assertEquals("name_city_country", result.reason());
    }

    @Test
    void returnsEmptyWhenNoDuplicate() {
        LeadCandidate candidate = new LeadCandidate("Loja Nova", "google", "new1");
        when(leadRepository.findFirstByNormalizedInstagramIgnoreCase(any())).thenReturn(Optional.empty());
        when(leadRepository.findFirstByNormalizedSourceIdIgnoreCase(any())).thenReturn(Optional.empty());
        when(leadRepository.findFirstByNormalizedWebsiteIgnoreCase(any())).thenReturn(Optional.empty());
        when(leadRepository.findFirstByNormalizedPhoneIgnoreCase(any())).thenReturn(Optional.empty());
        when(leadRepository.findFirstByNormalizedEmailIgnoreCase(any())).thenReturn(Optional.empty());
        when(leadRepository.findFirstByNormalizedNameAndCityAndCountryIgnoreCase(any(), any(), any())).thenReturn(Optional.empty());

        var result = service.check(candidate);
        assertTrue(result.existing().isEmpty());
        assertNull(result.reason());
    }
}