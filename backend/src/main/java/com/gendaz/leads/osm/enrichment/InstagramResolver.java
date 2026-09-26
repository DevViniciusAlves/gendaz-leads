package com.gendaz.leads.osm.enrichment;

import java.util.Set;

/**
 * Normalizacao canonica de Instagram + bloqueio de paths reservados.
 * Aceita apenas provenance oficial (tag OSM direta, website oficial,
 * pagina de contato oficial ou hub oficial ligado pelo OSM/site).
 */
public final class InstagramResolver {

    private InstagramResolver() {}

    private static final Set<String> RESERVED = Set.of(
            "p", "reel", "reels", "tv", "explore", "accounts", "direct",
            "developer", "about", "legal", "press", "jobs", "api", "graph", "www",
            "stories", "s", "g", "u");

    public record InstagramHandle(String normalized, String source, String sourceUrl) {}

    public static String normalize(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String v = raw.trim();
        // Extrai handle de URLs.
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(
                "(?i)instagram\\.com/([A-Za-z0-9_.]+)").matcher(v);
        if (m.find()) {
            v = m.group(1);
        } else {
            v = v.replaceAll("^@", "").trim();
        }
        v = v.toLowerCase(java.util.Locale.ROOT).trim();
        if (v.contains("/")) v = v.split("/")[0];
        if (v.contains("?")) v = v.split("\\?")[0];
        v = v.replaceAll("[^a-z0-9_.]", "");
        if (v.isBlank() || v.length() > 30) return null;
        if (RESERVED.contains(v)) return null;
        return v;
    }

    public static InstagramHandle fromTags(com.fasterxml.jackson.databind.JsonNode tags) {
        if (tags == null) return null;
        String direct = text(tags.get("contact:instagram"));
        String source = "DIRECT_OSM_INSTAGRAM";
        if (direct == null) {
            direct = text(tags.get("instagram"));
        }
        if (direct == null) return null;
        String norm = normalize(direct);
        if (norm == null) return null;
        return new InstagramHandle(norm, source, null);
    }

    public static InstagramHandle fromHtml(String html, String source, String sourceUrl) {
        if (html == null || html.isBlank()) return null;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(
                "(?i)instagram\\.com/([A-Za-z0-9_.]{1,30})").matcher(html);
        while (m.find()) {
            String norm = normalize(m.group(1));
            if (norm != null) {
                return new InstagramHandle(norm, source, sourceUrl);
            }
        }
        return null;
    }

    private static String text(com.fasterxml.jackson.databind.JsonNode n) {
        if (n == null || n.isNull()) return null;
        String s = n.asText();
        return s == null || s.isBlank() ? null : s.trim();
    }
}
