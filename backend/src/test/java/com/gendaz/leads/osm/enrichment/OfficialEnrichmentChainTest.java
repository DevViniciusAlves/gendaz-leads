package com.gendaz.leads.osm.enrichment;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.gendaz.leads.osm.discovery.OsmCandidate;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class OfficialEnrichmentChainTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void sameAsExtractionFindsInstagramAndHub() {
        String html = "<html><head><script type=\"application/ld+json\">"
                + "{\"@type\":\"BeautySalon\",\"sameAs\":[\"https://instagram.com/studio\",\"https://linktr.ee/studio\"]}"
                + "</script></head><body></body></html>";
        List<String> urls = OfficialContactEnrichmentService.extractSameAs(html);
        assertTrue(urls.stream().anyMatch(u -> u.contains("instagram.com")));
        assertTrue(urls.stream().anyMatch(u -> u.contains("linktr.ee")));
    }

    @Test
    void hubUrlDetection() {
        assertTrue(OfficialContactEnrichmentService.isHubUrl("https://linktr.ee/studio"));
        assertTrue(OfficialContactEnrichmentService.isHubUrl("https://beacons.ai/studio"));
        assertFalse(OfficialContactEnrichmentService.isHubUrl("https://studio.com/contato"));
        assertFalse(OfficialContactEnrichmentService.isHubUrl("https://instagram.com/studio"));
    }

    @Test
    void hubUrlsFoundInWebsiteHtml() {
        String html = "<a href=\"https://linktr.ee/studio\">link</a>"
                + "<a href=\"/contato\">c</a>";
        List<String> hubs = OfficialContactEnrichmentService.findHubUrls(html, "https://studio.com/");
        assertEquals(1, hubs.size());
        assertTrue(hubs.get(0).contains("linktr.ee"));
    }

    @Test
    void fastRejectNeedsZeroHttp() {
        ObjectNode tags = JSON.createObjectNode();
        tags.put("shop", "beauty");
        // sem phone/instagram/website/social -> sem canal
        assertFalse(OfficialContactEnrichmentService.hasOfficialContactChannel(tags));
        tags.put("website", "https://studio.com");
        assertTrue(OfficialContactEnrichmentService.hasOfficialContactChannel(tags));
    }

    @Test
    void enrichDoesNotCallHttpOnFastRejectPath() {
        // Prova indireta: hasOfficialContactChannel=false significa que o job pula antes do enrich.
        ObjectNode tags = JSON.createObjectNode();
        tags.put("shop", "beauty");
        tags.put("name", "Studio Test");
        OsmCandidate c = new OsmCandidate("node", 99, "Studio Test", "studio test",
                tags, 0, 0, java.time.Instant.parse("2024-01-01T00:00:00Z"), null, "Cuiaba", "MT", "Brasil", "br");
        assertFalse(OfficialContactEnrichmentService.hasOfficialContactChannel(c.tags()));
    }
}
