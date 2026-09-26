package com.gendaz.leads.service;

import com.gendaz.leads.domain.LeadCandidate;
import com.gendaz.leads.entity.OsmCatalogRegion;
import com.gendaz.leads.entity.OsmCatalogTarget;
import com.gendaz.leads.repository.OsmCatalogRegionRepository;
import com.gendaz.leads.repository.OsmCatalogTargetRepository;
import com.gendaz.leads.service.provider.NicheMapper;
import com.gendaz.leads.util.CountryCodeResolver;
import com.gendaz.leads.util.Normalizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Consumidor simples do pool qualificado (campanha NAO descobre OSM,
 * NAO acessa Nominatim/Overpass, NAO faz enrichment, NAO reexecuta
 * NicheMapper em lead ja qualificado). Membership via osm_place_niches.
 */
@Service
public class QualifiedLeadPoolService {

    private static final Logger log = LoggerFactory.getLogger(QualifiedLeadPoolService.class);

    private final OsmCatalogRegionRepository regionRepository;
    private final OsmCatalogTargetRepository targetRepository;
    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final Normalizer normalizer;
    private final int candidateMultiplier;
    private final int maxCandidates;

    public QualifiedLeadPoolService(
            OsmCatalogRegionRepository regionRepository,
            OsmCatalogTargetRepository targetRepository,
            NamedParameterJdbcTemplate jdbcTemplate,
            Normalizer normalizer,
            @Value("${app.discovery.catalog.candidate-multiplier:10}") int candidateMultiplier,
            @Value("${app.discovery.catalog.max-candidates:300}") int maxCandidates) {
        this.regionRepository = regionRepository;
        this.targetRepository = targetRepository;
        this.jdbcTemplate = jdbcTemplate;
        this.normalizer = normalizer;
        this.candidateMultiplier = candidateMultiplier;
        this.maxCandidates = maxCandidates;
    }

    public record PoolPage(List<LeadCandidate> candidates, int rawRows, int nextOffset, boolean hasMore,
                           long targetId, String canonicalNiche) {}

    public record ResolvedTarget(OsmCatalogRegion region, OsmCatalogTarget target, String canonicalNiche) {}

    public ResolvedTarget resolveTarget(String niche, String city, String country) {
        String countryCode = CountryCodeResolver.resolveToIso2(country);
        String normalizedCity = normalizeForCompare(city);
        List<OsmCatalogRegion> regions = regionRepository
                .findByNormalizedCityAndCountryCode(normalizedCity, countryCode);
        if (regions.isEmpty()) {
            throw new IllegalArgumentException(
                    "OSM_CATALOG_NOT_READY: O catálogo OSM desta cidade ainda não foi sincronizado. Sincronize a cidade antes de gerar leads.");
        }
        if (regions.size() > 1) {
            throw new IllegalArgumentException(
                    "OSM_CATALOG_LOCATION_AMBIGUOUS: Há mais de uma cidade sincronizada com este nome. Informe uma localização mais específica.");
        }
        OsmCatalogRegion region = regions.get(0);
        String canonical = NicheMapper.resolve(niche).canonicalName();
        OsmCatalogTarget target = targetRepository.findByRegionIdAndCanonicalNiche(region.getId(), canonical)
                .orElseThrow(() -> new IllegalArgumentException(
                        "OSM_TARGET_NOT_FOUND: Ainda não existe sincronização para [" + niche + "] em [" + city + "]. Sincronize esse nicho antes de gerar a campanha."));
        return new ResolvedTarget(region, target, canonical);
    }

    public PoolPage discoverPage(long targetId, int target, int offset) {
        int pageLimit = Math.min(Math.max(target * candidateMultiplier, 30), maxCandidates);
        int safeOffset = Math.max(0, offset);
        String sql = """
                SELECT p.* FROM osm_places p
                JOIN osm_place_niches n ON n.place_id = p.id
                WHERE n.target_id = :targetId AND n.active = TRUE
                  AND p.active = TRUE AND p.qualified = TRUE
                  AND p.whatsapp_verified = TRUE AND p.instagram_validated = TRUE
                  AND p.normalized_phone IS NOT NULL AND BTRIM(p.normalized_phone) <> ''
                  AND p.normalized_instagram IS NOT NULL AND BTRIM(p.normalized_instagram) <> ''
                  AND p.business_name IS NOT NULL AND BTRIM(p.business_name) <> ''
                ORDER BY p.normalized_name, p.id
                LIMIT :limit OFFSET :offset
                """;
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("targetId", targetId)
                .addValue("limit", pageLimit)
                .addValue("offset", safeOffset);
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql, params);
        List<LeadCandidate> candidates = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            LeadCandidate c = mapRow(row);
            if (c != null) candidates.add(c);
        }
        int rawRows = rows.size();
        return new PoolPage(candidates, rawRows, safeOffset + rawRows, rawRows == pageLimit, targetId, null);
    }

    private LeadCandidate mapRow(Map<String, Object> row) {
        Object businessName = row.get("business_name");
        if (businessName == null || businessName.toString().isBlank()) return null;
        Object osmType = row.get("osm_type");
        Object osmId = row.get("osm_id");
        if (osmType == null || osmId == null) return null;
        LeadCandidate candidate = new LeadCandidate(
                businessName.toString().trim(), "openstreetmap",
                osmType + "/" + osmId);
        Object phone = row.get("phone");
        if (phone == null) phone = row.get("normalized_phone");
        if (phone != null) candidate.setPhone(phone.toString());
        Object website = row.get("website");
        if (website != null) candidate.setWebsite(website.toString());
        Object email = row.get("email");
        if (email != null) candidate.setEmail(email.toString());
        Object city = row.get("city");
        if (city != null) candidate.setCity(city.toString());
        Object state = row.get("state");
        if (state != null) candidate.setState(state.toString());
        Object country = row.get("country");
        if (country != null) candidate.setCountry(country.toString());
        Object address = row.get("address");
        if (address != null) candidate.setAddress(address.toString());
        Object instagram = row.get("instagram");
        if (instagram == null) instagram = row.get("normalized_instagram");
        candidate.setInstagramStatus("NOT_FOUND");
        if (instagram != null && !instagram.toString().isBlank()) {
            String normalized = normalizer.normalizeInstagram(instagram.toString());
            if (normalized != null && !normalized.isBlank()) {
                candidate.setInstagramUsername(normalized);
                candidate.setInstagramUrl("https://instagram.com/" + normalized);
                candidate.setInstagramStatus("FOUND");
            }
        }
        Object contactStatus = row.get("contact_status");
        if (contactStatus != null) candidate.setContactStatus(contactStatus.toString());
        Object contactSource = row.get("contact_source");
        if (contactSource != null) candidate.setContactSource(contactSource.toString());
        Object contactSourceUrl = row.get("contact_source_url");
        if (contactSourceUrl != null) candidate.setContactSourceUrl(contactSourceUrl.toString());
        // Sem rematch: lead ja qualificado no sync. Marca diagnostico como pool.
        candidate.setNicheMatchType("QUALIFIED_POOL");
        candidate.setNicheMatchedRule("osm_place_niches");
        return candidate;
    }

    private String normalizeForCompare(String s) {
        if (s == null) return "";
        return java.text.Normalizer.normalize(s.trim().toLowerCase(Locale.ROOT),
                        java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "").replaceAll("\\s+", " ").trim();
    }
}
