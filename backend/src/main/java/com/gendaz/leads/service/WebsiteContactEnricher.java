package com.gendaz.leads.service;

import com.gendaz.leads.util.Normalizer;
import com.gendaz.leads.util.SsrfGuard;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class WebsiteContactEnricher {

    private static final Pattern PHONE_PATTERN = Pattern.compile(
            "(?:tel:|\\+?[1-9]\\d{1,2}[\\s.-]?)?\\(?\\d{2,4}\\)?[\\s.-]?\\d{3,4}[\\s.-]?\\d{3,4}", Pattern.CASE_INSENSITIVE);
    private static final Pattern EMAIL_PATTERN = Pattern.compile(
            "(?:mailto:)?([A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,})", Pattern.CASE_INSENSITIVE);
    private static final Pattern IG_LINK = Pattern.compile(
            "(?:https?://)?(?:www\\.)?instagram\\.com/([A-Za-z0-9_.]+)", Pattern.CASE_INSENSITIVE);

    private final RestClient restClient;
    private final Normalizer normalizer;
    private final SsrfGuard ssrfGuard;
    private final int connectTimeoutMs;
    private final int readTimeoutMs;
    private final int maxBytes;

    public WebsiteContactEnricher(
            RestClient.Builder builder,
            Normalizer normalizer,
            SsrfGuard ssrfGuard,
            @Value("${app.enrichment.connect-timeout-ms:2000}") int connectTimeoutMs,
            @Value("${app.enrichment.read-timeout-ms:3000}") int readTimeoutMs,
            @Value("${app.enrichment.max-bytes:500000}") int maxBytes
    ) {
        this.normalizer = normalizer;
        this.ssrfGuard = ssrfGuard;
        this.connectTimeoutMs = connectTimeoutMs;
        this.readTimeoutMs = readTimeoutMs;
        this.maxBytes = maxBytes;

        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(connectTimeoutMs);
        factory.setReadTimeout(readTimeoutMs);
        this.restClient = builder
                .requestFactory(factory)
                .defaultHeader("User-Agent", "GendazLeads/1.0 (+https://gendaz.com)")
                .build();
    }

    public record WebsiteContactData(String phone, String email, String instagramUsername) {}

    public WebsiteContactData enrich(String website) {
        if (website == null || website.isBlank() || !ssrfGuard.isSafe(website)) {
            return new WebsiteContactData(null, null, null);
        }
        String target = website.trim();
        if (!target.startsWith("http")) target = "https://" + target;
        try {
            String body = restClient.get()
                    .uri(target)
                    .retrieve()
                    .body(String.class);
            if (body == null || body.length() > maxBytes) return new WebsiteContactData(null, null, null);
            
            String phone = extractPhone(body);
            String email = extractEmail(body);
            String instagram = extractInstagram(body);
            
            return new WebsiteContactData(phone, email, instagram);
        } catch (RuntimeException e) {
            return new WebsiteContactData(null, null, null);
        }
    }

    private String extractPhone(String html) {
        Matcher m = PHONE_PATTERN.matcher(html);
        while (m.find()) {
            String phone = m.group(0);
            String normalized = normalizer.normalizePhone(phone);
            if (normalized != null && normalized.length() >= 8) {
                return normalized;
            }
        }
        return null;
    }

    private String extractEmail(String html) {
        Matcher m = EMAIL_PATTERN.matcher(html);
        while (m.find()) {
            String email = m.group(1);
            String normalized = normalizer.normalizeEmail(email);
            if (normalized != null) {
                return normalized;
            }
        }
        return null;
    }

    private String extractInstagram(String html) {
        Matcher m = IG_LINK.matcher(html);
        while (m.find()) {
            String handle = m.group(1);
            if (handle != null && !isReserved(handle)) {
                return normalizer.normalizeInstagram(handle);
            }
        }
        return null;
    }

    private boolean isReserved(String handle) {
        return switch (handle.toLowerCase()) {
            case "p", "reel", "tv", "explore", "accounts", "direct", "developer",
                    "about", "legal", "press", "jobs", "api", "graph", "www" -> true;
            default -> false;
        };
    }
}