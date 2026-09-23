package com.gendaz.leads.service.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gendaz.leads.domain.LeadCandidate;
import com.gendaz.leads.entity.OsmCatalogRegion;
import com.gendaz.leads.entity.OsmPlace;
import com.gendaz.leads.repository.OsmCatalogRegionRepository;
import com.gendaz.leads.repository.OsmPlaceRepository;
import com.gendaz.leads.util.CountryCodeResolver;
import com.gendaz.leads.util.Normalizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

@Component
public class LocalOsmCatalogProvider {

    private static final Logger log = LoggerFactory.getLogger(LocalOsmCatalogProvider.class);

    private final OsmCatalogRegionRepository regionRepository;
    private final OsmPlaceRepository placeRepository;
    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final Normalizer normalizer;
    private final int candidateMultiplier;
    private final int maxCandidates;

    public LocalOsmCatalogProvider(
            OsmCatalogRegionRepository regionRepository,
            OsmPlaceRepository placeRepository,
            NamedParameterJdbcTemplate jdbcTemplate,
            Normalizer normalizer,
            @Value("${app.discovery.catalog.candidate-multiplier:10}") int candidateMultiplier,
            @Value("${app.discovery.catalog.max-candidates:300}") int maxCandidates
    ) {
        this.regionRepository = regionRepository;
        this.placeRepository = placeRepository;
        this.jdbcTemplate = jdbcTemplate;
        this.normalizer = normalizer;
        this.candidateMultiplier = candidateMultiplier;
        this.maxCandidates = maxCandidates;
    }

    private static final ObjectMapper JSON = new ObjectMapper();

    private JsonNode parseTags(String tagsJson) {
        if (tagsJson == null || tagsJson.isBlank()) {
            return JSON.createObjectNode();
        }

        try {
            JsonNode node = JSON.readTree(tagsJson);

            if (node != null && node.isObject()) {
                return node;
            }
        } catch (Exception e) {
            log.warn(
                    "[osm-catalog] invalid_tags_json error={}",
                    e.getMessage()
            );
        }

        return JSON.createObjectNode();
    }

    private String tag(JsonNode tags, String key) {
        if (tags == null || key == null) {
            return null;
        }

        JsonNode value = tags.get(key);

        if (value == null || value.isNull()) {
            return null;
        }

        String text = value.asText();

        return text == null || text.isBlank()
                ? null
                : text.trim();
    }

