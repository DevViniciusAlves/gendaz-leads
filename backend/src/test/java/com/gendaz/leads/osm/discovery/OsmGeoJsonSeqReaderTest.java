package com.gendaz.leads.osm.discovery;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class OsmGeoJsonSeqReaderTest {

    @TempDir
    Path tmp;

    @Test
    void readsPointAndPolygonWithCentroid() throws Exception {
        String point = "{\"type\":\"Feature\",\"properties\":{\"type\":\"node\",\"id\":1,\"tags\":{\"shop\":\"beauty\",\"name\":\"Studio Bella\"},\"@timestamp\":\"2024-01-01T00:00:00Z\"},\"geometry\":{\"type\":\"Point\",\"coordinates\":[-56.1,-15.6]}}";
        String polygon = "{\"type\":\"Feature\",\"properties\":{\"type\":\"way\",\"id\":2,\"tags\":{\"shop\":\"hairdresser\",\"name\":\"Salao X\"}},\"geometry\":{\"type\":\"Polygon\",\"coordinates\":[[[-56.2,-15.7],[-56.0,-15.7],[-56.0,-15.5],[-56.2,-15.5],[-56.2,-15.7]]]}}";
        Path input = tmp.resolve("in.geojsonseq");
        Files.writeString(input, point + "\n" + polygon + "\n", StandardCharsets.UTF_8);
        List<OsmCandidate> out = new ArrayList<>();
        try (OsmGeoJsonSeqReader reader = new OsmGeoJsonSeqReader(input, "Cuiabá", "Mato Grosso", "Brasil", "br")) {
            for (OsmCandidate c : reader) {
                out.add(c);
            }
        }
        assertEquals(2, out.size());
        assertEquals("node", out.get(0).osmType());
        assertEquals(1L, out.get(0).osmId());
        assertEquals(-15.6, out.get(0).latitude(), 1e-9);
        assertEquals(-56.1, out.get(0).longitude(), 1e-9);
        assertEquals("2024-01-01T00:00:00Z", out.get(0).timestamp());
        // Polygon centroid deterministico da bbox: ((-56.2 + -56.0)/2, (-15.7 + -15.5)/2)
        assertEquals(-15.6, out.get(1).latitude(), 1e-9);
        assertEquals(-56.1, out.get(1).longitude(), 1e-9);
    }

    @Test
    void skipsInvalidLines() throws Exception {
        Path input = tmp.resolve("bad.geojsonseq");
        Files.writeString(input, "not json\n\n{\"type\":\"Feature\"}\n", StandardCharsets.UTF_8);
        List<OsmCandidate> out = new ArrayList<>();
        try (OsmGeoJsonSeqReader reader = new OsmGeoJsonSeqReader(input, "Cuiabá", null, null, "br")) {
            for (OsmCandidate c : reader) {
                if (c != null) out.add(c);
            }
        }
        assertTrue(out.isEmpty());
    }
}
