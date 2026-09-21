package com.gendaz.leads.service;

import com.gendaz.leads.util.Normalizer;
import com.gendaz.leads.util.SsrfGuard;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpClient.Redirect;
import java.time.Duration;
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

    private final Normalizer normalizer;
    private final SsrfGuard ssrfGuard;
    private final int connectTimeoutMs;
    private final int readTimeoutMs;
    private final int maxBytes;
    private final HttpClient httpClient;

    public WebsiteContactEnricher(
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

        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(connectTimeoutMs))
                .followRedirects(Redirect.NEVER)
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
            String body = fetchWithRedirectValidation(target, 0);
            if (body == null || body.length() > maxBytes) return new WebsiteContactData(null, null, null);

            String phone = extractPhone(body);
            String email = extractEmail(body);
            String instagram = extractInstagram(body);

            return new WebsiteContactData(phone, email, instagram);
        } catch (RuntimeException | IOException | InterruptedException e) {
            return new WebsiteContactData(null, null, null);
        }
    }

    private String fetchWithRedirectValidation(String url, int redirectCount) throws IOException, InterruptedException {
        if (redirectCount > 3) {
            return null;
        }

        if (!ssrfGuard.isSafe(url)) {
            return null;
        }

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofMillis(readTimeoutMs))
                .header("User-Agent", "GendazLeads/1.0 (+https://gendaz.com)")
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        int status = response.statusCode();
        if (status >= 300 && status < 400) {
            String location = response.headers().firstValue("Location").orElse(null);
            if (location != null) {
                String nextUrl = resolveUrl(url, location);
                return fetchWithRedirectValidation(nextUrl, redirectCount + 1);
            }
        }

        return response.body();
    }

    private String resolveUrl(String base, String location) {
        try {
            URI baseUri = URI.create(base);
            URI locationUri = URI.create(location);
            if (locationUri.isAbsolute()) {
                return locationUri.toString();
            }
            return baseUri.resolve(locationUri).toString();
        } catch (Exception e) {
            return location;
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