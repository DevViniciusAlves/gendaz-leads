package com.gendaz.leads.osm.persistence;

import com.gendaz.leads.osm.discovery.OsmSourceTimestamp;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Scan state em Java. Outcomes: NO_PHONE, NO_INSTAGRAM, NOT_ON_WHATSAPP,
 * NICHE_NOT_CONFIRMED, DUPLICATE_*, QUALIFIED. Falha tecnica WPP nunca gera
 * cache negativo. TTL 30 dias; reavalia se source_timestamp mudou.
 * Pipeline version invalida cache quando algoritmo muda.
 *
 * <p>source_timestamp e sempre Instant tipado (nunca String/CAST textual).
 */
public class OsmCandidateScanStateRepository {

    public record ScanEntry(String outcome, Instant retryAfter, Instant sourceTimestamp, String pipelineVersion) {}

    public static final String PIPELINE_VERSION = "java-osm-v2";

    public ScanEntry find(Connection con, long regionId, String canonicalNiche,
                          String osmType, long osmId) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(
                "SELECT outcome, retry_after, source_timestamp, pipeline_version FROM osm_candidate_scan_state "
                        + "WHERE region_id = ? AND canonical_niche = ? AND osm_type = ? AND osm_id = ?")) {
            ps.setLong(1, regionId);
            ps.setString(2, canonicalNiche);
            ps.setString(3, osmType);
            ps.setLong(4, osmId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                Timestamp retry = rs.getTimestamp(2);
                Timestamp src = rs.getTimestamp(3);
                return new ScanEntry(rs.getString(1),
                        retry == null ? null : retry.toInstant(),
                        src == null ? null : src.toInstant(),
                        rs.getString(4));
            }
        }
    }

    /**
     * Carrega todo o scan state de uma vez para evitar N+1 queries.
     * Retorna mapa chaveado por candidateKey (osmType/osmId).
     */
    public Map<String, ScanEntry> loadAll(Connection con, long regionId, long targetId,
                                          String canonicalNiche, String pipelineVersion) throws SQLException {
        Map<String, ScanEntry> result = new HashMap<>();
        String sql = """
                SELECT osm_type, osm_id, outcome, retry_after, source_timestamp, pipeline_version
                FROM osm_candidate_scan_state
                WHERE region_id = ? AND canonical_niche = ? AND pipeline_version = ?
                """;
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setLong(1, regionId);
            ps.setString(2, canonicalNiche);
            ps.setString(3, pipelineVersion);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String osmType = rs.getString(1);
                    long osmId = rs.getLong(2);
                    Timestamp retry = rs.getTimestamp(4);
                    Timestamp src = rs.getTimestamp(5);
                    ScanEntry entry = new ScanEntry(rs.getString(3),
                            retry == null ? null : retry.toInstant(),
                            src == null ? null : src.toInstant(),
                            rs.getString(6));
                    result.put(key(osmType, osmId), entry);
                }
            }
        }
        return result;
    }

    private static String key(String osmType, long osmId) {
        return (osmType == null ? "?" : osmType) + "/" + osmId;
    }

    public boolean shouldSkip(ScanEntry entry, Instant currentSourceTimestamp) {
        if (entry == null) return false;
        if ("QUALIFIED".equals(entry.outcome())) return true;
        // source_timestamp mudou: reavalia (comparacao tipada Instant).
        if (!Objects.equals(currentSourceTimestamp, entry.sourceTimestamp())
                && currentSourceTimestamp != null && entry.sourceTimestamp() != null) {
            return false;
        }
        // Pipeline version diferente: reavalia (ja filtrado no loadAll, mas defensivo).
        if (entry.pipelineVersion() != null && !entry.pipelineVersion().equals(PIPELINE_VERSION)) {
            return false;
        }
        if (entry.retryAfter() != null && Instant.now().isAfter(entry.retryAfter())) {
            return false;
        }
        // Sem retry_after: ainda dentro do TTL implicito de 30d? Sem last_checked aqui,
        // trata ausencia como skip (conservador) — o job grava retry_after sempre.
        return true;
    }

    public void upsert(Connection con, long regionId, Long targetId, String canonicalNiche,
                       String osmType, long osmId, Instant sourceTimestamp, String outcome,
                       String phone, String instagram, String details) throws Exception {
        try (PreparedStatement ps = con.prepareStatement(
                "INSERT INTO osm_candidate_scan_state "
                        + "(region_id, target_id, canonical_niche, osm_type, osm_id, source_timestamp, "
                        + "outcome, normalized_phone, normalized_instagram, last_checked_at, retry_after, details, pipeline_version) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, NOW(), "
                        + "CASE WHEN ? IN ('QUALIFIED') THEN NULL ELSE NOW() + INTERVAL '30 days' END, ?, ?) "
                        + "ON CONFLICT (region_id, canonical_niche, osm_type, osm_id) DO UPDATE SET "
                        + "target_id = EXCLUDED.target_id, source_timestamp = EXCLUDED.source_timestamp, "
                        + "outcome = EXCLUDED.outcome, normalized_phone = EXCLUDED.normalized_phone, "
                        + "normalized_instagram = EXCLUDED.normalized_instagram, "
                        + "last_checked_at = NOW(), "
                        + "retry_after = CASE WHEN EXCLUDED.outcome IN ('QUALIFIED') THEN NULL "
                        + "ELSE NOW() + INTERVAL '30 days' END, details = EXCLUDED.details, "
                        + "pipeline_version = EXCLUDED.pipeline_version")) {
            ps.setLong(1, regionId);
            if (targetId == null) ps.setNull(2, java.sql.Types.BIGINT);
            else ps.setLong(2, targetId);
            ps.setString(3, canonicalNiche);
            ps.setString(4, osmType);
            ps.setLong(5, osmId);
            OsmSourceTimestamp.bindInstant(ps, 6, sourceTimestamp);
            ps.setString(7, outcome);
            ps.setString(8, phone);
            ps.setString(9, instagram);
            ps.setString(10, outcome);
            ps.setString(11, details == null ? null : details.substring(0, Math.min(details.length(), 2000)));
            ps.setString(12, PIPELINE_VERSION);
            ps.executeUpdate();
        }
    }
}
