package com.gendaz.leads.osm.batch;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.gendaz.leads.osm.discovery.OsmCandidate;
import com.gendaz.leads.osm.enrichment.OfficialContactEnrichmentService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class OsmSyncJobLogicTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static OsmCandidate cand(String type, long id, ObjectNode tags, String name) {
        return new OsmCandidate(type, id, name, name == null ? "" : name.toLowerCase(),
                tags, -15.0, -56.0, "2024-01-01T00:00:00Z", null, "Cuiaba", "MT", "Brasil", "br");
    }

    @Test
    void nameAloneIsNotCommercial() {
        ObjectNode tags = JSON.createObjectNode();
        tags.put("name", "Qualquer Coisa");
        OsmCandidate c = cand("node", 1, tags, "Qualquer Coisa");
        assertFalse(OsmSyncJob.isCommercialOrContact(c));
    }

    @Test
    void shopTagIsCommercial() {
        ObjectNode tags = JSON.createObjectNode();
        tags.put("shop", "beauty");
        tags.put("name", "Studio");
        assertTrue(OsmSyncJob.isCommercialOrContact(cand("node", 2, tags, "Studio")));
    }

    @Test
    void websiteAloneIsCommercial() {
        ObjectNode tags = JSON.createObjectNode();
        tags.put("website", "https://studio.com");
        assertTrue(OsmSyncJob.isCommercialOrContact(cand("node", 3, tags, "Studio")));
    }

    @Test
    void priorityTierPhonePlusIgFirst() {
        ObjectNode t1 = JSON.createObjectNode();
        t1.put("phone", "+5565999991111");
        t1.put("contact:instagram", "studio");
        assertEquals(1, OsmSyncJob.priorityTier(cand("node", 1, t1, "A")));

        ObjectNode t2 = JSON.createObjectNode();
        t2.put("phone", "+5565999991111");
        t2.put("website", "https://a.com");
        assertEquals(2, OsmSyncJob.priorityTier(cand("node", 2, t2, "B")));

        ObjectNode t4 = JSON.createObjectNode();
        t4.put("website", "https://a.com");
        assertEquals(4, OsmSyncJob.priorityTier(cand("node", 3, t4, "C")));

        ObjectNode t5 = JSON.createObjectNode();
        t5.put("phone", "+5565999991111");
        assertEquals(5, OsmSyncJob.priorityTier(cand("node", 4, t5, "D")));
    }

    @Test
    void fastRejectWithoutChannel() {
        ObjectNode tags = JSON.createObjectNode();
        tags.put("shop", "beauty");
        tags.put("name", "Studio Test");
        assertFalse(OfficialContactEnrichmentService.hasOfficialContactChannel(tags));
    }

    @Test
    void funnelTarget50Status() {
        assertEquals("SUCCESS", status(50, 50));
        assertEquals("PARTIAL", status(12, 50));
        assertEquals("EXHAUSTED", status(0, 50));
    }

    private static String status(long saved, long target) {
        if (saved >= target) return "SUCCESS";
        if (saved > 0) return "PARTIAL";
        return "EXHAUSTED";
    }

    @Test
    void tierOrdering() {
        ObjectNode t5 = JSON.createObjectNode();
        t5.put("phone", "+5565999991111");
        ObjectNode t1 = JSON.createObjectNode();
        t1.put("phone", "+5565999991111");
        t1.put("contact:instagram", "studio");
        List<OsmSyncJob.TieredCandidate> list = new java.util.ArrayList<>(List.of(
                new OsmSyncJob.TieredCandidate(cand("node", 1, t5, "A"), OsmSyncJob.priorityTier(cand("node", 1, t5, "A"))),
                new OsmSyncJob.TieredCandidate(cand("node", 2, t1, "B"), OsmSyncJob.priorityTier(cand("node", 2, t1, "B")))));
        list.sort(java.util.Comparator.comparingInt(OsmSyncJob.TieredCandidate::tier));
        assertEquals(2L, list.get(0).candidate().osmId());
    }
}
