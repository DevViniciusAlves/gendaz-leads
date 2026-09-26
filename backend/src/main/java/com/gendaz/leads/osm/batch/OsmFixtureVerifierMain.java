package com.gendaz.leads.osm.batch;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

/**
 * Verificador Java de fixtures osmium (substitui verify_osmium_fixture.py e
 * verify_boundary_fixture.py). Uso:
 * OsmFixtureVerifierMain <geojsonseq> [--require-tag shop] [--min-features N]
 * Falha (exit 1) se o export nao preservar tags completas.
 */
public class OsmFixtureVerifierMain {

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("Uso: OsmFixtureVerifierMain <geojsonseq> [--min-features N]");
            System.exit(2);
        }
        Path input = Path.of(args[0]);
        int minFeatures = 1;
        for (int i = 1; i < args.length; i++) {
            if ("--min-features".equals(args[i]) && i + 1 < args.length) {
                minFeatures = Integer.parseInt(args[++i]);
            }
        }
        ObjectMapper json = new ObjectMapper();
        long features = 0;
        Set<String> tagKeys = new HashSet<>();
        boolean hasCommercial = false;
        boolean hasContact = false;
        for (String line : Files.readAllLines(input)) {
            if (line.isBlank()) continue;
            JsonNode root = json.readTree(line);
            JsonNode props = root.path("properties");
            JsonNode tags = props.path("tags");
            if (!tags.isObject()) {
                System.err.println("[fixture] feature sem tags completas");
                System.exit(1);
            }
            features++;
            tags.fieldNames().forEachRemaining(tagKeys::add);
            if (tags.has("shop") || tags.has("amenity") || tags.has("craft")
                    || tags.has("healthcare") || tags.has("leisure") || tags.has("office")
                    || tags.has("tourism")) {
                hasCommercial = true;
            }
            if (tags.has("phone") || tags.has("contact:phone") || tags.has("website")
                    || tags.has("contact:website") || tags.has("instagram")
                    || tags.has("contact:instagram")) {
                hasContact = true;
            }
            // Geometria relevante deve existir.
            String geom = root.path("geometry").path("type").asText("");
            if (!Set.of("Point", "LineString", "Polygon", "MultiPolygon").contains(geom)) {
                System.err.println("[fixture] geometria inesperada: " + geom);
                System.exit(1);
            }
        }
        if (features < minFeatures) {
            System.err.println("[fixture] features=" + features + " abaixo do minimo " + minFeatures);
            System.exit(1);
        }
        // O filtro amplo nao pode eliminar tags importantes para classificacao.
        for (String required : new String[]{"shop", "name"}) {
            if (!tagKeys.contains(required)) {
                System.err.println("[fixture] tag importante ausente no export: " + required
                        + " (tags=" + tagKeys + ")");
                System.exit(1);
            }
        }
        if (!hasCommercial) {
            System.err.println("[fixture] nenhum objeto comercial no export");
            System.exit(1);
        }
        if (!hasContact) {
            System.err.println("[fixture] nenhum objeto com contato no export");
            System.exit(1);
        }
        System.out.println("[fixture] ok features=" + features + " tagKeys=" + tagKeys.size());
    }
}
