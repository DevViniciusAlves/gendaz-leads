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
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
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
    private final double entityMergeRadiusMeters;

    public LocalOsmCatalogProvider(
            OsmCatalogRegionRepository regionRepository,
            OsmPlaceRepository placeRepository,
            NamedParameterJdbcTemplate jdbcTemplate,
            Normalizer normalizer,
            @Value("${app.discovery.catalog.candidate-multiplier:10}") int candidateMultiplier,
            @Value("${app.discovery.catalog.max-candidates:300}") int maxCandidates,
            @Value("${app.discovery.catalog.entity-merge-radius-meters:50}") double entityMergeRadiusMeters
    ) {
        this.regionRepository = regionRepository;
        this.placeRepository = placeRepository;
        this.jdbcTemplate = jdbcTemplate;
        this.normalizer = normalizer;
        this.candidateMultiplier = candidateMultiplier;
        this.maxCandidates = maxCandidates;
        this.entityMergeRadiusMeters = Math.max(1.0, entityMergeRadiusMeters);
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

    private String firstPhoneLike(String... values) {
        if (values == null) {
            return null;
        }

        for (String value : values) {
            if (value == null || value.isBlank()) {
                continue;
            }

            for (String candidate : value.split(";")) {
                String raw = candidate.trim();

                if (raw.isBlank()) {
                    continue;
                }

                String digits = raw.replaceAll("\\D", "");

                if (digits.length() >= 8) {
                    return raw;
                }
            }
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

        Map<String, List<OsmPlace>> companions = findContactCompanions(
                region.getId(),
                basePlaces
        );

        List<LeadCandidate> candidates = new ArrayList<>();

        for (OsmPlace place : basePlaces) {
            LeadCandidate candidate = mapToCandidate(
                    place,
                    companions.getOrDefault(
                            place.getNormalizedName(),
                            List.of()
                    )
            );

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
                ") ORDER BY "
                        + "CASE "
                        + "WHEN NULLIF(BTRIM(phone), '') IS NOT NULL "
                        + "OR NULLIF(BTRIM(tags->>'contact:whatsapp'), '') IS NOT NULL "
                        + "OR NULLIF(BTRIM(tags->>'whatsapp'), '') IS NOT NULL "
                        + "OR NULLIF(BTRIM(tags->>'contact:phone'), '') IS NOT NULL "
                        + "OR NULLIF(BTRIM(tags->>'phone'), '') IS NOT NULL "
                        + "OR NULLIF(BTRIM(tags->>'contact:mobile'), '') IS NOT NULL "
                        + "OR NULLIF(BTRIM(tags->>'mobile'), '') IS NOT NULL "
                        + "THEN 0 "
                        + "WHEN NULLIF(BTRIM(website), '') IS NOT NULL "
                        + "OR NULLIF(BTRIM(tags->>'contact:website'), '') IS NOT NULL "
                        + "OR NULLIF(BTRIM(tags->>'website'), '') IS NOT NULL "
                        + "OR NULLIF(BTRIM(tags->>'url'), '') IS NOT NULL "
                        + "THEN 1 "
                        + "ELSE 2 END, "
                        + "normalized_name, id "
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

    private Map<String, List<OsmPlace>> findContactCompanions(
            Long regionId,
            List<OsmPlace> basePlaces
    ) {
        Map<String, List<OsmPlace>> companionsByName = new HashMap<>();

        Set<String> uniqueNames = new LinkedHashSet<>();
        for (OsmPlace place : basePlaces) {
            uniqueNames.add(place.getNormalizedName());
        }

        if (uniqueNames.isEmpty()) {
            return companionsByName;
        }

        StringBuilder sql = new StringBuilder();
        sql.append("SELECT * FROM osm_places ");
        sql.append("WHERE region_id = :regionId ");
        sql.append("AND active = true ");
        sql.append("AND normalized_name IN (:names) ");
        sql.append("AND ( ");
        sql.append("    NULLIF(BTRIM(phone), '') IS NOT NULL ");
        sql.append("    OR NULLIF(BTRIM(tags->>'contact:whatsapp'), '') IS NOT NULL ");
        sql.append("    OR NULLIF(BTRIM(tags->>'whatsapp'), '') IS NOT NULL ");
        sql.append("    OR NULLIF(BTRIM(tags->>'contact:phone'), '') IS NOT NULL ");
        sql.append("    OR NULLIF(BTRIM(tags->>'phone'), '') IS NOT NULL ");
        sql.append("    OR NULLIF(BTRIM(tags->>'contact:mobile'), '') IS NOT NULL ");
        sql.append("    OR NULLIF(BTRIM(tags->>'mobile'), '') IS NOT NULL ");
        sql.append("    OR NULLIF(BTRIM(website), '') IS NOT NULL ");
        sql.append("    OR NULLIF(BTRIM(tags->>'contact:website'), '') IS NOT NULL ");
        sql.append("    OR NULLIF(BTRIM(tags->>'website'), '') IS NOT NULL ");
        sql.append("    OR NULLIF(BTRIM(tags->>'url'), '') IS NOT NULL ");
        sql.append("    OR NULLIF(BTRIM(email), '') IS NOT NULL ");
        sql.append("    OR NULLIF(BTRIM(tags->>'contact:email'), '') IS NOT NULL ");
        sql.append("    OR NULLIF(BTRIM(tags->>'email'), '') IS NOT NULL ");
        sql.append("    OR NULLIF(BTRIM(instagram), '') IS NOT NULL ");
        sql.append("    OR NULLIF(BTRIM(tags->>'contact:instagram'), '') IS NOT NULL ");
        sql.append("    OR NULLIF(BTRIM(tags->>'instagram'), '') IS NOT NULL ");
        sql.append(") ");
        sql.append("ORDER BY normalized_name, id");

        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("regionId", regionId)
                .addValue("names", uniqueNames);

        List<OsmPlace> allCompanions = jdbcTemplate.query(sql.toString(), params, this::mapRow);

        for (OsmPlace companion : allCompanions) {
            companionsByName
                    .computeIfAbsent(companion.getNormalizedName(), k -> new ArrayList<>())
                    .add(companion);
        }

        return companionsByName;
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

    private record ContactData(
            String phone,
            String website,
            String email,
            String instagram
    ) {
    }

    private ContactData contactData(OsmPlace place) {
        JsonNode tags = parseTags(place.getTags());

        return new ContactData(
                firstPhoneLike(
                        place.getPhone(),
                        tag(tags, "contact:whatsapp"),
                        tag(tags, "whatsapp"),
                        tag(tags, "contact:phone"),
                        tag(tags, "phone"),
                        tag(tags, "contact:mobile"),
                        tag(tags, "mobile")
                ),
                firstPresent(
                        place.getWebsite(),
                        tag(tags, "contact:website"),
                        tag(tags, "website"),
                        tag(tags, "url")
                ),
                firstPresent(
                        place.getEmail(),
                        tag(tags, "contact:email"),
                        tag(tags, "email")
                ),
                firstPresent(
                        place.getInstagram(),
                        tag(tags, "contact:instagram"),
                        tag(tags, "instagram")
                )
        );
    }

    private double distanceMeters(OsmPlace a, OsmPlace b) {
        Double latA = a.getLatitude();
        Double lonA = a.getLongitude();
        Double latB = b.getLatitude();
        Double lonB = b.getLongitude();

        if (latA == null || lonA == null || latB == null || lonB == null) {
            return Double.MAX_VALUE;
        }

        double R = 6_371_000.0; // Earth radius in meters

        double latARad = Math.toRadians(latA);
        double latBRad = Math.toRadians(latB);
        double deltaLat = Math.toRadians(latB - latA);
        double deltaLon = Math.toRadians(lonB - lonA);

        double a_hav = Math.sin(deltaLat / 2) * Math.sin(deltaLat / 2)
                + Math.cos(latARad) * Math.cos(latBRad)
                * Math.sin(deltaLon / 2) * Math.sin(deltaLon / 2);

        double c = 2 * Math.atan2(Math.sqrt(a_hav), Math.sqrt(1 - a_hav));

        return R * c;
    }

    private boolean sameOsmObject(OsmPlace a, OsmPlace b) {
        return Objects.equals(a.getOsmType(), b.getOsmType())
                && Objects.equals(a.getOsmId(), b.getOsmId());
    }

    private List<OsmPlace> nearbyCompanions(OsmPlace base, List<OsmPlace> sameNameRows) {
        List<OsmPlace> result = new ArrayList<>();

        for (OsmPlace other : sameNameRows) {
            if (sameOsmObject(base, other)) {
                continue;
            }

            if (!Objects.equals(base.getNormalizedName(), other.getNormalizedName())) {
                continue;
            }

            double dist = distanceMeters(base, other);
            if (dist <= entityMergeRadiusMeters) {
                result.add(other);
            }
        }

        result.sort(Comparator
                .comparingDouble((OsmPlace o) -> distanceMeters(base, o))
                .thenComparingLong(OsmPlace::getId));

        return result;
    }

    private ContactData mergeContacts(OsmPlace base, List<OsmPlace> companions) {
        ContactData merged = contactData(base);

        for (OsmPlace companion : companions) {
            ContactData c = contactData(companion);

            if (merged.phone() == null && c.phone() != null) {
                merged = new ContactData(c.phone(), merged.website(), merged.email(), merged.instagram());
            }
            if (merged.website() == null && c.website() != null) {
                merged = new ContactData(merged.phone(), c.website(), merged.email(), merged.instagram());
            }
            if (merged.email() == null && c.email() != null) {
                merged = new ContactData(merged.phone(), merged.website(), c.email(), merged.instagram());
            }
            if (merged.instagram() == null && c.instagram() != null) {
                merged = new ContactData(merged.phone(), merged.website(), merged.email(), c.instagram());
            }
        }

        return merged;
    }

    private LeadCandidate mapToCandidate(
            OsmPlace place,
            List<OsmPlace> companions
    ) {
        if (place.getBusinessName() == null
                || place.getBusinessName().isBlank()) {
            return null;
        }

        ContactData own = contactData(place);
        List<OsmPlace> nearby = nearbyCompanions(place, companions);
        ContactData merged = mergeContacts(place, nearby);

        LeadCandidate candidate = new LeadCandidate(
                place.getBusinessName().trim(),
                "openstreetmap",
                place.getOsmType() + "/" + place.getOsmId()
        );

        candidate.setCategory(buildCategory(place.getTags()));
        candidate.setWebsite(merged.website());
        candidate.setPhone(merged.phone());
        candidate.setEmail(merged.email());
        candidate.setCity(place.getCity());
        candidate.setState(place.getState());
        candidate.setCountry(place.getCountry());
        candidate.setAddress(place.getAddress());

        candidate.setInstagramStatus("NOT_FOUND");

        if (merged.instagram() != null && !merged.instagram().isBlank()) {
            String normalized = normalizer.normalizeInstagram(merged.instagram());

            if (normalized != null && !normalized.isBlank()) {
                candidate.setInstagramUsername(normalized);
                candidate.setInstagramUrl(
                        "https://instagram.com/" + normalized
                );
                candidate.setInstagramStatus("FOUND");
            }
        }

        boolean phoneRecovered = own.phone() == null && merged.phone() != null;

        log.info(
                "[osm-catalog] candidate_contact sourceId={} companions={} phonePresent={} phoneRecoveredFromCompanion={} websitePresent={} instagramPresent={}",
                candidate.getSourceId(),
                nearby.size(),
                candidate.getPhone() != null,
                phoneRecovered,
                candidate.getWebsite() != null,
                candidate.getInstagramUsername() != null
        );

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