    private String normalizeForCompare(String s) {
        if (s == null) return "";
        return java.text.Normalizer.normalize(s.trim().toLowerCase(Locale.ROOT), java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .replaceAll("\\s+", " ")
                .trim();
    }

    public String getName() {
        return "osm-local-catalog";
    }

    public boolean isEnabled() {
        return true;
    }

    public List<LeadCandidate> discover(
            String niche,
            String city,
            String country,
            int target
    ) {
        return discoverPage(
                niche,
                city,
                country,
                target,
                0
        ).candidates();
    }

    public CatalogPage discoverPage(
            String niche,
            String city,
            String country,
            int target,
            int offset
    ) {
        String countryCode = CountryCodeResolver.resolveToIso2(country);

        String normalizedCity = normalizeForCompare(city);

        List<OsmCatalogRegion> readyRegions = regionRepository
                .findByNormalizedCityAndCountryCodeAndCatalogStatus(
                        normalizedCity,
                        countryCode,
                        "READY"
                );

        if (readyRegions.isEmpty()) {
            throw new IllegalArgumentException(
                    "OSM_CATALOG_NOT_READY: O catálogo OSM desta cidade ainda não foi sincronizado. Sincronize a cidade antes de gerar leads."
            );
        }

        if (readyRegions.size() > 1) {
            throw new IllegalArgumentException(
                    "OSM_CATALOG_LOCATION_AMBIGUOUS: Há mais de uma cidade sincronizada com este nome. Informe uma localização mais específica."
            );
        }

        OsmCatalogRegion region = readyRegions.get(0);

        NicheMapper.NicheStrategy strategy = NicheMapper.resolve(niche);

        int pageLimit = Math.min(
                Math.max(
                        target * candidateMultiplier,
                        30
                ),
                maxCandidates
        );

        int safeOffset = Math.max(0, offset);

        List<OsmPlace> basePlaces = findCandidatePage(
                region.getId(),
                strategy,
                pageLimit,
                safeOffset
        );

        List<LeadCandidate> candidates = new ArrayList<>();

        for (OsmPlace place : basePlaces) {
            if (!matchesStrategy(place, strategy)) {
                log.warn(
                        "[osm-catalog] candidate_rejected reason=niche_mismatch sourceId={}/{} niche={}",
                        place.getOsmType(),
                        place.getOsmId(),
                        niche
                );
                continue;
            }
            LeadCandidate candidate = mapToCandidate(place);
            if (candidate != null) {
                candidates.add(candidate);
            }
        }

        int rawRows = basePlaces.size();
        int nextOffset = safeOffset + rawRows;
        boolean hasMore = rawRows == pageLimit;

        log.info(
                "[osm-catalog] catalog_page regionId={} city={} niche={} offset={} rawRows={} candidates={} pageLimit={} hasMore={}",
                region.getId(),
                city,
                niche,
                safeOffset,
                rawRows,
                candidates.size(),
                pageLimit,
                hasMore
        );

        return new CatalogPage(
                candidates,
                rawRows,
                nextOffset,
                hasMore
        );
    }

    public record CatalogPage(
            List<LeadCandidate> candidates,
            int rawRows,
            int nextOffset,
            boolean hasMore
    ) {
    }

    private List<OsmPlace> findCandidatePage(
            Long regionId,
            NicheMapper.NicheStrategy strategy,
            int limit,
            int offset
    ) {
        StringBuilder sql = new StringBuilder();

        sql.append(
                "SELECT * FROM osm_places "
                        + "WHERE region_id = :regionId "
                        + "AND active = true "
                        + "AND phone IS NOT NULL "
                        + "AND BTRIM(phone) <> '' "
                        + "AND business_name IS NOT NULL "
                        + "AND BTRIM(business_name) <> '' "
                        + "AND ("
        );

        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("regionId", regionId);

        boolean wrotePredicate = false;

        List<String> tagFilters = strategy.tagFilters();

        if (
                tagFilters != null
                        && !tagFilters.isEmpty()
        ) {
            appendStructuredPredicate(
                    sql,
                    params,
                    tagFilters
            );

            wrotePredicate = true;
        }

        String fallbackRegex = normalizeFallbackRegex(
                strategy.fallbackNameRegex()
        );

        if (
                fallbackRegex != null
                        && !fallbackRegex.isBlank()
        ) {
            if (wrotePredicate) {
                sql.append(" OR ");
            }

            sql.append(
                    "normalized_name ~* :fallbackRegex"
            );

            params.addValue(
                    "fallbackRegex",
                    fallbackRegex
            );

            wrotePredicate = true;
        }

        if (!wrotePredicate) {
            sql.append("FALSE");
        }

        sql.append(
                ") ORDER BY normalized_name, id "
                        + "LIMIT :limit OFFSET :offset"
        );

        params
                .addValue("limit", limit)
                .addValue("offset", offset);

        return jdbcTemplate.query(
                sql.toString(),
                params,
                this::mapRow
        );
    }

    private void appendStructuredPredicate(
            StringBuilder sql,
            MapSqlParameterSource params,
            List<String> tagFilters
    ) {
        for (int i = 0; i < tagFilters.size(); i++) {
            if (i > 0) sql.append(" OR ");
            String filter = tagFilters.get(i);
            if (filter.contains(",")) {
                // AND condition
                String[] parts = filter.split(",");
                sql.append("(");
                for (int j = 0; j < parts.length; j++) {
                    if (j > 0) sql.append(" AND ");
                    String[] kv = parts[j].split("=", 2);
                    String key = kv[0];
                    String value = kv[1];
                    String paramKey = "key" + i + "_" + j;
                    String paramVal = "val" + i + "_" + j;
                    sql.append("tags->> :").append(paramKey).append(" = :").append(paramVal);
                    params.addValue(paramKey, key);
                    params.addValue(paramVal, value);
                }
                sql.append(")");
            } else {
                String[] kv = filter.split("=", 2);
                String key = kv[0];
                String value = kv[1];
                String paramKey = "key" + i;
                String paramVal = "val" + i;
                sql.append("tags->> :").append(paramKey).append(" = :").append(paramVal);
                params.addValue(paramKey, key);
                params.addValue(paramVal, value);
            }
        }
    }

    private String normalizeFallbackRegex(String regex) {
        if (regex == null || regex.isBlank()) {
            return "";
        }

        String[] parts = regex.split("\\|");
        List<String> normalized = new ArrayList<>();

        for (String part : parts) {
            String value = normalizeForCompare(part);

            if (!value.isBlank()) {
                normalized.add(value);
            }
        }

        return String.join("|", normalized);
    }

    private OsmPlace mapRow(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        OsmPlace p = new OsmPlace();

        p.setId(rs.getLong("id"));
        p.setOsmType(rs.getString("osm_type"));
        p.setOsmId(rs.getLong("osm_id"));
        p.setBusinessName(rs.getString("business_name"));
        p.setNormalizedName(rs.getString("normalized_name"));

        double latitude = rs.getDouble("latitude");
        if (!rs.wasNull()) {
            p.setLatitude(latitude);
        }

        double longitude = rs.getDouble("longitude");
        if (!rs.wasNull()) {
            p.setLongitude(longitude);
        }

        p.setAddress(rs.getString("address"));
        p.setCity(rs.getString("city"));
        p.setState(rs.getString("state"));
        p.setCountry(rs.getString("country"));
        p.setCountryCode(rs.getString("country_code"));
        p.setPhone(rs.getString("phone"));
        p.setEmail(rs.getString("email"));
        p.setWebsite(rs.getString("website"));
        p.setInstagram(rs.getString("instagram"));
        p.setTags(rs.getString("tags"));
        p.setActive(rs.getBoolean("active"));

        return p;
    }

    boolean matchesStrategy(
            OsmPlace place,
            NicheMapper.NicheStrategy strategy
    ) {
        JsonNode tags = parseTags(place.getTags());
        List<String> filters = strategy.tagFilters();
        if (filters != null && !filters.isEmpty()) {
            for (String filter : filters) {
                String[] parts = filter.split(",");
                boolean allMatch = true;
                for (String part : parts) {
                    String[] kv = part.split("=", 2);
                    if (kv.length != 2) {
                        allMatch = false;
                        break;
                    }
                    String key = kv[0].trim();
                    String expected = kv[1].trim();
                    String actual = tag(tags, key);
                    if (actual == null || !actual.equalsIgnoreCase(expected)) {
                        allMatch = false;
                        break;
                    }
                }
                if (allMatch) {
                    return true;
                }
            }
        }
        String fallback = strategy.fallbackNameRegex();
        if (fallback != null && !fallback.isBlank()) {
            String normalized = normalizeFallbackRegex(fallback);
            if (!normalized.isBlank()) {
                String name = place.getNormalizedName();
                if (name == null) name = normalizeForCompare(place.getBusinessName());
                if (name != null) {
                    try {
                        Pattern p = Pattern.compile(normalized, Pattern.CASE_INSENSITIVE);
                        if (p.matcher(name).find()) {
                            return true;
                        }
                    } catch (Exception e) {
                        // ignore invalid regex
                    }
                }
            }
        }
        // if no filters and no valid fallback, no match
        // if there were filters but none matched and fallback didn't match => false
        return false;
    }

    private LeadCandidate mapToCandidate(
            OsmPlace place
    ) {
        if (
                place.getBusinessName() == null
                        || place.getBusinessName().isBlank()
                        || place.getPhone() == null
                        || place.getPhone().isBlank()
        ) {
            return null;
        }

        LeadCandidate candidate =
                new LeadCandidate(
                        place.getBusinessName().trim(),
                        "openstreetmap",
                        place.getOsmType()
                                + "/"
                                + place.getOsmId()
                );

        candidate.setCategory(
                buildCategory(
                        place.getTags()
                )
        );

        candidate.setPhone(
                place.getPhone()
        );

        candidate.setWebsite(
                place.getWebsite()
        );

        candidate.setEmail(
                place.getEmail()
        );

        candidate.setCity(
                place.getCity()
        );

        candidate.setState(
                place.getState()
        );

        candidate.setCountry(
                place.getCountry()
        );

        candidate.setAddress(
                place.getAddress()
        );

        candidate.setInstagramStatus("NOT_FOUND");

        String instagram = place.getInstagram();
        if (instagram == null || instagram.isBlank()) {
            // try tags fallback
            JsonNode tags = parseTags(place.getTags());
            instagram = tag(tags, "contact:instagram");
            if (instagram == null) instagram = tag(tags, "instagram");
        }

        if (instagram != null && !instagram.isBlank()) {
            String normalized = normalizer.normalizeInstagram(instagram);
            if (normalized != null && !normalized.isBlank()) {
                candidate.setInstagramUsername(normalized);
                candidate.setInstagramUrl(
                        "https://instagram.com/" + normalized
                );
                candidate.setInstagramStatus("FOUND");
            }
        }

        return candidate;
    }

    private String buildCategory(String tagsJson) {
        if (tagsJson == null || tagsJson.isBlank()) return null;
        try {
            JsonNode tags = JSON.readTree(tagsJson);
            if (tags.has("beauty")) return "beauty:" + tags.get("beauty").asText();
            if (tags.has("shop")) return "shop:" + tags.get("shop").asText();
            if (tags.has("amenity")) return "amenity:" + tags.get("amenity").asText();
        } catch (Exception e) {
            // ignore
        }
        return null;
    }
}
