package com.gendaz.leads.service;

import com.gendaz.leads.util.Normalizer;
import com.gendaz.leads.util.SsrfGuard;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URLDecoder;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpClient.Redirect;
import java.nio.charset.StandardCharsets;
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

    private static final Pattern WHATSAPP_LINK =
            Pattern.compile(
                    "(?i)(?:https?://)?(?:www\\.)?"
                            + "(?:wa\\.me/"
                            + "|(?:api|web)\\.whatsapp\\.com/send\\?[^\\\"'\\s>]*?phone=)"
                            + "([^&\\\"'\\s<>]+)"
            );

    private static final Pattern TEL_LINK =
            Pattern.compile(
                    "(?i)href\\s*=\\s*[\\\"']\\s*tel:([^\\\"']+)[\\\"']"
            );

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

        if (website == null || website.isBlank()) {
            return new WebsiteContactData(null, null, null);
        }

        String target = normalizeTargetUrl(website);

        if (target == null || !ssrfGuard.isSafe(target)) {
            return new WebsiteContactData(null, null, null);
        }

        try {
            String body = fetchWithRedirectValidation(target, 0);

            if (body == null || body.length() > maxBytes) {
                return new WebsiteContactData(null, null, null);
            }

            return new WebsiteContactData(extractPhone(body), extractEmail(body), extractInstagram(body));

        } catch (RuntimeException | IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return new WebsiteContactData(null, null, null);
        }
    }

    private String normalizeTargetUrl(String website) {
        String value = website.trim();

        if (value.isBlank()) {
            return null;
        }

        if (!value.startsWith("http://") && !value.startsWith("https://")) {
            value = "https://" + value;
        }

        try {
            URI uri = URI.create(value);

            if (uri.getHost() == null) {
                return null;
            }

            return uri.toString();

        } catch (RuntimeException e) {
            return null;
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
                if (!ssrfGuard.isSafe(nextUrl)) {
                    return null;
                }
                return fetchWithRedirectValidation(nextUrl, redirectCount + 1);
            }
        }

        if (status < 200 || status >= 300) {
            return null;
        }

        String body = response.body();

        if (body == null) {
            return null;
        }

        if (body.length() > maxBytes) {
            return null;
        }

        return body;
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

    private String normalizeExtractedPhone(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }

        String decoded = raw;

        try {
            decoded = URLDecoder.decode(
                    raw,
                    StandardCharsets.UTF_8
            );
        } catch (IllegalArgumentException ignored) {
            // mantém raw
        }

        String normalized = normalizer.normalizePhone(decoded);

        if (normalized == null || normalized.length() < 8) {
            return null;
        }

        return normalized;
    }

    String extractPhone(String html) {
        if (html == null || html.isBlank()) {
            return null;
        }

        Matcher whatsapp = WHATSAPP_LINK.matcher(html);

        while (whatsapp.find()) {
            String normalized = normalizeExtractedPhone(
                    whatsapp.group(1)
            );

            if (normalized != null) {
                return normalized;
            }
        }

        Matcher tel = TEL_LINK.matcher(html);

        while (tel.find()) {
            String normalized = normalizeExtractedPhone(
                    tel.group(1)
            );

            if (normalized != null) {
                return normalized;
            }
        }

        Matcher generic = PHONE_PATTERN.matcher(html);

        while (generic.find()) {
            String normalized = normalizeExtractedPhone(
                    generic.group(0)
            );

            if (normalized != null) {
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