package com.gendaz.leads.osm.enrichment;

/**
 * Prioridade de telefone (somente origem oficial):
 * contact:whatsapp > whatsapp > contact:phone > phone > contact:mobile >
 * mobile > contact:sms > sms > website oficial > pagina de contato oficial > hub oficial.
 * Sempre preserva provenance.
 */
public final class PhoneResolver {

    private PhoneResolver() {}

    public record Phone(String normalized, String raw, String source, String sourceUrl) {}

    private static final String[] DIRECT_KEYS = {
            "contact:whatsapp", "whatsapp",
            "contact:phone", "phone",
            "contact:mobile", "mobile",
            "contact:sms", "sms"};

    public static Phone fromTags(com.fasterxml.jackson.databind.JsonNode tags, String countryCode) {
        if (tags == null) return null;
        for (String key : DIRECT_KEYS) {
            com.fasterxml.jackson.databind.JsonNode n = tags.get(key);
            if (n == null || n.isNull() || n.asText().isBlank()) continue;
            String norm = normalize(n.asText(), countryCode);
            if (norm != null) {
                return new Phone(norm, n.asText().trim(), "DIRECT_OSM:" + key, null);
            }
        }
        return null;
    }

    public static Phone fromText(String text, String source, String sourceUrl, String countryCode) {
        if (text == null || text.isBlank()) return null;
        // tel: links primeiro.
        java.util.regex.Matcher tel = java.util.regex.Pattern.compile(
                "(?i)href\\s*=\\s*[\"']\\s*tel:([^\"']+)[\"']").matcher(text);
        while (tel.find()) {
            String norm = normalize(tel.group(1), countryCode);
            if (norm != null) return new Phone(norm, tel.group(1), source + ":TEL", sourceUrl);
        }
        // wa.me / api.whatsapp phone=.
        java.util.regex.Matcher wa = java.util.regex.Pattern.compile(
                "(?i)(?:wa\\.me/|phone=)(\\+?[0-9][0-9\\s.()\\-]{7,20})").matcher(text);
        while (wa.find()) {
            String norm = normalize(wa.group(1), countryCode);
            if (norm != null) return new Phone(norm, wa.group(1), source + ":WHATSAPP_LINK", sourceUrl);
        }
        // Generico BR: 55? DDD? numero.
        java.util.regex.Matcher gen = java.util.regex.Pattern.compile(
                "(\\+?55\\s?)?\\(?([1-9]{2})\\)?[\\s.-]?([0-9]{4,5})[\\s.-]?([0-9]{4})").matcher(text);
        while (gen.find()) {
            String norm = normalize(gen.group(0), countryCode);
            if (norm != null) return new Phone(norm, gen.group(0), source + ":TEXT", sourceUrl);
        }
        return null;
    }

    public static String normalize(String raw, String countryCode) {
        if (raw == null || raw.isBlank()) return null;
        String[] parts = raw.split(";");
        for (String part : parts) {
            String n = normalizeSingle(part, countryCode);
            if (n != null) return n;
        }
        return null;
    }

    static String normalizeSingle(String raw, String countryCode) {
        String digits = raw.replaceAll("\\D", "");
        if (digits.isBlank()) return null;
        while (digits.startsWith("00") && digits.length() > 2) digits = digits.substring(2);
        boolean br = countryCode == null || countryCode.isBlank() || "br".equalsIgnoreCase(countryCode);
        if (br) {
            if (digits.startsWith("5555")) return null;
            if (digits.length() == 10 || digits.length() == 11) {
                int ddd = Integer.parseInt(digits.substring(0, 2));
                if (ddd < 11 || ddd > 99) return null;
                return "55" + digits;
            }
            if (digits.length() == 12 || digits.length() == 13) {
                if (!digits.startsWith("55")) return null;
                String rest = digits.substring(2);
                if (rest.length() != 10 && rest.length() != 11) return null;
                int ddd = Integer.parseInt(rest.substring(0, 2));
                if (ddd < 11 || ddd > 99) return null;
                return digits;
            }
            return null;
        }
        if (digits.length() < 8 || digits.length() > 15) return null;
        return digits;
    }
}
