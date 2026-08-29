package com.gendaz.leads.service;

import com.gendaz.leads.util.Normalizer;
import com.gendaz.leads.util.SsrfGuard;
import org.springframework.stereotype.Component;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class InstagramDetector {

    private static final Pattern IG_LINK = Pattern.compile(
            "(?:https?://)?(?:www\\.)?instagram\\.com/([A-Za-z0-9_.]+)", Pattern.CASE_INSENSITIVE);
    private static final int MAX_BYTES = 500_000;

    private final RestClient restClient;
    private final Normalizer normalizer;
    private final SsrfGuard ssrfGuard;

    public InstagramDetector(RestClient.Builder builder, Normalizer normalizer, SsrfGuard ssrfGuard) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5000);
        factory.setReadTimeout(10000);
        this.restClient = builder
                .requestFactory(factory)
                .defaultHeader("User-Agent", "GendazLeads/1.0 (+https://gendaz.com)")
                .build();
        this.normalizer = normalizer;
        this.ssrfGuard = ssrfGuard;
    }

    public String detectFromWebsite(String website) {
        if (website == null || website.isBlank() || !ssrfGuard.isSafe(website)) {
            return null;
        }
        String target = website.trim();
        if (!target.startsWith("http")) target = "https://" + target;
        try {
            String body = restClient.get()
                    .uri(target)
                    .retrieve()
                    .body(String.class);
            if (body == null || body.length() > MAX_BYTES) return null;
            return extractHandle(body);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private String extractHandle(String html) {
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
