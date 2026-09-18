package com.gendaz.leads.service.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gendaz.leads.domain.LeadCandidate;
import com.gendaz.leads.exception.ApiException;
import com.gendaz.leads.service.InstagramDetector;
import com.gendaz.leads.util.Normalizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.util.*;

@Component
public class OpenStreetMapProvider implements LeadDiscoveryProvider {

    private static final Logger log = LoggerFactory.getLogger(OpenStreetMapProvider.class);

    /** Meio-tamanho da bbox (graus) ao redor do ponto geocodificado. ~0.18 ~= 20 km. */
    private static final double BBOX_DELTA = 0.18;
    private static final int MIN_OUT = 80;
    private static final int MAX_OUT = 200;

    @Value("${app.discovery.osm.enabled:true}")
    private boolean enabled;

    @Value("${app.discovery.osm.timeout-ms:10000}")
    private int timeoutMs;

    @Value("${app.discovery.osm.overpass-url:https://overpass-api.de/api/interpreter}")
    private String overpassUrl;

    private final RestClient.Builder builder;
    private final ObjectMapper objectMapper;
    private final InstagramDetector instagramDetector;
    private final Normalizer normalizer;

    public OpenStreetMapProvider(RestClient.Builder builder, ObjectMapper objectMapper,
                                 InstagramDetector instagramDetector, Normalizer normalizer) {
        this.builder = builder;
        this.objectMapper = objectMapper;
        this.instagramDetector = instagramDetector;
        this.normalizer = normalizer;
    }

