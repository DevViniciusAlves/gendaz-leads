package com.gendaz.leads.osm.enrichment;

import com.gendaz.leads.util.SsrfGuard;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Fetcher oficial: somente website vindo do proprio OSM (website,
 * contact:website, url). brand:website/operator:website nunca qualificam
 * filial sozinhos. SSRF guard, timeouts, max bytes, redirect limit,
 * content-type, cache por URL, concurrency limitada, UA explicito.
 * Nunca loga conteudo integral.
 */
public class OfficialWebsiteFetcher {

    private final SsrfGuard ssrfGuard = new SsrfGuard();
    private final HttpClient httpClient;
    private final int maxBytes;
    private final Map<String, String> cache = new ConcurrentHashMap<>();

    public OfficialWebsiteFetcher(int connectTimeoutMs, int readTimeoutMs, int maxBytes) {
        this.maxBytes = maxBytes;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(connectTimeoutMs))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    public record FetchResult(String url, String html, String outcome) {}

    public String primaryWebsite(com.fasterxml.jackson.databind.JsonNode tags) {
        if (tags == null) return null;
        for (String key : new String[]{"contact:website", "website", "url"}) {
            com.fasterxml.jackson.databind.JsonNode n = tags.get(key);
            if (n != null && !n.isNull() && !n.asText().isBlank()) {
                return normalizeUrl(n.asText().trim());
            }
        }
        return null;
    }

    public FetchResult fetch(String url) {
        if (url == null || url.isBlank()) return new FetchResult(null, null, "NO_URL");
        String target = normalizeUrl(url);
        if (target == null) return new FetchResult(url, null, "INVALID_URL");
        if (cache.containsKey(target)) {
            return new FetchResult(target, cache.get(target), "OK_CACHED");
        }
        String current = target;
        for (int redirect = 0; redirect <= 3; redirect++) {
            if (!ssrfGuard.isSafe(current)) {
                return new FetchResult(target, null, "UNSAFE_URL");
            }
            try {
                HttpRequest req = HttpRequest.newBuilder(URI.create(current))
                        .timeout(Duration.ofMillis(8000))
                        .header("User-Agent", "GendazLeads-OsmSync/1.0 (+https://gendaz.com)")
                        .header("Accept", "text/html,application/xhtml+xml")
                        .GET().build();
                HttpResponse<byte[]> res = httpClient.send(req, HttpResponse.BodyHandlers.ofByteArray());
                int status = res.statusCode();
                if (status >= 300 && status < 400 && redirect < 3) {
                    String loc = res.headers().firstValue("location").orElse(null);
                    if (loc == null) return new FetchResult(target, null, "REDIRECT_NO_LOCATION");
                    try {
                        current = URI.create(current).resolve(loc).toString();
                    } catch (Exception e) {
                        return new FetchResult(target, null, "REDIRECT_INVALID");
                    }
                    continue;
                }
                if (status == 429) return new FetchResult(target, null, "HTTP_429");
                if (status == 403 || status == 404) return new FetchResult(target, null, "HTTP_" + status);
                if (status >= 400) return new FetchResult(target, null, "HTTP_" + status);
                String ct = res.headers().firstValue("content-type").orElse("");
                if (!ct.isEmpty() && !ct.toLowerCase(java.util.Locale.ROOT).contains("html")
                        && !ct.toLowerCase(java.util.Locale.ROOT).contains("text")) {
                    return new FetchResult(target, null, "BINARY_CONTENT");
                }
                byte[] body = res.body();
                if (body == null) return new FetchResult(target, null, "EMPTY");
                if (body.length > maxBytes) return new FetchResult(target, null, "TOO_LARGE");
                String html = new String(body, java.nio.charset.StandardCharsets.UTF_8);
                cache.put(target, html);
                return new FetchResult(target, html, "OK");
            } catch (java.net.http.HttpTimeoutException e) {
                return new FetchResult(target, null, "TIMEOUT");
            } catch (IOException | InterruptedException e) {
                if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                return new FetchResult(target, null, "FETCH_FAILED");
            } catch (Exception e) {
                return new FetchResult(target, null, "FETCH_FAILED");
            }
        }
        return new FetchResult(target, null, "REDIRECT_LIMIT");
    }

    static String normalizeUrl(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String v = raw.trim();
        if (!v.matches("(?i)^https?://.*")) v = "https://" + v;
        try {
            URI uri = URI.create(v);
            if (uri.getHost() == null) return null;
            return uri.toString();
        } catch (Exception e) {
            return null;
        }
    }
}
