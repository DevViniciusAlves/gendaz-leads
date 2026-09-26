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
    void readsRealOsmiumExportFormatWithFlatTags() throws Exception {
        // Formato real do `osmium export`: @type/@id + tags FLAT em properties.
        String point = "{\"type\":\"Feature\",\"properties\":{\"@type\":\"node\",\"@id\":1,\"@timestamp\":\"2024-01-01T00:00:00Z\",\"shop\":\"beauty\",\"name\":\"Studio Bella\"},\"geometry\":{\"type\":\"Point\",\"coordinates\":[-56.1,-15.6]}}";
        String polygon = "{\"type\":\"Feature\",\"properties\":{\"@type\":\"way\",\"@id\":2,\"shop\":\"hairdresser\",\"name\":\"Salao X\"},\"geometry\":{\"type\":\"Polygon\",\"coordinates\":[[[-56.2,-15.7],[-56.0,-15.7],[-56.0,-15.5],[-56.2,-15.5],[-56.2,-15.7]]]}}";
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
        assertEquals("beauty", out.get(0).tags().path("shop").asText());
        assertEquals("Studio Bella", out.get(0).tags().path("name").asText());
        // Polygon centroid deterministico da bbox: ((-56.2 + -56.0)/2, (-15.7 + -15.5)/2)
        assertEquals(-15.6, out.get(1).latitude(), 1e-9);
        assertEquals(-56.1, out.get(1).longitude(), 1e-9);
    }

    @Test
    void filterExportPreservesFullTagsForClassification() throws Exception {
        // Prova: tags-filter seleciona o objeto, mas o export preserva as tags
        // completas (shop, beauty, phone, website, instagram) para o Java classificar.
        String line = "{\"type\":\"Feature\",\"properties\":{\"@type\":\"node\",\"@id\":4,\"contact:phone\":\"+5565888887777\",\"name\":\"Barbearia Teste\",\"shop\":\"barber\",\"website\":\"https://x.example.com\",\"contact:instagram\":\"studio\"},\"geometry\":{\"type\":\"Point\",\"coordinates\":[-56.1,-15.6]}}";
        Path input = tmp.resolve("tags.geojsonseq");
        Files.writeString(input, line + "\n", StandardCharsets.UTF_8);
        List<OsmCandidate> out = new ArrayList<>();
        try (OsmGeoJsonSeqReader reader = new OsmGeoJsonSeqReader(input, "Cuiabá", null, null, "br")) {
            for (OsmCandidate c : reader) {
                out.add(c);
            }
        }
        assertEquals(1, out.size());
        assertEquals("+5565888887777", out.get(0).tags().path("contact:phone").asText());
        assertEquals("barber", out.get(0).tags().path("shop").asText());
        assertEquals("https://x.example.com", out.get(0).tags().path("website").asText());
        assertEquals("studio", out.get(0).tags().path("contact:instagram").asText());
    }

    @Test
    void supportsLegacyNestedTagsObject() throws Exception {
        String point = "{\"type\":\"Feature\",\"properties\":{\"type\":\"node\",\"id\":7,\"tags\":{\"shop\":\"beauty\",\"name\":\"Studio\"}},\"geometry\":{\"type\":\"Point\",\"coordinates\":[-56.1,-15.6]}}";
        Path input = tmp.resolve("nested.geojsonseq");
        Files.writeString(input, point + "\n", StandardCharsets.UTF_8);
        List<OsmCandidate> out = new ArrayList<>();
        try (OsmGeoJsonSeqReader reader = new OsmGeoJsonSeqReader(input, "Cuiabá", null, null, "br")) {
            for (OsmCandidate c : reader) {
                out.add(c);
            }
        }
        assertEquals(1, out.size());
        assertEquals(7L, out.get(0).osmId());
        assertEquals("beauty", out.get(0).tags().path("shop").asText());
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
