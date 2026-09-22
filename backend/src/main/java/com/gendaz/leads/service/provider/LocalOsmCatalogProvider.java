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
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Component
public class LocalOsmCatalogProvider implements LeadDiscoveryProvider {

    private static final Logger log = LoggerFactory.getLogger(LocalOsmCatalogProvider.class);

    @Override
    public GeoScope resolveScope(LeadDiscoveryRequest request, DiscoveryBudget budget) {
        throw new UnsupportedOperationException("LocalOsmCatalogProvider does not support resolveScope");
    }

    @Override
    public AreaQueryResult queryRegion(GeoScope scope, String niche, SearchRegion region, AreaQueryPhase phase, int rawLimit, String preferredEndpointHost, DiscoveryBudget budget, Long campaignId) {
        throw new UnsupportedOperationException("LocalOsmCatalogProvider does not support queryRegion");
    }

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

    @Override
    public String getName() {
        return "osm-local-catalog";
    }

    @Override
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
        String normalizedState = ""; // We'll get state from the region

        OsmCatalogRegion region = regionRepository
                .findByNormalizedCityAndNormalizedStateAndCountryCode(normalizedCity, normalizedState, countryCode)
                .orElseThrow(() -> new IllegalArgumentException("OSM_CATALOG_NOT_READY: O catálogo OSM desta cidade ainda não foi sincronizado. Sincronize a cidade antes de gerar leads."));

        if (!"READY".equals(region.getCatalogStatus())) {
            throw new IllegalArgumentException("OSM_CATALOG_NOT_READY: O catálogo OSM desta cidade ainda não foi sincronizado. Sincronize a cidade antes de gerar leads.");
        }

        NicheMapper.NicheStrategy strategy = NicheMapper.resolve(niche);
        int limit = Math.min(target * candidateMultiplier, maxCandidates);

        log.info("[osm-catalog] catalog_query regionId={} city={} niche={} target={} limit={} tagFilters={}",
                region.getId(), city, niche, target, limit, strategy.tagFilters());

        List<OsmPlace> places;
        if (strategy.tagFilters().isEmpty()) {
            places = placeRepository.findByRegionIdAndNormalizedNameContainingIgnoreCaseAndActiveTrue(
                    region.getId(), strategy.fallbackNameRegex().isBlank() ? "" : strategy.fallbackNameRegex());
        } else {
            places = findByStructuredTags(region.getId(), strategy.tagFilters(), limit);
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
                        sql.append("tags->>").append(":key").append(i).append("_").append(j).append(" = :val").append(i).append("_").append(j);
                        params.addValue("key" + i + "_" + j, key);
                        params.addValue("val" + i + "_" + j, value);
                    }
                    sql.append(")");
                } else {
                    String[] kv = filter.split("=", 2);
                    String key = kv[0];
                    String value = kv[1];
                    sql.append("tags->>").append(":key").append(i).append(" = :val").append(i);
                    params.addValue("key" + i, key);
                    params.addValue("val" + i, value);
                }
            }
            sql.append(")");
        }

        sql.append(" ORDER BY normalized_name LIMIT :limit");
        params.addValue("limit", limit);

        return jdbcTemplate.query(sql.toString(), params, (rs, rowNum) -> {
            OsmPlace p = new OsmPlace();
            p.setId(rs.getLong("id"));
            p.setOsmType(rs.getString("osm_type"));
            p.setOsmId(rs.getLong("osm_id"));
            p.setBusinessName(rs.getString("business_name"));
            p.setNormalizedName(rs.getString("normalized_name"));
            p.setLatitude(rs.getDouble("latitude"));
            p.setLongitude(rs.getDouble("longitude"));
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
        });
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