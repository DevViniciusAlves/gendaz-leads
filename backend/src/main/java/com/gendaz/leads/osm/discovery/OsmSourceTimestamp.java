package com.gendaz.leads.osm.discovery;

import com.fasterxml.jackson.databind.JsonNode;

import java.sql.PreparedStatement;
import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/**
 * Timestamp OSM tipado ponta a ponta.
 *
 * <p>o `osmium export --attributes=type,id,timestamp` emite {@code @timestamp}
 * como epoch em segundos numerico (ex: 1573317070), epoch em millis, ou
 * ISO-8601 dependendo da versao/fixture. O Java nunca deve tratar isso como
 * String para coluna TIMESTAMPTZ (causava
 * {@code date/time field value out of range: "1573317070"} no run #19).
 */
public final class OsmSourceTimestamp {

    private OsmSourceTimestamp() {}

    /** Threshold: valores >= 1e12 sao millis, menores sao segundos. */
    static final long MILLIS_THRESHOLD = 1_000_000_000_000L;

    public static Instant parse(JsonNode value) {
        if (value == null || value.isNull()) return null;
        try {
            if (value.isNumber()) {
                return fromNumeric(value.asLong());
            }
            String raw = value.asText();
            if (raw == null) return null;
            raw = raw.trim();
            if (raw.isEmpty()) return null;
            if (raw.matches("-?\\d+")) {
                return fromNumeric(Long.parseLong(raw));
            }
            // ISO-8601 (ex: 2019-11-09T... / 2024-01-01T00:00:00Z).
            try {
                return Instant.parse(raw);
            } catch (Exception e) {
                // Tenta OffsetDateTime como fallback (ex: sem Z).
                return OffsetDateTime.parse(raw).toInstant();
            }
        } catch (Exception e) {
            return null;
        }
    }

    private static Instant fromNumeric(long raw) {
        if (raw < 0) return null;
        if (raw >= MILLIS_THRESHOLD) {
            return Instant.ofEpochMilli(raw);
        }
        return Instant.ofEpochSecond(raw);
    }

    /** Bind tipado para coluna TIMESTAMPTZ. Nunca usar setString/CAST textual. */
    public static void bindInstant(PreparedStatement ps, int index, Instant instant)
            throws java.sql.SQLException {
        if (instant == null) {
            ps.setNull(index, Types.TIMESTAMP_WITH_TIMEZONE);
        } else {
            ps.setObject(index, instant.atOffset(ZoneOffset.UTC));
        }
    }
}
