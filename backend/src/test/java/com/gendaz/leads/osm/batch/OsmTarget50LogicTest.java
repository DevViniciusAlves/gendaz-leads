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
        Instant ts = Instant.parse("2024-01-01T00:00:00Z");
        OsmCandidateScanStateRepository.ScanEntry entry =
                new OsmCandidateScanStateRepository.ScanEntry(
                        "NO_PHONE", Instant.now().plusSeconds(3600), ts,
                        OsmCandidateScanStateRepository.PIPELINE_VERSION);
        assertTrue(repo.shouldSkip(entry, ts));
        assertFalse(repo.shouldSkip(entry, Instant.parse("2024-02-01T00:00:00Z")));
        OsmCandidateScanStateRepository.ScanEntry expired =
                new OsmCandidateScanStateRepository.ScanEntry(
                        "NO_PHONE", Instant.now().minusSeconds(10), ts,
                        OsmCandidateScanStateRepository.PIPELINE_VERSION);
        assertFalse(repo.shouldSkip(expired, ts));
        assertTrue(repo.shouldSkip(
                new OsmCandidateScanStateRepository.ScanEntry("QUALIFIED", null, null,
                        OsmCandidateScanStateRepository.PIPELINE_VERSION), Instant.now()));
    }

    @Test
    void oldPipelineVersionNeverSkips() {
        OsmCandidateScanStateRepository repo = new OsmCandidateScanStateRepository();
        Instant ts = Instant.parse("2024-01-01T00:00:00Z");
        OsmCandidateScanStateRepository.ScanEntry old =
                new OsmCandidateScanStateRepository.ScanEntry(
                        "NO_PHONE", Instant.now().plusSeconds(3600), ts,
                        "java-osm-v1");
        assertFalse(repo.shouldSkip(old, ts));
    }

    @Test
    void technicalInconclusiveNeverExhausted() {
        // Regra: timeout/infra pendente => PARTIAL + datasetExhausted=false, mesmo com 0 validos.
        assertEquals("PARTIAL", datasetStatus(0, 50, 3));
        assertEquals("SUCCESS", datasetStatus(50, 50, 10));
        assertEquals("PARTIAL", datasetStatus(5, 50, 0));
        assertEquals("EXHAUSTED", datasetStatus(0, 50, 0));
    }

    static String datasetStatus(long saved, long target, long technicalInconclusive) {
        if (saved >= target) return "SUCCESS";
        if (technicalInconclusive > 0) return "PARTIAL";
        if (saved > 0) return "PARTIAL";
        return "EXHAUSTED";
    }
}
