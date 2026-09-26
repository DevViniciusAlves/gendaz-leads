package com.gendaz.leads.osm.discovery;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.NoSuchElementException;

/**
 * Leitor streaming de GeoJSONSeq gerado por `osmium export -f geojsonseq`.
 * Suporta Point, LineString (primeiro ponto), Polygon e MultiPolygon (centroide
 * deterministico da bbox). Preserva osm_type, osm_id, timestamp, tags e
 * coordenada representativa. Sem OCR, scraping ou fonte externa.
 */
public class OsmGeoJsonSeqReader implements Iterable<OsmCandidate>, AutoCloseable {

    private static final ObjectMapper JSON = new ObjectMapper();
    private final BufferedReader reader;
    private final String defaultCity;
    private final String defaultState;
    private final String defaultCountry;
    private final String defaultCountryCode;

    public OsmGeoJsonSeqReader(Path input, String defaultCity, String defaultState,
                               String defaultCountry, String defaultCountryCode) throws IOException {
        this.reader = Files.newBufferedReader(input, StandardCharsets.UTF_8);
        this.defaultCity = defaultCity;
        this.defaultState = defaultState;
        this.defaultCountry = defaultCountry;
        this.defaultCountryCode = defaultCountryCode;
    }

    @Override
    public Iterator<OsmCandidate> iterator() {
        return new Iterator<>() {
            private String nextLine;
            private boolean fetched;

            private void fetch() {
                if (fetched) return;
                fetched = true;
                try {
                    nextLine = reader.readLine();
                    while (nextLine != null && nextLine.isBlank()) {
                        nextLine = reader.readLine();
                    }
                } catch (IOException e) {
                    throw new IllegalStateException("GEOJSON_READ_FAILED: " + e.getMessage(), e);
                }
            }

            @Override
            public boolean hasNext() {
                fetch();
                return nextLine != null;
            }

            @Override
            public OsmCandidate next() {
                fetch();
                if (nextLine == null) throw new NoSuchElementException();
                String line = nextLine;
                fetched = false;
                nextLine = null;
                OsmCandidate c = parseFeature(line);
                if (c == null) {
                    // Linha sem candidato valido: pula recursivamente.
                    return hasNext() ? next() : null;
                }
                return c;
            }
        };
    }

    OsmCandidate parseFeature(String line) {
        JsonNode root;
        try {
            root = JSON.readTree(line);
        } catch (Exception e) {
            return null;
        }
        JsonNode props = root.path("properties");
        if (!props.isObject()) return null;

        // Formato real do `osmium export`: @type/@id numerico + tags FLAT em properties.
        // (com --attributes=type,id,timestamp o osmium tambem pode emitir type/id.)
        String osmType = textOrNull(props.get("@type"));
        if (osmType == null) osmType = textOrNull(props.get("type"));
        long osmId = longOrNeg(props.get("@id"));
        if (osmId < 0) osmId = longOrNeg(props.get("id"));
        if (osmType == null || osmId < 0) return null;

        JsonNode tags = extractTags(props);
        String timestamp = textOrNull(props.get("@timestamp"));
        if (timestamp == null) timestamp = textOrNull(props.get("timestamp"));

        String name = textOrNull(tags.get("name"));
        double[] coords = representativeCoords(root.path("geometry"));
        if (coords == null) return null;

        String normalized = normalizeName(name);
        String address = buildAddress(tags);
        return new OsmCandidate(
                osmType.toLowerCase(java.util.Locale.ROOT), osmId,
                name == null ? "" : name, normalized == null ? "" : normalized,
                tags, coords[1], coords[0],
                timestamp, address, defaultCity, defaultState, defaultCountry, defaultCountryCode);
    }

    private double[] representativeCoords(JsonNode geometry) {
        if (geometry == null || geometry.isMissingNode()) return null;
        String type = geometry.path("type").asText("");
        JsonNode coords = geometry.get("coordinates");
        if (coords == null) return null;
        try {
            return switch (type) {
                case "Point" -> new double[]{coords.get(0).asDouble(), coords.get(1).asDouble()};
                case "LineString" -> coords.size() > 0
                        ? new double[]{coords.get(0).get(0).asDouble(), coords.get(0).get(1).asDouble()} : null;
                case "Polygon" -> bboxCenter(coords);
                case "MultiPolygon" -> bboxCenter(coords);
                default -> null;
            };
        } catch (Exception e) {
            return null;
        }
    }

