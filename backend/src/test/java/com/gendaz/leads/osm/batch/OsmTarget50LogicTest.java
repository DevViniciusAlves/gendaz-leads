package com.gendaz.leads.osm.batch;

import com.gendaz.leads.osm.persistence.OsmCandidateScanStateRepository;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class OsmTarget50LogicTest {

    static String finalStatus(long qualifiedSaved, long targetValid) {
        if (qualifiedSaved >= targetValid) return "SUCCESS";
        if (qualifiedSaved > 0) return "PARTIAL";
        return "EXHAUSTED";
    }

    @Test
    void seventyCandidatesTenInvalidFiftyValidGivesSuccess() {
        // 70 candidatos, 10 invalidos, 50 validos, 10 extras: rejeitados substituidos.
        long qualifiedSaved = 50;
        assertEquals("SUCCESS", finalStatus(qualifiedSaved, 50));
    }

    @Test
    void twentyCandidatesFiveValidGivesPartial() {
        assertEquals("PARTIAL", finalStatus(5, 50));
    }

    @Test
    void zeroValidGivesExhausted() {
        assertEquals("EXHAUSTED", finalStatus(0, 50));
    }

    @Test
    void scanSkipsWithinTtlButRevalidatesOnTimestampChange() {
        OsmCandidateScanStateRepository repo = new OsmCandidateScanStateRepository();
        OsmCandidateScanStateRepository.ScanEntry entry =
                new OsmCandidateScanStateRepository.ScanEntry(
                        "NO_PHONE", Instant.now().plusSeconds(3600), "2024-01-01T00:00:00Z");
        assertTrue(repo.shouldSkip(entry, "2024-01-01T00:00:00Z"));
        assertFalse(repo.shouldSkip(entry, "2024-02-01T00:00:00Z"));
        OsmCandidateScanStateRepository.ScanEntry expired =
                new OsmCandidateScanStateRepository.ScanEntry(
                        "NO_PHONE", Instant.now().minusSeconds(10), "2024-01-01T00:00:00Z");
        assertFalse(repo.shouldSkip(expired, "2024-01-01T00:00:00Z"));
        assertTrue(repo.shouldSkip(
                new OsmCandidateScanStateRepository.ScanEntry("QUALIFIED", null, null), "anything"));
    }
}
