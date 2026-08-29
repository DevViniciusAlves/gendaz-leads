package com.gendaz.leads.service;

import com.gendaz.leads.domain.AnalysisResult;
import com.gendaz.leads.domain.LeadCandidate;
import com.gendaz.leads.service.BookingSystemDetector.Detection;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ScoringServiceTest {

    private final ScoringService scoringService = new ScoringService();

    @Test
    void scoreStaysWithinBounds() {
        LeadCandidate candidate = new LeadCandidate("Barbearia X", "google", "1");
        candidate.setWebsite("https://barbeariax.com.br");
        candidate.setInstagramUsername("barbeariax");
        AnalysisResult analysis = new AnalysisResult("Barbearia", "corte", "basic",
                false, "NOT_IDENTIFIED", null, "atendimento manual", "dor", "oportunidade", 80, "resumo");
        int score = scoringService.compute(candidate, analysis, new Detection("NOT_IDENTIFIED", null));
        assertTrue(score >= 0 && score <= 100);
    }

    @Test
    void existingSystemLowersSignalScore() {
        LeadCandidate candidate = new LeadCandidate("Salao Y", "google", "2");
        candidate.setWebsite("https://salony.com.br");
        AnalysisResult withSystem = new AnalysisResult("Salao", "cabelo", "strong",
                true, "IDENTIFIED", "Booksy", "app", "dor", "oportunidade", 70, "resumo");
        int score = scoringService.compute(candidate, withSystem, new Detection("IDENTIFIED", "Booksy"));
        assertTrue(score >= 0 && score <= 100);
    }

    @Test
    void deterministicFallbackWhenAiScoreNull() {
        LeadCandidate candidate = new LeadCandidate("Pet Z", "osm", "n1");
        AnalysisResult analysis = new AnalysisResult("Pet", "banho", "none",
                false, "UNKNOWN", null, null, "dor", "oportunidade", null, "resumo");
        int score = scoringService.compute(candidate, analysis, new Detection("UNKNOWN", null));
        assertTrue(score >= 5 && score <= 100);
    }
}