    private RestClient client() {
        int effective = timeoutMs > 0 ? timeoutMs : 10000;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Math.min(effective, 15000));
        factory.setReadTimeout(effective);
        return builder
                .requestFactory(factory)
                .defaultHeader("User-Agent", "GendazLeads/1.0 (contato@gendaz.com)")
                .build();
    }

    private RestClient restClient() {
        return client();
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
            String query = buildOverpassQuery(niche, geo, limit);
            String response = restClient().post()
                    .uri(overpassUrl)
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .body("data=" + java.net.URLEncoder.encode(query, java.nio.charset.StandardCharsets.UTF_8))
                    .retrieve()
                    .body(String.class);

            JsonNode root = objectMapper.readTree(response);
            JsonNode elements = root.path("elements");
            if (elements.isMissingNode() || !elements.isArray()) return List.of();

            // Deduplicacao dentro do lote por chave explicita (source + sourceId),
            // sem depender de equals/hashCode de LeadCandidate.
            Map<String, LeadCandidate> byKey = new LinkedHashMap<>();
            for (JsonNode el : elements) {
                if (byKey.size() >= Math.max(limit, 1)) break;
                LeadCandidate candidate = mapElement(el, geo);
                if (candidate == null) continue;
                String key = "openstreetmap:" + String.valueOf(candidate.getSourceId()).toLowerCase(Locale.ROOT);
                byKey.putIfAbsent(key, candidate);
            }
            return new ArrayList<>(byKey.values());
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
            String response = restClient().get()
                    .uri(uriBuilder -> uriBuilder
                            .scheme("https").host("nominatim.openstreetmap.org").path("/search")
                            .queryParam("q", location).queryParam("format", "json")
                            .queryParam("limit", "1").queryParam("addressdetails", "1").build())
                    .retrieve()
                    .body(String.class);
            JsonNode arr = objectMapper.readTree(response);
            if (!arr.isArray() || arr.isEmpty()) return null;
            JsonNode hit = arr.get(0);
            double lat = hit.path("lat").asDouble(Double.NaN);
            double lon = hit.path("lon").asDouble(Double.NaN);
            if (Double.isNaN(lat) || Double.isNaN(lon)) return null;
            JsonNode address = hit.path("address");
            String city = firstPresent(address,
                    "city", "town", "village", "municipality", "suburb", "county");
            String state = address.path("state").asText(null);
            String country = address.path("country").asText(null);
            if (country == null || country.isBlank()) country = "BR";
            return new Geo(lat, lon, blankToNull(city), blankToNull(state), country);
        } catch (RuntimeException | java.io.IOException e) {
            log.warn("Falha de geocodificacao OSM: {}", e.getMessage());
            return null;
        }
    }

    String buildOverpassQuery(String niche, Geo geo, int limit) {
        String bbox = String.format(Locale.US, "%f,%f,%f,%f",
                geo.lat - BBOX_DELTA, geo.lon - BBOX_DELTA, geo.lat + BBOX_DELTA, geo.lon + BBOX_DELTA);
        NicheMapper.NicheStrategy strategy = NicheMapper.resolve(niche);
        StringBuilder sb = new StringBuilder();
        sb.append("[out:json][timeout:25];(");
        for (String t : strategy.tagFilters()) {
            String[] kv = t.split("=", 2);
            if (kv.length == 2 && !kv[0].isBlank() && !kv[1].isBlank()) {
                sb.append(String.format("nwr[\"%s\"=\"%s\"](%s);",
                        escapeTag(kv[0].trim()), escapeTag(kv[1].trim()), bbox));
            }
        }
        // Fallback pelo nome (mantido, mas nao e a estrategia principal).
        // Pattern.quote ja aplicado em NicheMapper.sanitizeForRegex.
        if (strategy.fallbackNameRegex() != null && !strategy.fallbackNameRegex().isBlank()
                && !strategy.fallbackNameRegex().equals("''")) {
            sb.append(String.format("nwr[\"name\"~\"%s\",i](%s);",
                    strategy.fallbackNameRegex(), bbox));
        }
        sb.append(");out center tags ").append(outLimit(limit)).append(";");
        return sb.toString();
    }

    static int outLimit(int limit) {
        int want = Math.max(limit, MIN_OUT);
        return Math.min(want, MAX_OUT);
    }

    LeadCandidate mapElement(JsonNode el, Geo geo) {
        JsonNode tags = el.path("tags");
        if (tags.isMissingNode() || !tags.isObject()) return null;
        String name = asTextOrNull(tags, "name");
        if (name == null || name.isBlank()) return null;

        String type = el.path("type").asText("");
        String id = el.path("id").asText("");
        if (type.isBlank() || id.isBlank()) return null;
        String osmId = type + "/" + id;

        LeadCandidate candidate = new LeadCandidate(name.trim(), "openstreetmap", osmId);
        candidate.setCategory(buildCategory(tags));
        candidate.setWebsite(firstPresent(tags, "contact:website", "website", "url"));
        candidate.setPhone(firstPresent(tags, "contact:phone", "phone", "contact:mobile", "mobile"));

        String city = firstPresent(tags, "addr:city", "addr:town", "addr:village", "addr:municipality");
        String state = asTextOrNull(tags, "addr:state");
        String country = asTextOrNull(tags, "addr:country");
        candidate.setCity(city != null ? city : geo.city);
        candidate.setState(state != null ? state : geo.state);
        candidate.setCountry(country != null ? country : (geo.country != null ? geo.country : "BR"));
        candidate.setAddress(buildAddress(tags, geo));

        // Instagram: primeiro direto do OSM; somente se ausente, fallback via website.
        String rawIg = firstPresent(tags, "contact:instagram", "instagram");
        String username = normalizer.normalizeInstagram(rawIg);
        if (username != null && !username.isBlank()) {
            candidate.setInstagramUsername(username);
            candidate.setInstagramUrl("https://instagram.com/" + username);
            candidate.setInstagramStatus("FOUND");
        } else if (candidate.getWebsite() != null && !candidate.getWebsite().isBlank()
                && instagramDetector != null) {
            String viaSite = instagramDetector.detectFromWebsite(candidate.getWebsite());
            if (viaSite != null && !viaSite.isBlank()) {
                candidate.setInstagramUsername(viaSite);
                candidate.setInstagramUrl("https://instagram.com/" + viaSite);
                candidate.setInstagramStatus("FOUND");
            } else {
                candidate.setInstagramStatus("NOT_FOUND");
            }
        } else {
            candidate.setInstagramStatus("NOT_FOUND");
        }
        return candidate;
    }

    static String buildCategory(JsonNode tags) {
        String beauty = asTextOrNull(tags, "beauty");
        String shop = asTextOrNull(tags, "shop");
        String amenity = asTextOrNull(tags, "amenity");
        String leisure = asTextOrNull(tags, "leisure");
        String office = asTextOrNull(tags, "office");
        String tourism = asTextOrNull(tags, "tourism");
        if (beauty != null && !beauty.isBlank()) {
            return "beauty:" + beauty.trim();
        }
        if (shop != null && !shop.isBlank()) return "shop:" + shop.trim();
        if (amenity != null && !amenity.isBlank()) return "amenity:" + amenity.trim();
        if (leisure != null && !leisure.isBlank()) return "leisure:" + leisure.trim();
        if (office != null && !office.isBlank()) return "office:" + office.trim();
        if (tourism != null && !tourism.isBlank()) return "tourism:" + tourism.trim();
        return null;
    }

    static String buildAddress(JsonNode tags, Geo geo) {
        String street = asTextOrNull(tags, "addr:street");
        String number = asTextOrNull(tags, "addr:housenumber");
        String suburb = asTextOrNull(tags, "addr:suburb", "addr:neighbourhood", "addr:district");
        String city = asTextOrNull(tags, "addr:city", "addr:town", "addr:village", "addr:municipality");
        String state = asTextOrNull(tags, "addr:state");
        if (city == null) city = geo.city;
        if (state == null) state = geo.state;

        List<String> head = new ArrayList<>();
        StringBuilder streetPart = new StringBuilder();
        if (street != null) streetPart.append(street);
        if (number != null) {
            if (!streetPart.isEmpty()) streetPart.append(" ");
            streetPart.append(number);
        }
        if (!streetPart.isEmpty()) head.add(streetPart.toString());
        if (suburb != null) head.add(suburb);

        List<String> tail = new ArrayList<>();
        if (city != null) tail.add(city);
        if (state != null) tail.add(state);

        String left = String.join(", ", head);
        String right = String.join(" - ", tail);
        if (!left.isEmpty() && !right.isEmpty()) return left + ", " + right;
        if (!left.isEmpty()) return left;
        if (!right.isEmpty()) return right;
        return null;
    }

    private static String firstPresent(JsonNode tags, String... keys) {
        for (String k : keys) {
            String v = asTextOrNull(tags, k);
            if (v != null && !v.isBlank()) return v.trim();
        }
        return null;
    }

    private static String asTextOrNull(JsonNode tags, String... keys) {
        for (String k : keys) {
            JsonNode n = tags.path(k);
            if (!n.isMissingNode() && !n.isNull()) {
                String v = n.asText(null);
                if (v != null && !v.isBlank()) return v.trim();
            }
        }
        return null;
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }

    private static String escapeTag(String s) {
        return s.replace("\\", "").replace("\"", "");
    }

    record Geo(double lat, double lon, String city, String state, String country) {
    }
}
