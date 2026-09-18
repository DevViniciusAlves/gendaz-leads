package com.gendaz.leads.service.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gendaz.leads.domain.LeadCandidate;
import com.gendaz.leads.util.Normalizer;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class OpenStreetMapProviderMappingTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final OpenStreetMapProvider provider =
            new OpenStreetMapProvider(null, mapper, null, new Normalizer());

    private com.fasterxml.jackson.databind.JsonNode el(String json) throws Exception {
        return mapper.readTree(json);
    }

    @Test
    void extractsInstagramDirectlyFromOsm() throws Exception {
        var node = el("""
                {"type":"node","id":123,"tags":{
                  "name":"Studio Bella","contact:instagram":"@studio.bella",
                  "addr:city":"Curitiba"}}""");
        var geo = new OpenStreetMapProvider.Geo(-25.4, -49.2, "Curitiba", "Paraná", "BR");
        LeadCandidate c = provider.mapElement(node, geo);
        assertNotNull(c);
        assertEquals("node/123", c.getSourceId());
        assertEquals("studio.bella", c.getInstagramUsername());
        assertEquals("https://instagram.com/studio.bella", c.getInstagramUrl());
        assertEquals("FOUND", c.getInstagramStatus());
    }

    @Test
    void extractsInstagramUrlForm() throws Exception {
        var node = el("""
                {"type":"way","id":456,"tags":{
                  "name":"Lash Lab","instagram":"https://instagram.com/lashlab/",
                  "addr:city":"Curitiba"}}""");
        var geo = new OpenStreetMapProvider.Geo(-25.4, -49.2, "Curitiba", "Paraná", "BR");
        LeadCandidate c = provider.mapElement(node, geo);
        assertEquals("lashlab", c.getInstagramUsername());
        assertEquals("FOUND", c.getInstagramStatus());
    }

    @Test
    void missingPhoneWebsiteAddressDoesNotBreak() throws Exception {
        var node = el("""
                {"type":"node","id":7,"tags":{"name":"Studio X"}}""");
        var geo = new OpenStreetMapProvider.Geo(-25.4, -49.2, "Curitiba", "Paraná", "BR");
        LeadCandidate c = provider.mapElement(node, geo);
        assertNotNull(c);
        assertNull(c.getPhone());
        assertNull(c.getWebsite());
        assertEquals("NOT_FOUND", c.getInstagramStatus());
        // cidade/estado vindos do geocoding, sem virgulas estranhas
        assertEquals("Curitiba", c.getCity());
        assertEquals("Paraná", c.getState());
        assertEquals("Curitiba - Paraná", c.getAddress());
    }

    @Test
    void phonePriorityAndWebsitePriority() throws Exception {
        var node = el("""
                {"type":"node","id":9,"tags":{"name":"Salao Y",
                  "contact:phone":"+55 41 99999-0000","phone":"111",
                  "contact:website":"https://salao.com","website":"https://outro.com"}}""");
        var geo = new OpenStreetMapProvider.Geo(-25.4, -49.2, null, null, "BR");
        LeadCandidate c = provider.mapElement(node, geo);
        assertEquals("+55 41 99999-0000", c.getPhone());
        assertEquals("https://salao.com", c.getWebsite());
    }

    @Test
    void categoryPrefersBeautySubcategory() throws Exception {
        var tags = mapper.readTree("{\"shop\":\"beauty\",\"beauty\":\"nails\"}");
        assertEquals("beauty:nails", OpenStreetMapProvider.buildCategory(tags));
        var tags2 = mapper.readTree("{\"shop\":\"hairdresser\"}");
        assertEquals("shop:hairdresser", OpenStreetMapProvider.buildCategory(tags2));
        var tags3 = mapper.readTree("{\"amenity\":\"dentist\"}");
        assertEquals("amenity:dentist", OpenStreetMapProvider.buildCategory(tags3));
    }

    @Test
    void duplicateSourceIdsDeduplicatedByExplicitKey() {
        Set<String> seen = new HashSet<>();
        assertTrue(seen.add("openstreetmap:node/123"));
        assertFalse(seen.add("openstreetmap:node/123"));
    }

    @Test
    void queryUsesStructuredTagsAndNameFallback() {
        var geo = new OpenStreetMapProvider.Geo(-25.43, -49.27, "Curitiba", "Paraná", "BR");
        String q = provider.buildOverpassQuery("cílios", geo, 30);
        assertTrue(q.contains("\"shop\"=\"beauty\""));
        assertTrue(q.contains("\"beauty\"=\"eyelash\""));
        assertTrue(q.contains("\"name\"~"));
        assertTrue(q.contains("out center tags"));
    }

    @Test
    void outLimitHasReasonableCap() {
        assertEquals(80, OpenStreetMapProvider.outLimit(10));
        assertTrue(OpenStreetMapProvider.outLimit(500) <= 200);
    }

    @Test
    void instagramNormalizationVariants() {
        Normalizer n = new Normalizer();
        assertEquals("studio", n.normalizeInstagram("@studio"));
        assertEquals("studio", n.normalizeInstagram("studio"));
        assertEquals("studio", n.normalizeInstagram("https://instagram.com/studio"));
    }
}
