package com.gendaz.leads.osm.persistence;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * Publicacao atomica do pool qualificado:
 * upsert places -> upsert place_niches -> upsert scan_state ->
 * recalcula target.qualified_count/available -> atualiza sync_run final ->
 * target lastSuccess/lastError -> commit. Falha no meio = rollback,
 * sem destruir pool anterior. Run incremental nunca desativa leads antigos.
 */
public class OsmQualifiedPoolRepository {

    public record QualifiedLead(
            String osmType, long osmId, String businessName, String normalizedName,
            double latitude, double longitude, String address,
            String city, String state, String country, String countryCode,
            String phone, String normalizedPhone, String website,
            String instagram, String normalizedInstagram,
            String tagsJson, String sourceTimestamp,
            String evidenceType, String evidenceDetails,
            String contactSource, String contactSourceUrl,
            String instagramSource, String instagramSourceUrl) {}

    public long upsertPlace(Connection con, long regionId, QualifiedLead lead) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(
                "INSERT INTO osm_places (region_id, osm_type, osm_id, business_name, normalized_name, "
                        + "latitude, longitude, address, city, state, country, country_code, phone, "
                        + "normalized_phone, website, instagram, normalized_instagram, tags, active, "
                        + "qualified, qualified_at, whatsapp_verified, whatsapp_verified_at, "
                        + "instagram_validated, instagram_validated_at, instagram_source, instagram_source_url, "
                        + "last_qualified_niche, contact_status, contact_source, contact_source_url, "
                        + "enriched_at, last_seen_at, source_timestamp) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS JSONB), TRUE, "
                        + "TRUE, NOW(), TRUE, NOW(), TRUE, NOW(), ?, ?, ?, 'QUALIFIED', ?, ?, NOW(), NOW(), "
                        + "CAST(? AS TIMESTAMPTZ)) "
                        + "ON CONFLICT (osm_type, osm_id) DO UPDATE SET "
                        + "business_name = EXCLUDED.business_name, normalized_name = EXCLUDED.normalized_name, "
                        + "latitude = EXCLUDED.latitude, longitude = EXCLUDED.longitude, "
                        + "address = EXCLUDED.address, city = EXCLUDED.city, state = EXCLUDED.state, "
                        + "country = EXCLUDED.country, country_code = EXCLUDED.country_code, "
                        + "phone = EXCLUDED.phone, normalized_phone = EXCLUDED.normalized_phone, "
                        + "website = EXCLUDED.website, instagram = EXCLUDED.instagram, "
                        + "normalized_instagram = EXCLUDED.normalized_instagram, tags = EXCLUDED.tags, "
                        + "active = TRUE, qualified = TRUE, qualified_at = COALESCE(osm_places.qualified_at, NOW()), "
                        + "whatsapp_verified = TRUE, whatsapp_verified_at = NOW(), "
                        + "instagram_validated = TRUE, instagram_validated_at = NOW(), "
                        + "instagram_source = EXCLUDED.instagram_source, "
                        + "instagram_source_url = EXCLUDED.instagram_source_url, "
                        + "last_qualified_niche = EXCLUDED.last_qualified_niche, "
                        + "contact_status = EXCLUDED.contact_status, contact_source = EXCLUDED.contact_source, "
                        + "contact_source_url = EXCLUDED.contact_source_url, enriched_at = NOW(), "
                        + "last_seen_at = NOW(), source_timestamp = EXCLUDED.source_timestamp "
                        + "RETURNING id")) {
            int i = 1;
            ps.setLong(i++, regionId);
            ps.setString(i++, lead.osmType());
            ps.setLong(i++, lead.osmId());
            ps.setString(i++, lead.businessName());
            ps.setString(i++, lead.normalizedName());
            ps.setDouble(i++, lead.latitude());
            ps.setDouble(i++, lead.longitude());
            ps.setString(i++, lead.address());
            ps.setString(i++, lead.city());
            ps.setString(i++, lead.state());
            ps.setString(i++, lead.country());
            ps.setString(i++, lead.countryCode());
            ps.setString(i++, lead.phone());
            ps.setString(i++, lead.normalizedPhone());
            ps.setString(i++, lead.website());
            ps.setString(i++, lead.instagram());
            ps.setString(i++, lead.normalizedInstagram());
            ps.setString(i++, lead.tagsJson() == null ? "{}" : lead.tagsJson());
            ps.setString(i++, lead.instagramSource());
            ps.setString(i++, lead.instagramSourceUrl());
            ps.setString(i++, lead.tagsJson() == null ? null : lastNicheFromEvidence(lead));
            ps.setString(i++, lead.contactSource());
            ps.setString(i++, lead.contactSourceUrl());
            ps.setString(i++, lead.sourceTimestamp());
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private static String lastNicheFromEvidence(QualifiedLead lead) {
        // Compatibilidade: last_qualified_niche espelha o nicho atual sem ser verdade.
        return lead.evidenceDetails() != null && lead.evidenceDetails().startsWith("canonical=")
                ? lead.evidenceDetails().substring("canonical=".length()).split("[; ]")[0]
                : null;
    }

    public void upsertPlaceNiche(Connection con, long placeId, long targetId, String canonicalNiche,
                                 String evidenceType, String evidenceDetails) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(
                "INSERT INTO osm_place_niches (place_id, target_id, canonical_niche, niche_evidence_type, "
                        + "niche_evidence_details, active, qualified_at, last_seen_at) "
                        + "VALUES (?, ?, ?, ?, ?, TRUE, NOW(), NOW()) "
                        + "ON CONFLICT (place_id, target_id) DO UPDATE SET "
                        + "canonical_niche = EXCLUDED.canonical_niche, "
                        + "niche_evidence_type = EXCLUDED.niche_evidence_type, "
                        + "niche_evidence_details = EXCLUDED.niche_evidence_details, "
                        + "active = TRUE, last_seen_at = NOW()")) {
            ps.setLong(1, placeId);
            ps.setLong(2, targetId);
            ps.setString(3, canonicalNiche);
            ps.setString(4, evidenceType == null ? "TAG_STRONG" : evidenceType);
            ps.setString(5, evidenceDetails);
            ps.executeUpdate();
        }
    }

    public int recalcTargetCounts(Connection con, long targetId) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(
                "SELECT COUNT(*) FROM osm_place_niches n JOIN osm_places p ON p.id = n.place_id "
                        + "WHERE n.target_id = ? AND n.active = TRUE AND p.active = TRUE "
                        + "AND p.qualified = TRUE AND p.whatsapp_verified = TRUE "
                        + "AND p.instagram_validated = TRUE")) {
            ps.setLong(1, targetId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                int total = rs.getInt(1);
                try (PreparedStatement upd = con.prepareStatement(
                        "UPDATE osm_catalog_targets SET qualified_count = ?, "
                                + "available_new_count = ?, "
                                + "pool_status = CASE WHEN ? > 0 THEN 'READY' ELSE pool_status END, "
                                + "updated_at = NOW() WHERE id = ?")) {
                    int available = countAvailableNew(con, targetId);
                    upd.setInt(1, total);
                    upd.setInt(2, available);
                    upd.setInt(3, total);
                    upd.setLong(4, targetId);
                    upd.executeUpdate();
                }
                return total;
            }
        }
    }

    private int countAvailableNew(Connection con, long targetId) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(
                "SELECT COUNT(*) FROM osm_place_niches n JOIN osm_places p ON p.id = n.place_id "
                        + "WHERE n.target_id = ? AND n.active = TRUE AND p.active = TRUE "
                        + "AND p.qualified = TRUE AND p.whatsapp_verified = TRUE "
                        + "AND p.instagram_validated = TRUE "
                        + "AND NOT EXISTS (SELECT 1 FROM leads l WHERE "
                        + "l.normalized_phone = p.normalized_phone "
                        + "OR l.normalized_instagram = p.normalized_instagram "
                        + "OR l.normalized_source_id = 'openstreetmap_' || p.osm_type || '/' || p.osm_id)")) {
            ps.setLong(1, targetId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        } catch (SQLException e) {
            if (e.getMessage() != null && e.getMessage().contains("does not exist")) return 0;
            throw e;
        }
    }
}
