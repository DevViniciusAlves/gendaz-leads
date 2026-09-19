package com.gendaz.leads.service.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gendaz.leads.domain.LeadCandidate;
import com.gendaz.leads.service.InstagramDetector;
import com.gendaz.leads.util.Normalizer;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class OpenStreetMapProviderInstagramEnrichmentTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final InstagramDetector instagramDetector = mock(InstagramDetector.class);
    private final OpenStreetMapProvider provider =
            new OpenStreetMapProvider(null, mapper, instagramDetector, new Normalizer());

    private com.fasterxml.jackson.databind.JsonNode el(String json) throws Exception {
        return mapper.readTree(json);
    }

    @Test
    void extractsInstagramFromWebsiteWhenNotPresentInTags() throws Exception {
        var node = el("""
                {"type":"node","id":123,"tags":{
                  "name":"Studio Bella",
                  "website":"https://studiobella.com",
                  "addr:city":"Curitiba"}}""");
        var geo = new OpenStreetMapProvider.Geo(-25.4, -49.2, "Curitiba", "Parana", "BR", 0,0,0,0,false);
        
        when(instagramDetector.detectFromWebsite("https://studiobella.com")).thenReturn("studio.bella");
        
        LeadCandidate c = provider.mapElement(node, geo);
        
        assertNotNull(c);
        assertEquals("studio.bella", c.getInstagramUsername());
        assertEquals("https://instagram.com/studio.bella", c.getInstagramUrl());
        assertEquals("FOUND", c.getInstagramStatus());
        verify(instagramDetector, times(1)).detectFromWebsite("https://studiobella.com");
    }

    @Test
    void cachesInstagramDetectionForSameWebsite() throws Exception {
        var node = el("""
                {"type":"node","id":123,"tags":{
                  "name":"Studio Bella",
                  "website":"https://studiobella.com",
                  "addr:city":"Curitiba"}}""");
        var geo = new OpenStreetMapProvider.Geo(-25.4, -49.2, "Curitiba", "Parana", "BR", 0,0,0,0,false);
        
        when(instagramDetector.detectFromWebsite("https://studiobella.com")).thenReturn("studio.bella");
        
        // First call
        provider.mapElement(node, geo);
        // Second call with same website
        provider.mapElement(node, geo);
        
        verify(instagramDetector, times(1)).detectFromWebsite("https://studiobella.com");
    }

    @Test
    void instagramStatusIsNotFoundWhenWebsiteDetectionFails() throws Exception {
        var node = el("""
                {"type":"node","id":123,"tags":{
                  "name":"Studio Bella",
                  "website":"https://studiobella.com",
                  "addr:city":"Curitiba"}}""");
        var geo = new OpenStreetMapProvider.Geo(-25.4, -49.2, "Curitiba", "Parana", "BR", 0,0,0,0,false);
        
        when(instagramDetector.detectFromWebsite("https://studiobella.com")).thenReturn(null);
        
        LeadCandidate c = provider.mapElement(node, geo);
        
        assertEquals("NOT_FOUND", c.getInstagramStatus());
        assertNull(c.getInstagramUsername());
    }
}
