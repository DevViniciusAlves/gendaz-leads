package com.gendaz.leads.osm.persistence;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

/**
 * Conexao JDBC simples do batch (sem Spring no runner GitHub).
 * DATABASE_URL pode vir como jdbc:postgresql://... ou postgres://...
 */
public final class BatchDataSource {

    private BatchDataSource() {}

    public static Connection open() throws SQLException {
        String raw = System.getenv("OSM_SYNC_DATABASE_URL");
        if (raw == null || raw.isBlank()) raw = System.getenv("DATABASE_URL");
        if (raw == null || raw.isBlank()) {
            throw new SQLException("OSM_SYNC_DATABASE_URL/DATABASE_URL nao configurada");
        }
        String jdbc = toJdbc(raw);
        return DriverManager.getConnection(jdbc);
    }

    static String toJdbc(String raw) {
        String v = raw.trim();
        if (v.startsWith("jdbc:")) return v;
        // postgres://user:pass@host:port/db?sslmode=require
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
                base += (query.isEmpty() ? "?" : "&") + "user=" + userInfoEncode(user);
                if (pass != null) base += "&password=" + userInfoEncode(pass);
            }
            return base;
        } catch (Exception e) {
            return v;
        }
    }

    private static String userInfoEncode(String s) {
        try {
            return java.net.URLEncoder.encode(s, java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            return s;
        }
    }
}
