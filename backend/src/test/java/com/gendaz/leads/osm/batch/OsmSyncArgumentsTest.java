package com.gendaz.leads.osm.batch;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class OsmSyncArgumentsTest {

    @Test
    void parseUsesCliArgsWhenProvided() {
        String[] args = {"--sync-run-id", "123", "--region-id", "456", "--target-id", "789",
                "--city", "Cuiabá", "--state", "Mato Grosso", "--country-code", "br",
                "--canonical-niche", "nails", "--target-valid", "50",
                "--niche-strategy-json", "{}", "--input", "/tmp/test.geojsonseq"};

        OsmSyncArguments parsed = OsmSyncArguments.parse(args);

        assertEquals("Cuiabá", parsed.city());
        assertEquals("Mato Grosso", parsed.state());
        assertEquals("br", parsed.countryCode());
        assertEquals("nails", parsed.canonicalNiche());
        assertEquals(50, parsed.targetValid());
        assertEquals("{}", parsed.nicheStrategyJson());
        assertEquals("/tmp/test.geojsonseq", parsed.input());
    }

    @Test
    void parseHandlesCityWithSpaces() {
        String[] args = {"--sync-run-id", "123", "--region-id", "456",
                "--city", "São Paulo", "--state", "São Paulo", "--country-code", "br",
                "--canonical-niche", "nails", "--target-valid", "50",
                "--niche-strategy-json", "{}", "--input", "/tmp/test.geojsonseq"};

        OsmSyncArguments parsed = OsmSyncArguments.parse(args);

        assertEquals("São Paulo", parsed.city());
        assertEquals("São Paulo", parsed.state());
    }

    @Test
    void parseHandlesStateWithSpaces() {
        String[] args = {"--sync-run-id", "123", "--region-id", "456",
                "--city", "Cuiabá", "--state", "Mato Grosso", "--country-code", "br",
                "--canonical-niche", "nails", "--target-valid", "50",
                "--niche-strategy-json", "{}", "--input", "/tmp/test.geojsonseq"};

        OsmSyncArguments parsed = OsmSyncArguments.parse(args);

        assertEquals("Cuiabá", parsed.city());
        assertEquals("Mato Grosso", parsed.state());
    }

    @Test
    void parseWithLegacyAliases() {
        String[] args = {"--sync-run-id", "123", "--region-id", "456", "--target-id", "789",
                "--city", "Cuiabá", "--state", "Mato Grosso", "--country-code", "br",
                "--canonical-niche", "nails", "--target-valid", "50",
                "--niche-strategy-json", "{}", "--input", "/tmp/test.geojsonseq"};

        OsmSyncArguments parsed = OsmSyncArguments.parse(args);

        assertEquals(123L, parsed.syncRunId());
        assertEquals(456L, parsed.regionId());
        assertEquals(789L, parsed.targetId());
    }

    @Test
    void parseDefaultsTargetValidTo50() {
        String[] args = {"--sync-run-id", "123", "--region-id", "456",
                "--city", "Cuiabá", "--state", "Mato Grosso", "--country-code", "br",
                "--canonical-niche", "nails", "--niche-strategy-json", "{}",
                "--input", "/tmp/test.geojsonseq"};

        OsmSyncArguments parsed = OsmSyncArguments.parse(args);

        assertEquals(50, parsed.targetValid());
    }

    @Test
    void parseThrowsWhenCityMissing() {
        String[] args = {"--sync-run-id", "123", "--region-id", "456",
                "--state", "Mato Grosso", "--country-code", "br",
                "--canonical-niche", "nails", "--target-valid", "50",
                "--niche-strategy-json", "{}", "--input", "/tmp/test.geojsonseq"};

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> OsmSyncArguments.parse(args));
        assertTrue(ex.getMessage().contains("city"));
    }

    @Test
    void parseThrowsWhenCanonicalMissing() {
        String[] args = {"--sync-run-id", "123", "--region-id", "456",
                "--city", "Cuiabá", "--state", "Mato Grosso", "--country-code", "br",
                "--target-valid", "50", "--niche-strategy-json", "{}",
                "--input", "/tmp/test.geojsonseq"};

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> OsmSyncArguments.parse(args));
        assertTrue(ex.getMessage().contains("canonical"));
    }
}