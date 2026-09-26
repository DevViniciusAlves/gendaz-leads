package com.gendaz.leads.osm.enrichment;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ContactResolversTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void phonePriorityWhatsappFirst() {
        ObjectNode tags = JSON.createObjectNode();
        tags.put("phone", "+55 (65) 9999-1111");
        tags.put("contact:whatsapp", "+55 65 99999-2222");
        PhoneResolver.Phone p = PhoneResolver.fromTags(tags, "br");
        assertNotNull(p);
        assertEquals("5565999992222", p.normalized());
        assertTrue(p.source().contains("contact:whatsapp"));
    }

    @Test
    void phoneRejectsInvalidDdd() {
        assertNull(PhoneResolver.normalize("+55 (05) 9999-1111", "br"));
        assertNull(PhoneResolver.normalize("555512345678", "br"));
    }

    @Test
    void instagramReservedBlocked() {
        assertNull(InstagramResolver.normalize("https://www.instagram.com/p/ABC123/"));
        assertNull(InstagramResolver.normalize("explore"));
        assertEquals("studio.bella", InstagramResolver.normalize("@Studio.Bella"));
        assertEquals("studio.bella",
                InstagramResolver.normalize("https://instagram.com/studio.bella/?hl=pt"));
    }

    @Test
    void instagramDirectTag() {
        ObjectNode tags = JSON.createObjectNode();
        tags.put("contact:instagram", "https://www.instagram.com/studio.bella/");
        InstagramResolver.InstagramHandle h = InstagramResolver.fromTags(tags);
        assertNotNull(h);
        assertEquals("studio.bella", h.normalized());
    }

    @Test
    void websitePrimaryOnlyFromOsm() {
        ObjectNode tags = JSON.createObjectNode();
        tags.put("brand:website", "https://brand.com");
        OfficialWebsiteFetcher fetcher = new OfficialWebsiteFetcher(100, 100, 1000);
        assertNull(fetcher.primaryWebsite(tags));
        tags.put("website", "studio.com");
        assertNotNull(fetcher.primaryWebsite(tags));
    }
}
