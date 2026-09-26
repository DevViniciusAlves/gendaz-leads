package com.gendaz.leads.osm.persistence;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashSet;
import java.util.Set;

/**
 * Dedup antes de contar o lead: current run + osm_places/pool + leads globais.
 * Chaves: osm_type+osm_id, normalized_phone, normalized_instagram.
 * Mesmo way/123 com telefone/Instagram diferentes NAO consome outro slot.
 * Dedup por target (cidade+nicho): so considera duplicados dentro do mesmo target.
 */
public class OsmDeduplicator {

    private final Set<String> seenSources = new HashSet<>();
    private final Set<String> seenPhones = new HashSet<>();
    private final Set<String> seenInstagrams = new HashSet<>();
    private final Set<String> poolSources = new HashSet<>();
    private final Set<String> poolPhones = new HashSet<>();
    private final Set<String> poolInstagrams = new HashSet<>();
    private final Set<String> leadPhones = new HashSet<>();
    private final Set<String> leadInstagrams = new HashSet<>();
    private final Set<String> leadSources = new HashSet<>();

    public void loadPool(Connection con, long regionId, Long targetId) throws SQLException {
        String sql;
        if (targetId != null && targetId > 0) {
            // N:N: so carrega places que ja tem membership neste target
            sql = """
                    SELECT p.osm_type, p.osm_id, p.normalized_phone, p.normalized_instagram
                    FROM osm_places p
                    JOIN osm_place_niches n ON n.place_id = p.id
                    WHERE p.region_id = ? AND n.target_id = ? AND n.active = TRUE
                      AND p.active = TRUE AND p.qualified = TRUE
                    """;
        } else {
            // Legado: carrega todos os places qualificados da regiao
            sql = """
                    SELECT osm_type, osm_id, normalized_phone, normalized_instagram FROM osm_places
                    WHERE region_id = ? AND active = TRUE AND qualified = TRUE
                    """;
        }
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setLong(1, regionId);
            if (targetId != null && targetId > 0) {
                ps.setLong(2, targetId);
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    poolSources.add(key(rs.getString(1), rs.getLong(2)));
                    String ph = rs.getString(3);
                    if (ph != null && !ph.isBlank()) poolPhones.add(ph.trim());
                    String ig = rs.getString(4);
                    if (ig != null && !ig.isBlank()) poolInstagrams.add(ig.trim());
                }
            }
        }
        try (PreparedStatement ps = con.prepareStatement(
                "SELECT normalized_phone, normalized_instagram, normalized_source_id FROM leads "
                        + "WHERE normalized_phone IS NOT NULL OR normalized_instagram IS NOT NULL "
                        + "OR normalized_source_id IS NOT NULL LIMIT 100000")) {
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String ph = rs.getString(1);
                    if (ph != null && !ph.isBlank()) leadPhones.add(ph.trim());
                    String ig = rs.getString(2);
                    if (ig != null && !ig.isBlank()) leadInstagrams.add(ig.trim());
                    String src = rs.getString(3);
                    if (src != null && !src.isBlank()) leadSources.add(src.trim());
                }
            }
        } catch (SQLException e) {
            // Tabela leads pode nao existir em dry-run isolado; dedup do pool continua valendo.
            if (!e.getMessage().contains("does not exist")
                    && !e.getMessage().contains("leads")) {
                throw e;
            }
        }
    }

    public enum DuplicateKind { NONE, SOURCE, PHONE, INSTAGRAM, GLOBAL }

    public record DuplicateResult(boolean duplicate, DuplicateKind kind) {}

    public DuplicateResult check(String osmType, long osmId, String phone, String instagram) {
        String src = key(osmType, osmId);
        if (seenSources.contains(src) || poolSources.contains(src)) {
            return new DuplicateResult(true, DuplicateKind.SOURCE);
        }
        if (phone != null) {
            if (seenPhones.contains(phone) || poolPhones.contains(phone) || leadPhones.contains(phone)) {
                return new DuplicateResult(true,
                        leadPhones.contains(phone) ? DuplicateKind.GLOBAL : DuplicateKind.PHONE);
            }
        }
        if (instagram != null) {
            if (seenInstagrams.contains(instagram) || poolInstagrams.contains(instagram)
                    || leadInstagrams.contains(instagram)) {
                return new DuplicateResult(true,
                        leadInstagrams.contains(instagram) ? DuplicateKind.GLOBAL : DuplicateKind.INSTAGRAM);
            }
        }
        // source global openstreetmap osmType/osmId (canonical format)
        String globalSrc = "openstreetmap_" + osmType + "/" + osmId;
        if (leadSources.contains(globalSrc)) {
            return new DuplicateResult(true, DuplicateKind.GLOBAL);
        }
        return new DuplicateResult(false, DuplicateKind.NONE);
    }

    public void markSeen(String osmType, long osmId, String phone, String instagram) {
        seenSources.add(key(osmType, osmId));
        if (phone != null) seenPhones.add(phone);
        if (instagram != null) seenInstagrams.add(instagram);
    }

    private static String key(String osmType, long osmId) {
        return (osmType == null ? "?" : osmType) + "/" + osmId;
    }
}
