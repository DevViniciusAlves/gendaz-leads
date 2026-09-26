package com.gendaz.leads.osm.enrichment;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class OsmHardeningTest {

    @Test
    void sameAsGraphIsSupported() {
        String html = "<html><head><script type=\"application/ld+json\">"
                + "{\"@context\":\"https://schema.org\",\"@graph\":["
                + "{\"@type\":\"LocalBusiness\",\"sameAs\":[\"https://instagram.com/studiox\",\"https://linktr.ee/studiox\"]}"
                + "]}</script></head><body></body></html>";
        List<String> urls = OfficialContactEnrichmentService.extractSameAs(html);
        assertTrue(urls.stream().anyMatch(u -> u.contains("instagram.com/studiox")));
        assertTrue(urls.stream().anyMatch(u -> u.contains("linktr.ee/studiox")));
    }

    @Test
    void sameAsRootArrayAndNested() {
        String root = "<script type=\"application/ld+json\">"
                + "{\"@type\":\"BeautySalon\",\"sameAs\":\"https://instagram.com/root\"}</script>";
        assertTrue(OfficialContactEnrichmentService.extractSameAs(root).stream()
                .anyMatch(u -> u.contains("instagram.com/root")));
        String arr = "<script type=\"application/ld+json\">"
                + "[{\"@type\":\"BeautySalon\",\"sameAs\":[\"https://instagram.com/a\"]},"
                + "{\"@type\":\"BeautySalon\",\"sameAs\":[\"https://instagram.com/b\"]}]</script>";
        List<String> urls = OfficialContactEnrichmentService.extractSameAs(arr);
        assertTrue(urls.stream().anyMatch(u -> u.contains("instagram.com/a")));
        assertTrue(urls.stream().anyMatch(u -> u.contains("instagram.com/b")));
    }

    @Test
    void singleFlightSameUrlOneHttpCall() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        ConcurrentHashMap<String, CompletableFuture<String>> inflight = new ConcurrentHashMap<>();
        int workers = 8;
        CountDownLatch start = new CountDownLatch(1);
        List<CompletableFuture<String>> futures = new java.util.ArrayList<>();
        for (int i = 0; i < workers; i++) {
            futures.add(CompletableFuture.supplyAsync(() -> {
                try {
                    start.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return inflight.computeIfAbsent("https://studio.com/",
                        k -> CompletableFuture.supplyAsync(() -> {
                            calls.incrementAndGet();
                            try {
                                Thread.sleep(50);
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                            }
                            return "html";
                        })).join();
            }));
        }
        start.countDown();
        for (CompletableFuture<String> f : futures) {
            assertEquals("html", f.get(10, TimeUnit.SECONDS));
        }
        assertEquals(1, calls.get());
    }

    @Test
    void readLimitedAbortsOverMaxBytes() throws Exception {
        byte[] big = new byte[100];
        java.io.InputStream in = new java.io.ByteArrayInputStream(big);
        assertNull(OfficialWebsiteFetcher.readLimited(in, 10));
        java.io.InputStream small = new java.io.ByteArrayInputStream(new byte[5]);
        assertEquals(5, OfficialWebsiteFetcher.readLimited(small, 10).length);
    }
}