    /** Centro deterministico da bbox (min/max sobre todos os aneis). */
    static double[] bboxCenter(JsonNode coords) {
        double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE, maxY = -Double.MAX_VALUE;
        boolean found = false;
        java.util.ArrayDeque<JsonNode> stack = new java.util.ArrayDeque<>();
        stack.push(coords);
        while (!stack.isEmpty()) {
            JsonNode n = stack.pop();
            if (n.isArray() && n.size() == 2 && n.get(0).isNumber() && n.get(1).isNumber()) {
                double x = n.get(0).asDouble(), y = n.get(1).asDouble();
                if (x < minX) minX = x;
                if (y < minY) minY = y;
                if (x > maxX) maxX = x;
                if (y > maxY) maxY = y;
                found = true;
            } else if (n.isArray()) {
                for (JsonNode child : n) stack.push(child);
            }
        }
        if (!found) return null;
        return new double[]{(minX + maxX) / 2.0, (minY + maxY) / 2.0};
    }

    private static String textOrNull(JsonNode n) {
        if (n == null || n.isNull()) return null;
        String s = n.asText();
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static long longOrNeg(JsonNode n) {
        if (n == null || n.isNull()) return -1;
        if (n.canConvertToLong()) return n.asLong();
        // Tolerancia: "@id": "node/123".
        String s = n.asText();
        if (s == null) return -1;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d+)\\s*$").matcher(s.trim());
        if (m.find()) {
            try {
                return Long.parseLong(m.group(1));
            } catch (NumberFormatException e) {
                return -1;
            }
        }
        return -1;
    }

    private static final java.util.Set<String> META_KEYS = java.util.Set.of(
            "@type", "@id", "@timestamp", "@version", "@changeset",
            "type", "id", "timestamp", "version", "changeset");

    /** Tags flat em properties + merge de eventual objeto "tags" aninhado. */
    static com.fasterxml.jackson.databind.node.ObjectNode extractTags(JsonNode props) {
        com.fasterxml.jackson.databind.node.ObjectNode tags = JSON.createObjectNode();
        java.util.Iterator<java.util.Map.Entry<String, JsonNode>> fields = props.fields();
        while (fields.hasNext()) {
            java.util.Map.Entry<String, JsonNode> e = fields.next();
            if (META_KEYS.contains(e.getKey())) continue;
            if (e.getKey().equals("tags") && e.getValue() != null && e.getValue().isObject()) continue;
            JsonNode v = e.getValue();
            if (v != null && v.isValueNode()) {
                tags.put(e.getKey(), v.asText());
            }
        }
        JsonNode nested = props.get("tags");
        if (nested != null && nested.isObject()) {
            java.util.Iterator<java.util.Map.Entry<String, JsonNode>> nf = nested.fields();
            while (nf.hasNext()) {
                java.util.Map.Entry<String, JsonNode> e = nf.next();
                if (!tags.has(e.getKey()) && e.getValue() != null && e.getValue().isValueNode()) {
                    tags.put(e.getKey(), e.getValue().asText());
                }
            }
        }
        return tags;
    }

    static String normalizeName(String name) {
        if (name == null) return "";
        String s = java.text.Normalizer.normalize(name.trim().toLowerCase(java.util.Locale.ROOT),
                java.text.Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        s = s.replaceAll("[^a-z0-9]+", " ").replaceAll("\\s+", " ").trim();
        return s;
    }

    private static String buildAddress(JsonNode tags) {
        String[] keys = {"addr:street", "addr:housenumber", "addr:suburb", "addr:city", "addr:state", "addr:postcode"};
        StringBuilder sb = new StringBuilder();
        for (String k : keys) {
            JsonNode v = tags.get(k);
            if (v != null && !v.isNull() && !v.asText().isBlank()) {
                if (sb.length() > 0) sb.append(", ");
                sb.append(v.asText().trim());
            }
        }
        return sb.toString();
    }

    @Override
    public void close() throws IOException {
        reader.close();
    }
}
