package com.gendaz.leads.osm.persistence;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PostgreSQL real: Flyway V1->V15 + timestamp epoch parse/write/read +
 * scan-state batch + pool upsert. Roda somente quando PG_TEST_JDBC_URL
 * (ou OSM_SYNC_DATABASE_URL) estiver configurado — CI fornece via service.
 */
class OsmPostgresTimestampIT {

    private static String jdbcUrl() {
        String v = System.getenv("PG_TEST_JDBC_URL");
        if (v == null || v.isBlank()) v = System.getenv("OSM_SYNC_DATABASE_URL");
        if (v == null || v.isBlank()) v = System.getenv("DATABASE_URL");
        return v;
    }

    private static String toJdbc(String raw) {
        return BatchDataSourceAccessor.toJdbc(raw);
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "PG_TEST_JDBC_URL", matches = ".+")
    void flywayAndTimestampRoundTrip() throws Exception {
        String raw = jdbcUrl();
        assertNotNull(raw, "PG_TEST_JDBC_URL necessario");
        String jdbc = toJdbc(raw);
        Flyway flyway = Flyway.configure().dataSource(jdbc, null, null).load();
        flyway.migrate();
        try (Connection con = DriverManager.getConnection(jdbc)) {
            // Schema deve estar >= V15.
            try (PreparedStatement ps = con.prepareStatement(
                    "SELECT version FROM flyway_schema_history ORDER BY installed_rank DESC LIMIT 1");
                 ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                assertNotEquals("0", rs.getString(1));
            }
            Instant epoch = Instant.ofEpochSecond(1573317070L);
            // scan-state upsert tipado + read round-trip.
            OsmCandidateScanStateRepository scan = new OsmCandidateScanStateRepository();
            con.setAutoCommit(false);
            scan.upsert(con, 999991L, null, "nails-it", "node", 369650222L, epoch,
                    "NO_PHONE", null, null, "it");
            con.commit();
            OsmCandidateScanStateRepository.ScanEntry entry =
                    scan.find(con, 999991L, "nails-it", "node", 369650222L);
            assertNotNull(entry);
            assertEquals(epoch, entry.sourceTimestamp());
            // pool upsert tipado.
            OsmQualifiedPoolRepository pool = new OsmQualifiedPoolRepository();
            OsmQualifiedPoolRepository.QualifiedLead lead = new OsmQualifiedPoolRepository.QualifiedLead(
                    "node", 369650223L, "IT", "it", -15.0, -56.0, null,
                    "Cuiaba", "MT", "Brasil", "br", "+5565999990001", "+5565999990001",
                    null, "@it", "it", "{}", epoch, "TAG_STRONG", "canonical=nails-it;detail=it",
                    "OSM_DIRECT", null, "OSM_DIRECT", null);
            con.setAutoCommit(false);
            long placeId = pool.upsertPlace(con, 999991L, lead);
            assertTrue(placeId > 0);
            con.rollback();
            con.setAutoCommit(false);
            try (PreparedStatement del = con.prepareStatement(
                    "DELETE FROM osm_candidate_scan_state WHERE region_id = 999991")) {
                del.executeUpdate();
            }
            con.commit();
        }
    }

    /** Reexposicao do conversor privado para o IT (sem duplicar logica). */
    static class BatchDataSourceAccessor {
        static String toJdbc(String raw) {
            String v = raw.trim();
            if (v.startsWith("jdbc:")) return v;
            try {
                java.net.URI uri = java.net.URI.create(v);
                String userInfo = uri.getUserInfo();
                String user = null, pass = null;
                if (userInfo != null) {
                    String[] parts = userInfo.split(":", 2);
                    user = parts[0];
                    if (parts.length > 1) pass = parts[1];
                }
                String query = uri.getQuery() == null ? "" : "?" + uri.getQuery();
                String base = "jdbc:postgresql://" + uri.getHost()
                        + (uri.getPort() > 0 ? ":" + uri.getPort() : "")
                        + (uri.getPath() == null || uri.getPath().isBlank() ? "/" : uri.getPath())
                        + query;
                if (user != null) {
                    base += (query.isEmpty() ? "?" : "&") + "user="
                            + java.net.URLEncoder.encode(user, java.nio.charset.StandardCharsets.UTF_8);
                    if (pass != null) base += "&password="
                            + java.net.URLEncoder.encode(pass, java.nio.charset.StandardCharsets.UTF_8);
                }
                return base;
            } catch (Exception e) {
                return v;
            }
        }
    }
}
