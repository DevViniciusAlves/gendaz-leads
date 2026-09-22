package com.gendaz.leads.service.provider;

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
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

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
        String countryCode = CountryCodeResolver.resolveToIso2(country);
        String normalizedCity = normalizeForCompare(city);

        List<OsmCatalogRegion> readyRegions = regionRepository
                .findByNormalizedCityAndCountryCodeAndCatalogStatus(normalizedCity, countryCode, "READY");

        if (readyRegions.isEmpty()) {
            throw new IllegalArgumentException("OSM_CATALOG_NOT_READY: O catálogo OSM desta cidade ainda não foi sincronizado. Sincronize a cidade antes de gerar leads.");
        }

        if (readyRegions.size() > 1) {
            throw new IllegalArgumentException("OSM_CATALOG_LOCATION_AMBIGUOUS: Há mais de uma cidade sincronizada com este nome. Informe uma localização mais específica.");
        }

        OsmCatalogRegion region = readyRegions.get(0);

        NicheMapper.NicheStrategy strategy = NicheMapper.resolve(niche);
        int limit = Math.min(Math.max(target * candidateMultiplier, 30), maxCandidates);

        log.info("[osm-catalog] catalog_query regionId={} city={} niche={} target={} limit={} tagFilters={}",
                region.getId(), city, niche, target, limit, strategy.tagFilters());

        List<OsmPlace> places;
        if (strategy.tagFilters().isEmpty()) {
            places = findByNameFallback(region.getId(), strategy.fallbackNameRegex(), limit);
        } else {
            places = findByStructuredTags(region.getId(), strategy.tagFilters(), limit);
        }

        // If we still have room and have tag filters, add name fallback candidates
        if (!strategy.tagFilters().isEmpty()
                && places.size() < limit
                && strategy.fallbackNameRegex() != null
                && !strategy.fallbackNameRegex().isBlank()) {

            List<OsmPlace> fallbackPlaces = findByNameFallback(
                    region.getId(),
                    strategy.fallbackNameRegex(),
                    limit - places.size()
            );

            Set<String> seen = new HashSet<>();
            for (OsmPlace p : places) {
                seen.add(p.getOsmType() + "/" + p.getOsmId());
            }
            for (OsmPlace p : fallbackPlaces) {
                if (seen.add(p.getOsmType() + "/" + p.getOsmId())) {
                    places.add(p);
                }
            }
        }

        log.info("[osm-catalog] catalog_candidates regionId={} found={}", region.getId(), places.size());

        List<LeadCandidate> candidates = new ArrayList<>();
        for (OsmPlace place : places) {
            if (candidates.size() >= limit) break;
            LeadCandidate candidate = mapToCandidate(place);
            if (candidate != null) {
                candidates.add(candidate);
            }
        }

        return candidates;
    }

    private List<OsmPlace> findByStructuredTags(Long regionId, List<String> tagFilters, int limit) {
        // Build dynamic query for JSONB tag matching
        // Each filter is like "shop=barber" or "shop=hairdresser,hairdresser=barber" (AND)
        // Multiple filters are OR

        StringBuilder sql = new StringBuilder();
        sql.append("SELECT * FROM osm_places WHERE region_id = :regionId AND active = true");

        MapSqlParameterSource params = new MapSqlParameterSource("regionId", regionId);

        if (!tagFilters.isEmpty()) {
            sql.append(" AND (");
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
            sql.append(")");
        }

        sql.append(" ORDER BY normalized_name LIMIT :limit");
        params.addValue("limit", limit);

        return jdbcTemplate.query(sql.toString(), params, this::mapRow);
    }

    private List<OsmPlace> findByNameFallback(Long regionId, String fallbackRegex, int limit) {
        String sql = """
                SELECT *
                FROM osm_places
                WHERE region_id = :regionId
                  AND active = true
                  AND normalized_name ~* :regex
                ORDER BY normalized_name, id
                LIMIT :limit
                """;

        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("regionId", regionId)
                .addValue("regex", normalizeFallbackRegex(fallbackRegex))
                .addValue("limit", limit);

        return jdbcTemplate.query(sql, params, this::mapRow);
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
                normalized.add(java.util.regex.Pattern.quote(value));
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

    private LeadCandidate mapToCandidate(OsmPlace place) {
        if (place.getBusinessName() == null || place.getBusinessName().isBlank()) {
            return null;
        }
        LeadCandidate candidate = new LeadCandidate(
                place.getBusinessName().trim(),
                "openstreetmap",
                place.getOsmType() + "/" + place.getOsmId()
        );
        candidate.setCategory(buildCategory(place.getTags()));
        candidate.setWebsite(firstPresent(place.getWebsite()));
        candidate.setPhone(firstPresent(place.getPhone()));
        candidate.setEmail(firstPresent(place.getEmail()));
        candidate.setCity(place.getCity());
        candidate.setState(place.getState());
        candidate.setCountry(place.getCountry());
        candidate.setAddress(place.getAddress());
        candidate.setInstagramStatus("NOT_FOUND");
        if (place.getInstagram() != null && !place.getInstagram().isBlank()) {
            String normalized = normalizer.normalizeInstagram(place.getInstagram());
            if (normalized != null && !normalized.isBlank()) {
                candidate.setInstagramUsername(normalized);
                candidate.setInstagramUrl("https://instagram.com/" + normalized);
                candidate.setInstagramStatus("FOUND");
            }
        }
        return candidate;
    }

    private String buildCategory(String tagsJson) {
        if (tagsJson == null || tagsJson.isBlank()) return null;
        try {
            com.fasterxml.jackson.databind.JsonNode tags = new com.fasterxml.jackson.databind.ObjectMapper().readTree(tagsJson);
            if (tags.has("beauty")) return "beauty:" + tags.get("beauty").asText();
            if (tags.has("shop")) return "shop:" + tags.get("shop").asText();
            if (tags.has("amenity")) return "amenity:" + tags.get("amenity").asText();
        } catch (Exception e) {
            // ignore
        }
        return null;
    }

    private String firstPresent(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) return v.trim();
        }
        return null;
    }

    private String normalizeForCompare(String s) {
        if (s == null) return "";
        return java.text.Normalizer.normalize(s.trim().toLowerCase(Locale.ROOT), java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .replaceAll("\\s+", " ")
                .trim();
    }
}