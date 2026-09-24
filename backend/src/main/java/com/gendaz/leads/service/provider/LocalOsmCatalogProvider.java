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
                0L,
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
        return discoverPage(0L, niche, city, country, target, offset);
    }

    public CatalogPage discoverPage(
            Long campaignId,
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
                        "[osm-catalog] candidate_rejected reason=niche_mismatch canonicalNiche={} sourceId={}/{} businessName={}",
                        strategy.canonicalName(),
                        place.getOsmType(),
                        place.getOsmId(),
                        place.getBusinessName()
                );
                continue;
            }
            LeadCandidate candidate = mapToCandidate(place);
            if (candidate != null) {
                // Add niche diagnostics
                JsonNode tags = parseTags(place.getTags());
                String normalizedName = candidate.getBusinessName();
                NicheMapper.NicheMatchDiagnostic diagnostic = NicheMapper.diagnoseMatch(strategy, tags, normalizedName);
                candidate.setNicheMatchType(diagnostic.matchType());
                candidate.setNicheMatchedRule(diagnostic.matchedRule());
                candidate.setNicheRelevantTags(diagnostic.relevantTags());

                log.info(
                        "[osm-catalog] niche_candidate campaignId={} canonicalNiche={} sourceId={}/{} businessName={} matchType={} matchedRule={} relevantTags={}",
                        campaignId,
                        strategy.canonicalName(),
                        place.getOsmType(),
                        place.getOsmId(),
                        place.getBusinessName(),
                        diagnostic.matchType(),
                        diagnostic.matchedRule(),
                        diagnostic.relevantTags()
                );

                candidates.add(candidate);
            }
        }

        int rawRows = basePlaces.size();
        int nextOffset = safeOffset + rawRows;
        boolean hasMore = rawRows == pageLimit;

        log.info(
                "[osm-catalog] catalog_page regionId={} city={} niche={} canonicalNiche={} offset={} rawRows={} candidates={} pageLimit={} hasMore={}",
                region.getId(),
                city,
                niche,
                strategy.canonicalName(),
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
                        + "AND qualified = true "
                        + "AND whatsapp_verified = true "
                        + "AND instagram_validated = true "
                        + "AND phone IS NOT NULL "
                        + "AND BTRIM(phone) <> '' "
                        + "AND instagram IS NOT NULL "
                        + "AND BTRIM(instagram) <> '' "
                        + "AND business_name IS NOT NULL "
                        + "AND BTRIM(business_name) <> '' "
                        + "AND ("
        );

        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("regionId", regionId);

        boolean wrotePredicate = false;

        if (
                strategy.structuredRules() != null
                        && !strategy
                        .structuredRules()
                        .isEmpty()
        ) {
            wrotePredicate =
                    appendRulesPredicate(
                            sql,
                            params,
                            strategy.structuredRules(),
                            "structured"
                    );
        }

        if (
                strategy.nameFallback() != null
                        && strategy
                        .nameFallback()
                        .enabled()
        ) {
            if (wrotePredicate) {
                sql.append(" OR ");
            }

            boolean fallbackWritten =
                    appendNameFallbackPredicate(
                            sql,
                            params,
                            strategy.nameFallback(),
                            "fallback"
                    );

            wrotePredicate =
                    wrotePredicate
                            || fallbackWritten;
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

    private boolean appendRulesPredicate(
            StringBuilder sql,
            MapSqlParameterSource params,
            List<NicheMapper.NicheRule> rules,
            String prefix
    ) {
        if (rules == null || rules.isEmpty()) {
            return false;
        }

        sql.append("(");

        for (int i = 0; i < rules.size(); i++) {
            if (i > 0) {
                sql.append(" OR ");
            }

            appendRulePredicate(
                    sql,
                    params,
                    rules.get(i),
                    prefix + "_r" + i
            );
        }

        sql.append(")");

        return true;
    }

    private void appendRulePredicate(
            StringBuilder sql,
            MapSqlParameterSource params,
            NicheMapper.NicheRule rule,
            String prefix
    ) {
        sql.append("(");

        List<NicheMapper.TagCondition> conditions =
                rule.allOf();

        for (int i = 0; i < conditions.size(); i++) {
            if (i > 0) {
                sql.append(" AND ");
            }

            appendConditionPredicate(
                    sql,
                    params,
                    conditions.get(i),
                    prefix + "_c" + i
            );
        }

        sql.append(")");
    }

    private void appendConditionPredicate(
            StringBuilder sql,
            MapSqlParameterSource params,
            NicheMapper.TagCondition condition,
            String prefix
    ) {
        String keyParam =
                prefix + "_key";

        String valuesParam =
                prefix + "_values";

        params.addValue(
                keyParam,
                condition.key()
        );

        params.addValue(
                valuesParam,
                condition.acceptedValues()
        );

        if (condition.mode() == NicheMapper.MatchMode.EXACT) {
            sql.append("LOWER(COALESCE(tags->> :")
                    .append(keyParam)
                    .append(", '')) IN (:")
                    .append(valuesParam)
                    .append(")");
        } else if (condition.mode() == NicheMapper.MatchMode.SEMICOLON_TOKEN) {
            sql.append("EXISTS (SELECT 1 FROM unnest(string_to_array(LOWER(COALESCE(tags->> :")
                    .append(keyParam)
                    .append(", '')), ';')) AS token(value) WHERE BTRIM(token.value) IN (:")
                    .append(valuesParam)
                    .append("))");
        }
    }

    private boolean appendNameFallbackPredicate(
            StringBuilder sql,
            MapSqlParameterSource params,
            NicheMapper.NameFallback fallback,
            String prefix
    ) {
        if (
                fallback == null
                        || !fallback.enabled()
        ) {
            return false;
        }

        sql.append("(");

        if (fallback.requiresContext()) {
            appendRulesPredicate(
                    sql,
                    params,
                    fallback.contextAnyOf(),
                    prefix + "_ctx"
            );

            sql.append(" AND ");
        }

        sql.append("(");

        for (int i = 0; i < fallback.aliases().size(); i++) {
            if (i > 0) {
                sql.append(" OR ");
            }

            String param =
                    prefix + "_name_" + i;

            String alias =
                    fallback.aliases().get(i);

            params.addValue(
                    param,
                    "% " + alias + " %"
            );

            sql.append(
                    "(' ' || "
                            + "REGEXP_REPLACE("
                            + "REGEXP_REPLACE("
                            + "LOWER(COALESCE(normalized_name, '')), "
                            + "'[^[:alnum:] ]+', ' ', 'g'"
                            + "), "
                            + "'[[:space:]]+', ' ', 'g'"
                            + ") "
                            + "|| ' ') LIKE :"
            )
            .append(param);
        }

        sql.append(")");

        sql.append(")");

        return true;
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
        p.setContactStatus(rs.getString("contact_status"));
        p.setContactSource(rs.getString("contact_source"));
        p.setContactSourceUrl(rs.getString("contact_source_url"));
        try { p.setQualified(rs.getBoolean("qualified")); } catch (Exception ignored) {}
        try { p.setNormalizedPhone(rs.getString("normalized_phone")); } catch (Exception ignored) {}
        try { p.setNormalizedInstagram(rs.getString("normalized_instagram")); } catch (Exception ignored) {}
        try { p.setWhatsappVerified(rs.getBoolean("whatsapp_verified")); } catch (Exception ignored) {}
        try { p.setInstagramValidated(rs.getBoolean("instagram_validated")); } catch (Exception ignored) {}
        try { p.setInstagramSource(rs.getString("instagram_source")); } catch (Exception ignored) {}
        try { p.setInstagramSourceUrl(rs.getString("instagram_source_url")); } catch (Exception ignored) {}
        try { p.setLastQualifiedNiche(rs.getString("last_qualified_niche")); } catch (Exception ignored) {}

        return p;
    }

    private boolean matchesCondition(
            JsonNode tags,
            NicheMapper.TagCondition condition
    ) {
        String actual =
                tag(
                        tags,
                        condition.key()
                );

        if (
                actual == null
                        || actual.isBlank()
        ) {
            return false;
        }

        if (condition.mode() == NicheMapper.MatchMode.EXACT) {
            return condition
                    .acceptedValues()
                    .stream()
                    .anyMatch(
                            expected ->
                                    actual.trim()
                                            .equalsIgnoreCase(
                                                    expected
                                            )
                    );
        } else if (condition.mode() == NicheMapper.MatchMode.SEMICOLON_TOKEN) {
            java.util.Set<String> tokens =
                    java.util.Arrays
                            .stream(
                                    actual.split(";")
                            )
                            .map(String::trim)
                            .filter(v ->
                                    !v.isBlank()
                            )
                            .map(v ->
                                    v.toLowerCase(
                                            Locale.ROOT
                                    )
                            )
                            .collect(
                                    java.util.stream.Collectors
                                            .toSet()
                            );

            return condition
                    .acceptedValues()
                    .stream()
                    .anyMatch(tokens::contains);
        } else {
            return false;
        }
    }

    private boolean matchesRule(
            JsonNode tags,
            NicheMapper.NicheRule rule
    ) {
        return rule
                .allOf()
                .stream()
                .allMatch(
                        condition ->
                                matchesCondition(
                                        tags,
                                        condition
                                )
                );
    }

    private boolean matchesAnyRule(
            JsonNode tags,
            List<NicheMapper.NicheRule> rules
    ) {
        return rules != null
                && rules.stream()
                .anyMatch(
                        rule ->
                                matchesRule(
                                        tags,
                                        rule
                                )
                );
    }

    private boolean containsNamePhrase(
            String normalizedName,
            String alias
    ) {
        String name =
                NicheMapper
                        .normalizeNamePhrase(
                                normalizedName
                        );

        String normalizedAlias =
                NicheMapper
                        .normalizeNamePhrase(
                                alias
                        );

        if (
                name.isBlank()
                        || normalizedAlias.isBlank()
        ) {
            return false;
        }

        return (
                " " + name + " "
        ).contains(
                " "
                        + normalizedAlias
                        + " "
        );
    }

    private boolean matchesNameFallback(
            OsmPlace place,
            JsonNode tags,
            NicheMapper.NameFallback fallback
    ) {
        if (
                fallback == null
                        || !fallback.enabled()
        ) {
            return false;
        }

        if (
                fallback.requiresContext()
                        && !matchesAnyRule(
                        tags,
                        fallback.contextAnyOf()
                )
        ) {
            return false;
        }

        String name =
                place.getNormalizedName();

        if (
                name == null
                        || name.isBlank()
        ) {
            name =
                    place.getBusinessName();
        }

        String finalName = name;

        return fallback
                .aliases()
                .stream()
                .anyMatch(
                        alias ->
                                containsNamePhrase(
                                        finalName,
                                        alias
                                )
                );
    }

    boolean matchesStrategy(
            OsmPlace place,
            NicheMapper.NicheStrategy strategy
    ) {
        JsonNode tags =
                parseTags(
                        place.getTags()
                );

        if (
                matchesAnyRule(
                        tags,
                        strategy.structuredRules()
                )
        ) {
            return true;
        }

        return matchesNameFallback(
                place,
                tags,
                strategy.nameFallback()
        );
    }

    private LeadCandidate mapToCandidate(
            OsmPlace place
    ) {
        if (
                place.getBusinessName() == null
                        || place.getBusinessName().isBlank()
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

        // Map contact metadata for diagnostics
        candidate.setContactStatus(place.getContactStatus());
        candidate.setContactSource(place.getContactSource());
        candidate.setContactSourceUrl(place.getContactSourceUrl());

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
