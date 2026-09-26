package com.gendaz.leads.osm.batch;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Verificador Java de fixtures osmium (substitui verify_osmium_fixture.py e
 * verify_boundary_fixture.py, removidos do runtime Python).
 *
 * Modo 1 (osmium): OsmFixtureVerifierMain &lt;geojsonseq&gt;
 *   - properties com @type/@id (tags FLAT, como o osmium export emite);
 *   - tipos node+way+relation presentes;
 *   - companion contact-only (node 4, contact:phone +5565888887777) preservado
 *     pelo tags-filter/export (prova que o filtro amplo nao derruba contato).
 *
 * Modo 2 (boundary, offline, sem Nominatim):
 *   OsmFixtureVerifierMain --boundary &lt;boundary.osm&gt; &lt;city.geojsonseq&gt;
 *   - boundary contem relation 100 + member way 200/outer + refs completas;
 *   - city contem nos internos 110/111 e exclui o externo 112.
 */
public class OsmFixtureVerifierMain {

    private static final ObjectMapper JSON = new ObjectMapper();

    public static void main(String[] args) throws Exception {
        if (args.length == 3 && "--boundary".equals(args[0])) {
            verifyBoundary(Path.of(args[1]), Path.of(args[2]));
            return;
        }
        if (args.length < 1 || args.length > 2) {
            System.err.println("Uso: OsmFixtureVerifierMain <geojsonseq> | --boundary <boundary.osm> <city.geojsonseq>");
            System.exit(2);
        }
        verifyOsmium(Path.of(args[0]));
    }

    static void verifyOsmium(Path input) throws Exception {
        Set<String> types = new HashSet<>();
        long count = 0;
        boolean contactCompanionFound = false;
        for (String raw : Files.readAllLines(input)) {
            String line = raw.replace("\u001E", "").strip();
            if (line.isEmpty()) continue;
            JsonNode root = JSON.readTree(line);
            JsonNode props = root.path("properties");
            String osmType = props.path("@type").asText(null);
            JsonNode idNode = props.path("@id");
            if (osmType == null || (!"node".equals(osmType) && !"way".equals(osmType) && !"relation".equals(osmType))) {
                continue;
            }
            if (idNode.isMissingNode() || idNode.isNull()) {
                fail("Feature " + osmType + " sem @id");
            }
            long osmId = idNode.asLong(-1);
            if ("node".equals(osmType) && osmId == 4
                    && "+5565888887777".equals(props.path("contact:phone").asText(null))) {
                contactCompanionFound = true;
            }
            types.add(osmType);
            count++;
        }
        if (count == 0) {
            fail("Nenhuma feature foi exportada pelo osmium");
        }
        for (String required : new String[]{"node", "way", "relation"}) {
            if (!types.contains(required)) {
                fail("Tipos OSM ausentes no pipeline da fixture: " + required);
            }
        }
        if (!contactCompanionFound) {
            fail("Contact-only OSM companion was dropped by tags-filter/export pipeline");
        }
        System.out.println("OSMIUM_FIXTURE_PASS count=" + count + " types=" + String.join(",", sorted(types)));
    }

    static void verifyBoundary(Path boundaryOsm, Path cityGeojsonseq) throws Exception {
        javax.xml.parsers.DocumentBuilderFactory dbf = javax.xml.parsers.DocumentBuilderFactory.newInstance();
        org.w3c.dom.Document doc = dbf.newDocumentBuilder().parse(boundaryOsm.toFile());
        Set<String> nodes = new HashSet<>();
        java.util.Map<String, List<String>> ways = new java.util.HashMap<>();
        java.util.Map<String, List<String[]>> relations = new java.util.HashMap<>();
        org.w3c.dom.NodeList children = doc.getDocumentElement().getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            org.w3c.dom.Node n = children.item(i);
            if (!(n instanceof org.w3c.dom.Element)) continue;
            org.w3c.dom.Element e = (org.w3c.dom.Element) n;
            switch (e.getTagName()) {
                case "node" -> nodes.add(e.getAttribute("id"));
                case "way" -> {
                    List<String> refs = new ArrayList<>();
                    org.w3c.dom.NodeList nds = e.getElementsByTagName("nd");
                    for (int j = 0; j < nds.getLength(); j++) {
                        refs.add(((org.w3c.dom.Element) nds.item(j)).getAttribute("ref"));
                    }
                    ways.put(e.getAttribute("id"), refs);
                }
                case "relation" -> {
                    List<String[]> members = new ArrayList<>();
                    org.w3c.dom.NodeList ms = e.getElementsByTagName("member");
                    for (int j = 0; j < ms.getLength(); j++) {
                        org.w3c.dom.Element member = (org.w3c.dom.Element) ms.item(j);
                        members.add(new String[]{member.getAttribute("type"), member.getAttribute("ref"), member.getAttribute("role")});
                    }
                    relations.put(e.getAttribute("id"), members);
                }
                default -> { }
            }
        }
        if (!relations.containsKey("100")) {
            fail("boundary.osm.pbf does not contain relation r100");
        }
        boolean hasOuter = relations.get("100").stream()
                .anyMatch(m -> "way".equals(m[0]) && "200".equals(m[1]) && "outer".equals(m[2]));
        if (!hasOuter) {
            fail("relation r100 is missing member way 200/outer");
        }
        if (!ways.containsKey("200")) {
            fail("boundary.osm.pbf is missing referenced way 200 (osmium getid -r must include references)");
        }
        List<String> missingNodes = ways.get("200").stream().filter(r -> !nodes.contains(r)).distinct().sorted().toList();
        if (!missingNodes.isEmpty()) {
            fail("boundary.osm.pbf is missing referenced nodes " + String.join(",", missingNodes));
        }
        Set<String> cityKeys = new HashSet<>();
        for (String raw : Files.readAllLines(cityGeojsonseq)) {
            String line = raw.replace("\u001E", "").strip();
            if (line.isEmpty()) continue;
            JsonNode root = JSON.readTree(line);
            JsonNode props = root.path("properties");
            cityKeys.add(props.path("@type").asText("") + "/" + props.path("@id").asText(""));
        }
        if (!cityKeys.contains("node/110")) {
            fail("city extract is missing inner node 110");
        }
        if (!cityKeys.contains("node/111")) {
            fail("city extract is missing inner node 111");
        }
        if (cityKeys.contains("node/112")) {
            fail("city extract leaked outer node 112 (boundary polygon was not applied)");
        }
        System.out.println("BOUNDARY_FIXTURE_PASS relation=r100 inner=n110,n111 outer_excluded=n112");
    }

    private static List<String> sorted(Set<String> set) {
        List<String> list = new ArrayList<>(set);
        java.util.Collections.sort(list);
        return list;
    }

    private static void fail(String message) {
        System.err.println("[fixture] " + message);
        System.exit(1);
    }
}
