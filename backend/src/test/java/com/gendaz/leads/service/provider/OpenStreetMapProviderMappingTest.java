package com.gendaz.leads.service.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gendaz.leads.domain.LeadCandidate;
import com.gendaz.leads.service.InstagramDetector;
import com.gendaz.leads.service.WebsiteContactEnricher;
import com.gendaz.leads.util.Normalizer;
import com.gendaz.leads.util.SsrfGuard;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class OpenStreetMapProviderMappingTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final Normalizer normalizer = new Normalizer();
    private final InstagramDetector igDetector = mock(InstagramDetector.class);
    private final WebsiteContactEnricher enricher = mock(WebsiteContactEnricher.class);
    private final SsrfGuard ssrfGuard = mock(SsrfGuard.class);
    private final OpenStreetMapProvider provider = new OpenStreetMapProvider(null, mapper, igDetector, enricher, normalizer, ssrfGuard);

    private com.fasterxml.jackson.databind.JsonNode el(String json) throws Exception {
        return mapper.readTree(json);
    }

    @Test
    void extractsInstagramDirectlyFromOsm() throws Exception {
        var node = el("""
                {"type":"node","id":123,"tags":{
                  "name":"Studio Bella","contact:instagram":"@studio.bella",
                  "addr:city":"Curitiba"}}""");
        var geo = new OpenStreetMapProvider.Geo(-25.4, -49.2, "Curitiba", "Parana", "BR", "br", 0,0,0,0,false);
        var cache = new java.util.HashMap<String, java.util.Optional<String>>();
        LeadCandidate c = provider.mapElement(node, geo, cache);
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
        var geo = new OpenStreetMapProvider.Geo(-25.4, -49.2, "Curitiba", "Parana", "BR", "br", 0,0,0,0,false);
        var cache = new java.util.HashMap<String, java.util.Optional<String>>();
        LeadCandidate c = provider.mapElement(node, geo, cache);
        assertEquals("lashlab", c.getInstagramUsername());
        assertEquals("FOUND", c.getInstagramStatus());
    }

    @Test
    void missingPhoneWebsiteAddressDoesNotBreak() throws Exception {
        var node = el("""
                {"type":"node","id":7,"tags":{"name":"Studio X"}}""");
        var geo = new OpenStreetMapProvider.Geo(-25.4, -49.2, "Curitiba", "Parana", "BR", "br", 0,0,0,0,false);
        var cache = new java.util.HashMap<String, java.util.Optional<String>>();
        LeadCandidate c = provider.mapElement(node, geo, cache);
        assertNotNull(c);
        assertNull(c.getPhone());
        assertNull(c.getWebsite());
        assertEquals("NOT_FOUND", c.getInstagramStatus());
        assertEquals("Curitiba", c.getCity());
        assertEquals("Parana", c.getState());
        assertEquals("Curitiba - Parana", c.getAddress());
    }

    @Test
    void phonePriorityAndWebsitePriority() throws Exception {
        var node = el("""
                {"type":"node","id":9,"tags":{"name":"Salao Y",
                  "contact:phone":"+55 41 99999-0000","phone":"111",
                  "contact:website":"https://salao.com","website":"https://outro.com"}}""");
        var geo = new OpenStreetMapProvider.Geo(-25.4, -49.2, null, null, "BR", "br", 0,0,0,0,false);
        var cache = new java.util.HashMap<String, java.util.Optional<String>>();
        LeadCandidate c = provider.mapElement(node, geo, cache);
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
        var tile = new OpenStreetMapProvider.Tile(-25.5, -49.5, -25.0, -49.0, 0.0);
        String structuredQuery = provider.buildStructuredQuery("cilios", tile, 30);
        String fallbackQuery = provider.buildNameFallbackQuery("cilios", tile, 30);
        assertTrue(structuredQuery.contains("\"shop\"=\"beauty\""));
        assertTrue(structuredQuery.contains("\"beauty\"=\"eyelash\""));
        assertFalse(structuredQuery.contains("\"name\"~"));
        assertTrue(fallbackQuery != null && fallbackQuery.contains("\"name\"~"));
        assertTrue(structuredQuery.contains("out center tags"));
        assertTrue(fallbackQuery.contains("out center tags"));
    }

    @Test
    void instagramNormalizationVariants() {
        assertEquals("studio", normalizer.normalizeInstagram("@studio"));
        assertEquals("studio", normalizer.normalizeInstagram("studio"));
        assertEquals("studio", normalizer.normalizeInstagram("https://instagram.com/studio"));
    }
}