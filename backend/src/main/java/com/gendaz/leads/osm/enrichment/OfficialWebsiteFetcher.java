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
import java.util.concurrent.atomic.AtomicLong;

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
    private final int connectTimeoutMs;
    private final int readTimeoutMs;
    private final int maxBytes;
    private final Map<String, FetchResult> cache = new ConcurrentHashMap<>();

    // Metricas thread-safe para observabilidade do run.
    public final AtomicLong fetchSuccess = new AtomicLong();
    public final AtomicLong fetchFailed = new AtomicLong();
    public final AtomicLong fetchTimeout = new AtomicLong();
    public final AtomicLong fetch403 = new AtomicLong();
    public final AtomicLong fetch404 = new AtomicLong();
    public final AtomicLong fetch429 = new AtomicLong();

    public OfficialWebsiteFetcher(int connectTimeoutMs, int readTimeoutMs, int maxBytes) {
        this.connectTimeoutMs = connectTimeoutMs;
        this.readTimeoutMs = readTimeoutMs;
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
        FetchResult cached = cache.get(target);
        if (cached != null) {
            return new FetchResult(cached.url(), cached.html(), "OK_CACHED".equals(cached.outcome()) ? "OK_CACHED" : cached.outcome());
        }
        String current = target;
        for (int redirect = 0; redirect <= 3; redirect++) {
            if (!ssrfGuard.isSafe(current)) {
                FetchResult r = new FetchResult(target, null, "UNSAFE_URL");
                cache.put(target, r);
                fetchFailed.incrementAndGet();
                return r;
            }
            try {
                HttpRequest req = HttpRequest.newBuilder(URI.create(current))
                        .timeout(Duration.ofMillis(readTimeoutMs))
                        .header("User-Agent", "GendazLeads-OsmSync/1.0 (+https://gendaz.com)")
                        .header("Accept", "text/html,application/xhtml+xml")
                        .GET().build();
                HttpResponse<byte[]> res = httpClient.send(req, HttpResponse.BodyHandlers.ofByteArray());
                int status = res.statusCode();
                if (status >= 300 && status < 400 && redirect < 3) {
                    String loc = res.headers().firstValue("location").orElse(null);
                    if (loc == null) {
                        FetchResult r = new FetchResult(target, null, "REDIRECT_NO_LOCATION");
                        cache.put(target, r);
                        fetchFailed.incrementAndGet();
                        return r;
                    }
                    try {
                        String resolved = URI.create(current).resolve(loc).toString();
                        if (!ssrfGuard.isSafe(resolved)) {
                            FetchResult r = new FetchResult(target, null, "UNSAFE_URL");
                            cache.put(target, r);
                            fetchFailed.incrementAndGet();
                            return r;
                        }
                        current = resolved;
                    } catch (Exception e) {
                        FetchResult r = new FetchResult(target, null, "REDIRECT_INVALID");
                        cache.put(target, r);
                        fetchFailed.incrementAndGet();
                        return r;
                    }
                    continue;
                }
                if (status == 429) {
                    FetchResult r = new FetchResult(target, null, "HTTP_429");
                    cache.put(target, r);
                    fetch429.incrementAndGet();
                    return r;
                }
                if (status == 403) {
                    FetchResult r = new FetchResult(target, null, "HTTP_403");
                    cache.put(target, r);
                    fetch403.incrementAndGet();
                    return r;
                }
                if (status == 404) {
                    FetchResult r = new FetchResult(target, null, "HTTP_404");
                    cache.put(target, r);
                    fetch404.incrementAndGet();
                    return r;
                }
                if (status >= 400) {
                    FetchResult r = new FetchResult(target, null, "HTTP_" + status);
                    cache.put(target, r);
                    fetchFailed.incrementAndGet();
                    return r;
                }
                String ct = res.headers().firstValue("content-type").orElse("");
                if (!ct.isEmpty() && !ct.toLowerCase(java.util.Locale.ROOT).contains("html")
                        && !ct.toLowerCase(java.util.Locale.ROOT).contains("text")) {
                    FetchResult r = new FetchResult(target, null, "BINARY_CONTENT");
                    cache.put(target, r);
                    fetchFailed.incrementAndGet();
                    return r;
                }
                byte[] body = res.body();
                if (body == null) {
                    FetchResult r = new FetchResult(target, null, "EMPTY");
                    cache.put(target, r);
                    fetchFailed.incrementAndGet();
                    return r;
                }
                if (body.length > maxBytes) {
                    FetchResult r = new FetchResult(target, null, "TOO_LARGE");
                    cache.put(target, r);
                    fetchFailed.incrementAndGet();
                    return r;
                }
                String html = new String(body, java.nio.charset.StandardCharsets.UTF_8);
                FetchResult r = new FetchResult(target, html, "OK");
                cache.put(target, r);
                fetchSuccess.incrementAndGet();
                return r;
            } catch (java.net.http.HttpTimeoutException e) {
                FetchResult r = new FetchResult(target, null, "TIMEOUT");
                cache.put(target, r);
                fetchTimeout.incrementAndGet();
                return r;
            } catch (IOException | InterruptedException e) {
                if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                FetchResult r = new FetchResult(target, null, "FETCH_FAILED");
                cache.put(target, r);
                fetchFailed.incrementAndGet();
                return r;
            } catch (Exception e) {
                FetchResult r = new FetchResult(target, null, "FETCH_FAILED");
                cache.put(target, r);
                fetchFailed.incrementAndGet();
                return r;
            }
        }
        FetchResult r = new FetchResult(target, null, "REDIRECT_LIMIT");
        cache.put(target, r);
        fetchFailed.incrementAndGet();
        return r;
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
