package com.gendaz.leads.util;

import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.regex.Pattern;

@Component
public class Normalizer {

    private static final Pattern NON_ALPHANUMERIC = Pattern.compile("[^a-z0-9]");
    private static final Pattern INSTAGRAM_PATH = Pattern.compile("(?i)instagram\\.com/([A-Za-z0-9_.]+)");
    private static final Pattern EMAIL_PATTERN = Pattern.compile("^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$");

    public String normalizeName(String name) {
        if (name == null) return null;
        String normalized = stripAccents(name.toLowerCase());
        normalized = NON_ALPHANUMERIC.matcher(normalized).replaceAll(" ").trim();
        normalized = normalized.replaceAll("\\s+", " ");
        return normalized.isBlank() ? null : normalized;
    }

    public String normalizePhone(String phone) {
        if (phone == null) return null;
        String digits = phone.replaceAll("[^0-9]", "");
        if (digits.length() < 8) return null;
        if (digits.startsWith("00")) digits = digits.substring(2);
        return digits;
    }

    public String normalizeWebsite(String website) {
        if (website == null) return null;
        try {
            String w = website.trim().toLowerCase();
            if (!w.startsWith("http://") && !w.startsWith("https://")) w = "https://" + w;
            URI uri = URI.create(w);
            String host = uri.getHost();
            if (host == null) return null;
            if (host.startsWith("www.")) host = host.substring(4);
            return host;
        } catch (RuntimeException e) {
            String fallback = website.toLowerCase().replaceAll("^https?://", "")
                    .replaceAll("^www\\.", "").split("/")[0].trim();
            return fallback.isBlank() ? null : fallback;
        }
    }

    public String normalizeInstagram(String handleOrUrl) {
        if (handleOrUrl == null) return null;
        String value = handleOrUrl.trim();
        var m = INSTAGRAM_PATH.matcher(value);
        if (m.find()) {
            value = m.group(1);
        } else {
            value = value.replaceAll("^@", "").trim();
        }
        value = value.toLowerCase();
        if (value.contains("/")) value = value.split("/")[0];
        if (value.isBlank()) return null;
        return value;
    }

    public String normalizeEmail(String email) {
        if (email == null) return null;
        String e = email.trim().toLowerCase();
        if (!EMAIL_PATTERN.matcher(e).matches()) return null;
        return e;
    }

    public String normalizeSourceId(String source, String sourceId) {
        if (sourceId == null) return null;
        String s = (source == null ? "src" : source.toLowerCase());
        // Canonical format for OpenStreetMap: openstreetmap_way/123, openstreetmap_node/456
        if ("openstreetmap".equals(s) && sourceId.contains("/")) {
            return s + "_" + sourceId.toLowerCase();
        }
        return s + "_" + sourceId.toLowerCase();
    }

    public String stripAccents(String input) {
        if (input == null) return null;
        return java.text.Normalizer.normalize(input, java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "");
    }
}
