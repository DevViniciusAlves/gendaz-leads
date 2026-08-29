package com.gendaz.leads.service.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gendaz.leads.domain.LeadCandidate;
import com.gendaz.leads.exception.ApiException;
import com.gendaz.leads.service.InstagramDetector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.util.*;
import java.util.regex.Pattern;

@Component
public class OpenStreetMapProvider implements LeadDiscoveryProvider {

    private static final Logger log = LoggerFactory.getLogger(OpenStreetMapProvider.class);
    private static final String NOMINATIM = "https://nominatim.openstreetmap.org/search";
    private static final Map<String, String[]> TAG_MAP = buildTagMap();

    @Value("${app.discovery.osm.enabled:true}")
    private boolean enabled;

    @Value("${app.discovery.osm.timeout-ms:10000}")
    private int timeoutMs;

    @Value("${app.discovery.osm.overpass-url:https://overpass-api.de/api/interpreter}")
    private String overpassUrl;

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final InstagramDetector instagramDetector;

    public OpenStreetMapProvider(RestClient.Builder builder, ObjectMapper objectMapper, InstagramDetector instagramDetector) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5000);
        factory.setReadTimeout(20000);
        this.restClient = builder
                .requestFactory(factory)
                .defaultHeader("User-Agent", "GendazLeads/1.0 (contato@gendaz.com)")
                .build();
        this.objectMapper = objectMapper;
        this.instagramDetector = instagramDetector;
    }

    @Override
    public String getName() {
        return "openstreetmap";
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    @Override
    public List<LeadCandidate> discover(String niche, String location, int limit) {
        if (!isEnabled()) {
            log.info("OpenStreetMap desabilitado");
            return List.of();
        }
        try {
            Geo geo = geocode(location);
            if (geo == null) {
                log.warn("Nao foi possivel geocodificar a localizacao: {}", location);
                return List.of();
            }
            String query = buildOverpassQuery(niche, geo);
            String response = restClient.post()
                    .uri(overpassUrl)
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .body("data=" + java.net.URLEncoder.encode(query, java.nio.charset.StandardCharsets.UTF_8))
                    .retrieve()
                    .body(String.class);

            JsonNode root = objectMapper.readTree(response);
            JsonNode elements = root.path("elements");
            if (elements.isMissingNode()) return List.of();

            Set<LeadCandidate> candidates = new LinkedHashSet<>();
            for (JsonNode el : elements) {
                if (candidates.size() >= limit) break;
                LeadCandidate candidate = mapElement(el, geo);
                if (candidate != null) candidates.add(candidate);
            }
            return new ArrayList<>(candidates);
        } catch (ApiException e) {
            throw e;
        } catch (RuntimeException | java.io.IOException e) {
            log.warn("Falha ao consultar OpenStreetMap: {}", e.getMessage());
            throw new ApiException(HttpStatus.BAD_GATEWAY, "OSM_ERROR",
                    "Falha ao obter leads do OpenStreetMap.");
        }
    }

    private Geo geocode(String location) {
        try {
            String response = restClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .scheme("https").host("nominatim.openstreetmap.org").path("/search")
                            .queryParam("q", location).queryParam("format", "json")
                            .queryParam("limit", "1").queryParam("addressdetails", "1").build())
                    .retrieve()
                    .body(String.class);
            JsonNode arr = objectMapper.readTree(response);
            if (!arr.isArray() || arr.isEmpty()) return null;
            JsonNode hit = arr.get(0);
            double lat = hit.path("lat").asDouble();
            double lon = hit.path("lon").asDouble();
            JsonNode address = hit.path("address");
            String city = address.path("city").asText(null);
            if (city == null) city = address.path("town").asText(null);
            if (city == null) city = address.path("municipality").asText(null);
            String state = address.path("state").asText(null);
            String country = address.path("country").asText("BR");
            return new Geo(lat, lon, city, state, country);
        } catch (RuntimeException | java.io.IOException e) {
            log.warn("Falha de geocodificacao OSM: {}", e.getMessage());
            return null;
        }
    }

    private String buildOverpassQuery(String niche, Geo geo) {
        double d = 0.18;
        String bbox = String.format(Locale.US, "%f,%f,%f,%f",
                geo.lat - d, geo.lon - d, geo.lat + d, geo.lon + d);
        StringBuilder sb = new StringBuilder();
        sb.append("[out:json][timeout:25];(");
        String[] tags = TAG_MAP.get(normalizeNicheKey(niche));
        if (tags != null) {
            for (String t : tags) {
                String[] kv = t.split("=");
                if (kv.length == 2) {
                    sb.append(String.format("nwr[\"%s\"=\"%s\"](bbox:%s);", kv[0], kv[1], bbox));
                }
            }
        }
        String safe = Pattern.quote(niche.trim());
        sb.append(String.format("nwr[\"name\"~\"%s\",i](bbox:%s);", safe, bbox));
        sb.append(");out center tags ").append(Math.max(limitCap(), 80)).append(";");
        return sb.toString();
    }

    private int limitCap() {
        return 80;
    }

    private LeadCandidate mapElement(JsonNode el, Geo geo) {
        JsonNode tags = el.path("tags");
        if (tags.isMissingNode()) return null;
        String name = tags.path("name").asText(null);
        if (name == null || name.isBlank()) return null;

        String osmId = el.path("type").asText("") + "/" + el.path("id").asText("");
        LeadCandidate candidate = new LeadCandidate(name, "openstreetmap", osmId);
        candidate.setCategory(tags.path("shop").asText(null) != null ? "shop:" + tags.path("shop").asText() :
                (tags.path("amenity").asText(null) != null ? "amenity:" + tags.path("amenity").asText() : null));
        candidate.setWebsite(firstPresent(tags, "contact:website", "website", "url"));
        candidate.setPhone(firstPresent(tags, "contact:phone", "phone"));

        String city = tags.path("addr:city").asText(null);
        String state = tags.path("addr:state").asText(null);
        String country = tags.path("addr:country").asText("BR");
        candidate.setCity(city != null ? city : geo.city);
        candidate.setState(state != null ? state : geo.state);
        candidate.setCountry(country);

        String street = tags.path("addr:street").asText(null);
        String number = tags.path("addr:housenumber").asText(null);
        String suburb = tags.path("addr:suburb").asText(null);
        StringBuilder addr = new StringBuilder();
        if (street != null) addr.append(street);
        if (number != null) addr.append(" ").append(number);
        if (suburb != null) addr.append(", ").append(suburb);
        if (geo.city != null) addr.append(", ").append(geo.city);
        if (geo.state != null) addr.append(" - ").append(geo.state);
        candidate.setAddress(addr.toString().isBlank() ? null : addr.toString().trim());

        String instagram = instagramDetector.detectFromWebsite(candidate.getWebsite());
        if (instagram != null) {
            candidate.setInstagramUsername(instagram);
            candidate.setInstagramUrl("https://instagram.com/" + instagram);
            candidate.setInstagramStatus("FOUND");
        }
        return candidate;
    }

    private String firstPresent(JsonNode tags, String... keys) {
        for (String k : keys) {
            String v = tags.path(k).asText(null);
            if (v != null && !v.isBlank()) return v;
        }
        return null;
    }

    private String normalizeNicheKey(String niche) {
        return NormalizerAccess.stripAccents(niche.toLowerCase());
    }

    private static Map<String, String[]> buildTagMap() {
        Map<String, String[]> map = new HashMap<>();
        map.put("barbearia", new String[]{"shop=barber"});
        map.put("barbearias", new String[]{"shop=barber"});
        map.put("cabeleireiro", new String[]{"shop=hairdresser"});
        map.put("salao", new String[]{"shop=hairdresser"});
        map.put("salao de beleza", new String[]{"shop=hairdresser", "shop=beauty"});
        map.put("estetica", new String[]{"shop=beauty"});
        map.put("beleza", new String[]{"shop=beauty"});
        map.put("dentista", new String[]{"amenity=dentist"});
        map.put("clinica odontologica", new String[]{"amenity=dentist"});
        map.put("odontologia", new String[]{"amenity=dentist"});
        map.put("clinica", new String[]{"amenity=clinic"});
        map.put("clinicas", new String[]{"amenity=clinic"});
        map.put("medico", new String[]{"amenity=clinic"});
        map.put("restaurante", new String[]{"amenity=restaurant"});
        map.put("restaurantes", new String[]{"amenity=restaurant"});
        map.put("cafe", new String[]{"amenity=cafe"});
        map.put("padaria", new String[]{"shop=bakery"});
        map.put("academia", new String[]{"leisure=fitness_centre"});
        map.put("hotel", new String[]{"tourism=hotel"});
        map.put("pousada", new String[]{"tourism=hotel"});
        map.put("pet", new String[]{"shop=pet"});
        map.put("veterinario", new String[]{"amenity=veterinary"});
        map.put("farmacia", new String[]{"amenity=pharmacy"});
        map.put("loja", new String[]{"shop=clothes"});
        map.put("advogado", new String[]{"office=lawyer"});
        map.put("escritorio", new String[]{"office=company"});
        return map;
    }

    private record Geo(double lat, double lon, String city, String state, String country) {}

    static class NormalizerAccess {
        static String stripAccents(String s) {
            if (s == null) return null;
            return java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD)
                    .replaceAll("\\p{M}", "");
        }
    }
}
