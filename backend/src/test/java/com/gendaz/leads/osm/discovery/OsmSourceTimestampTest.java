package com.gendaz.leads.osm.discovery;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class OsmSourceTimestampTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void numericEpochSeconds() {
        ObjectNode n = JSON.createObjectNode();
        n.put("@timestamp", 1573317070L);
        assertEquals(Instant.ofEpochSecond(1573317070L), OsmSourceTimestamp.parse(n.get("@timestamp")));
    }

    @Test
    void stringEpochSeconds() {
        ObjectNode n = JSON.createObjectNode();
        n.put("@timestamp", "1573317070");
        assertEquals(Instant.ofEpochSecond(1573317070L), OsmSourceTimestamp.parse(n.get("@timestamp")));
    }

    @Test
    void numericEpochMillis() {
        ObjectNode n = JSON.createObjectNode();
        n.put("@timestamp", 1573317070000L);
        assertEquals(Instant.ofEpochMilli(1573317070000L), OsmSourceTimestamp.parse(n.get("@timestamp")));
    }

    @Test
    void stringEpochMillis() {
        ObjectNode n = JSON.createObjectNode();
        n.put("@timestamp", "1573317070000");
        assertEquals(Instant.ofEpochMilli(1573317070000L), OsmSourceTimestamp.parse(n.get("@timestamp")));
    }

    @Test
    void iso8601() {
        ObjectNode n = JSON.createObjectNode();
        n.put("@timestamp", "2019-11-09T00:00:00Z");
        assertEquals(Instant.parse("2019-11-09T00:00:00Z"), OsmSourceTimestamp.parse(n.get("@timestamp")));
    }

    @Test
    void nullReturnsNull() {
        assertNull(OsmSourceTimestamp.parse(null));
        ObjectNode n = JSON.createObjectNode();
        n.putNull("@timestamp");
        assertNull(OsmSourceTimestamp.parse(n.get("@timestamp")));
    }

    @Test
    void invalidReturnsNullWithoutThrow() {
        ObjectNode n = JSON.createObjectNode();
        n.put("@timestamp", "not-a-date");
        assertNull(OsmSourceTimestamp.parse(n.get("@timestamp")));
    }

    @Test
    void bindInstantUsesTemporalType() throws Exception {
        java.sql.PreparedStatement ps = org.mockito.Mockito.mock(java.sql.PreparedStatement.class);
        Instant ts = Instant.ofEpochSecond(1573317070L);
        OsmSourceTimestamp.bindInstant(ps, 1, ts);
        org.mockito.Mockito.verify(ps).setObject(org.mockito.Mockito.eq(1),
                org.mockito.Mockito.eq(ts.atOffset(java.time.ZoneOffset.UTC)));
        OsmSourceTimestamp.bindInstant(ps, 2, null);
        org.mockito.Mockito.verify(ps).setNull(2, java.sql.Types.TIMESTAMP_WITH_TIMEZONE);
        org.mockito.Mockito.verify(ps, org.mockito.Mockito.never())
                .setString(org.mockito.Mockito.anyInt(), org.mockito.Mockito.anyString());
    }

    @Test
    void exactBugFixtureParsesToInstant(@org.junit.jupiter.api.io.TempDir Path tmp) throws Exception {
        // Fixture exata do run #19: @timestamp numerico.
        String line = "{\"type\":\"Feature\",\"properties\":{\"@type\":\"node\",\"@id\":369650222,"
                + "\"@timestamp\":1573317070,\"name\":\"Example\",\"shop\":\"beauty\"},"
                + "\"geometry\":{\"type\":\"Point\",\"coordinates\":[-56.1,-15.6]}}";
        Path input = tmp.resolve("bug.geojsonseq");
        Files.writeString(input, line + "\n", StandardCharsets.UTF_8);
        List<OsmCandidate> out = new ArrayList<>();
        try (OsmGeoJsonSeqReader reader = new OsmGeoJsonSeqReader(input, "Cuiaba", "MT", "Brasil", "br")) {
            for (OsmCandidate c : reader) out.add(c);
        }
        assertEquals(1, out.size());
        assertEquals(Instant.ofEpochSecond(1573317070L), out.get(0).sourceTimestamp());
    }

    @Test
    void stringEpochAndIsoAndNullVariants(@org.junit.jupiter.api.io.TempDir Path tmp) throws Exception {
        String l1 = "{\"type\":\"Feature\",\"properties\":{\"@type\":\"node\",\"@id\":1,\"@timestamp\":\"1573317070\",\"shop\":\"beauty\"},\"geometry\":{\"type\":\"Point\",\"coordinates\":[-56.1,-15.6]}}";
        String l2 = "{\"type\":\"Feature\",\"properties\":{\"@type\":\"node\",\"@id\":2,\"@timestamp\":1573317070000,\"shop\":\"beauty\"},\"geometry\":{\"type\":\"Point\",\"coordinates\":[-56.1,-15.6]}}";
        String l3 = "{\"type\":\"Feature\",\"properties\":{\"@type\":\"node\",\"@id\":3,\"@timestamp\":\"2019-11-09T00:00:00Z\",\"shop\":\"beauty\"},\"geometry\":{\"type\":\"Point\",\"coordinates\":[-56.1,-15.6]}}";
        String l4 = "{\"type\":\"Feature\",\"properties\":{\"@type\":\"node\",\"@id\":4,\"shop\":\"beauty\"},\"geometry\":{\"type\":\"Point\",\"coordinates\":[-56.1,-15.6]}}";
        String l5 = "{\"type\":\"Feature\",\"properties\":{\"@type\":\"node\",\"@id\":5,\"@timestamp\":\"garbage\",\"shop\":\"beauty\"},\"geometry\":{\"type\":\"Point\",\"coordinates\":[-56.1,-15.6]}}";
        Path input = tmp.resolve("variants.geojsonseq");
        Files.writeString(input, String.join("\n", l1, l2, l3, l4, l5) + "\n", StandardCharsets.UTF_8);
        List<OsmCandidate> out = new ArrayList<>();
        try (OsmGeoJsonSeqReader reader = new OsmGeoJsonSeqReader(input, "Cuiaba", "MT", "Brasil", "br")) {
            for (OsmCandidate c : reader) out.add(c);
        }
        assertEquals(5, out.size());
        assertEquals(Instant.ofEpochSecond(1573317070L), out.get(0).sourceTimestamp());
        assertEquals(Instant.ofEpochMilli(1573317070000L), out.get(1).sourceTimestamp());
        assertEquals(Instant.parse("2019-11-09T00:00:00Z"), out.get(2).sourceTimestamp());
        assertNull(out.get(3).sourceTimestamp());
        assertNull(out.get(4).sourceTimestamp());
    }
}
