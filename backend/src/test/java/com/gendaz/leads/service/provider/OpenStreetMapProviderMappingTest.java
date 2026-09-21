package com.gendaz.leads.service.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gendaz.leads.domain.LeadCandidate;
import com.gendaz.leads.util.Normalizer;
import com.gendaz.leads.util.SsrfGuard;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class OpenStreetMapProviderMappingTest {

    private RestClient.Builder restClientBuilder;
    private ObjectMapper objectMapper;
    private Normalizer normalizer;
    private SsrfGuard ssrfGuard;
    private OpenStreetMapProvider provider;

    @BeforeEach
    void setUp() {
        restClientBuilder = mock(RestClient.Builder.class);
        objectMapper = new ObjectMapper();
        normalizer = new Normalizer();
        ssrfGuard = new SsrfGuard();
        provider = new OpenStreetMapProvider(restClientBuilder, objectMapper, normalizer, ssrfGuard);
    }

    @Test
    void contactPhoneMapping() {
        var tags = objectMapper.createObjectNode();
        tags.put("contact:phone", "+55 65 9999-8888");
        tags.put("name", "Barbearia");

        var element = objectMapper.createObjectNode();
        element.put("type", "node");
        element.put("id", "1");
        element.set("tags", tags);
        element.put("lat", -15.6);
        element.put("lon", -56.1);

        var scope = new GeoScope(-15.6, -56.1, "Cuiabá", "MT", "Brazil", "br", -15.7, -56.2, -15.5, -56.0, true, "relation", 333734L);
        LeadCandidate candidate = provider.mapElement(element, scope, true);

        assertNotNull(candidate);
        assertEquals("+55 65 9999-8888", candidate.getPhone());
    }

    @Test
    void phoneMapping() {
        var tags = objectMapper.createObjectNode();
        tags.put("phone", "+55 65 9999-8888");
        tags.put("name", "Barbearia");

        var element = objectMapper.createObjectNode();
        element.put("type", "node");
        element.put("id", "1");
        element.set("tags", tags);
        element.put("lat", -15.6);
        element.put("lon", -56.1);

        var scope = new GeoScope(-15.6, -56.1, "Cuiabá", "MT", "Brazil", "br", -15.7, -56.2, -15.5, -56.0, true, "relation", 333734L);
        LeadCandidate candidate = provider.mapElement(element, scope, true);

        assertNotNull(candidate);
        assertEquals("+55 65 9999-8888", candidate.getPhone());
    }

    @Test
    void contactMobileMapping() {
        var tags = objectMapper.createObjectNode();
        tags.put("contact:mobile", "+55 65 9999-8888");
        tags.put("name", "Barbearia");

        var element = objectMapper.createObjectNode();
        element.put("type", "node");
        element.put("id", "1");
        element.set("tags", tags);
        element.put("lat", -15.6);
        element.put("lon", -56.1);

        var scope = new GeoScope(-15.6, -56.1, "Cuiabá", "MT", "Brazil", "br", -15.7, -56.2, -15.5, -56.0, true, "relation", 333734L);
        LeadCandidate candidate = provider.mapElement(element, scope, true);

        assertNotNull(candidate);
        assertEquals("+55 65 9999-8888", candidate.getPhone());
    }

    @Test
    void mobileMapping() {
        var tags = objectMapper.createObjectNode();
        tags.put("mobile", "+55 65 9999-8888");
        tags.put("name", "Barbearia");

        var element = objectMapper.createObjectNode();
        element.put("type", "node");
        element.put("id", "1");
        element.set("tags", tags);
        element.put("lat", -15.6);
        element.put("lon", -56.1);

        var scope = new GeoScope(-15.6, -56.1, "Cuiabá", "MT", "Brazil", "br", -15.7, -56.2, -15.5, -56.0, true, "relation", 333734L);
        LeadCandidate candidate = provider.mapElement(element, scope, true);

        assertNotNull(candidate);
        assertEquals("+55 65 9999-8888", candidate.getPhone());
    }

    @Test
    void contactEmailMapping() {
        var tags = objectMapper.createObjectNode();
        tags.put("contact:email", "contato@barbearia.com");
        tags.put("name", "Barbearia");

        var element = objectMapper.createObjectNode();
        element.put("type", "node");
        element.put("id", "1");
        element.set("tags", tags);
        element.put("lat", -15.6);
        element.put("lon", -56.1);

        var scope = new GeoScope(-15.6, -56.1, "Cuiabá", "MT", "Brazil", "br", -15.7, -56.2, -15.5, -56.0, true, "relation", 333734L);
        LeadCandidate candidate = provider.mapElement(element, scope, true);

        assertNotNull(candidate);
        assertEquals("contato@barbearia.com", candidate.getEmail());
    }

    @Test
    void emailMapping() {
        var tags = objectMapper.createObjectNode();
        tags.put("email", "contato@barbearia.com");
        tags.put("name", "Barbearia");

        var element = objectMapper.createObjectNode();
        element.put("type", "node");
        element.put("id", "1");
        element.set("tags", tags);
        element.put("lat", -15.6);
        element.put("lon", -56.1);

        var scope = new GeoScope(-15.6, -56.1, "Cuiabá", "MT", "Brazil", "br", -15.7, -56.2, -15.5, -56.0, true, "relation", 333734L);
        LeadCandidate candidate = provider.mapElement(element, scope, true);

        assertNotNull(candidate);
        assertEquals("contato@barbearia.com", candidate.getEmail());
    }

    @Test
    void contactWebsiteMapping() {
        var tags = objectMapper.createObjectNode();
        tags.put("contact:website", "https://barbearia.com");
        tags.put("name", "Barbearia");

        var element = objectMapper.createObjectNode();
        element.put("type", "node");
        element.put("id", "1");
        element.set("tags", tags);
        element.put("lat", -15.6);
        element.put("lon", -56.1);

        var scope = new GeoScope(-15.6, -56.1, "Cuiabá", "MT", "Brazil", "br", -15.7, -56.2, -15.5, -56.0, true, "relation", 333734L);
        LeadCandidate candidate = provider.mapElement(element, scope, true);

        assertNotNull(candidate);
        assertEquals("https://barbearia.com", candidate.getWebsite());
    }

    @Test
    void websiteMapping() {
        var tags = objectMapper.createObjectNode();
        tags.put("website", "https://barbearia.com");
        tags.put("name", "Barbearia");

        var element = objectMapper.createObjectNode();
        element.put("type", "node");
        element.put("id", "1");
        element.set("tags", tags);
        element.put("lat", -15.6);
        element.put("lon", -56.1);

        var scope = new GeoScope(-15.6, -56.1, "Cuiabá", "MT", "Brazil", "br", -15.7, -56.2, -15.5, -56.0, true, "relation", 333734L);
        LeadCandidate candidate = provider.mapElement(element, scope, true);

        assertNotNull(candidate);
        assertEquals("https://barbearia.com", candidate.getWebsite());
    }

    @Test
    void urlMapping() {
        var tags = objectMapper.createObjectNode();
        tags.put("url", "https://barbearia.com");
        tags.put("name", "Barbearia");

        var element = objectMapper.createObjectNode();
        element.put("type", "node");
        element.put("id", "1");
        element.set("tags", tags);
        element.put("lat", -15.6);
        element.put("lon", -56.1);

        var scope = new GeoScope(-15.6, -56.1, "Cuiabá", "MT", "Brazil", "br", -15.7, -56.2, -15.5, -56.0, true, "relation", 333734L);
        LeadCandidate candidate = provider.mapElement(element, scope, true);

        assertNotNull(candidate);
        assertEquals("https://barbearia.com", candidate.getWebsite());
    }

    @Test
    void contactInstagramMapping() {
        var tags = objectMapper.createObjectNode();
        tags.put("contact:instagram", "@barbearia");
        tags.put("name", "Barbearia");

        var element = objectMapper.createObjectNode();
        element.put("type", "node");
        element.put("id", "1");
        element.set("tags", tags);
        element.put("lat", -15.6);
        element.put("lon", -56.1);

        var scope = new GeoScope(-15.6, -56.1, "Cuiabá", "MT", "Brazil", "br", -15.7, -56.2, -15.5, -56.0, true, "relation", 333734L);
        LeadCandidate candidate = provider.mapElement(element, scope, true);

        assertNotNull(candidate);
        assertEquals("barbearia", candidate.getInstagramUsername());
        assertEquals("https://instagram.com/barbearia", candidate.getInstagramUrl());
        assertEquals("FOUND", candidate.getInstagramStatus());
    }

    @Test
    void instagramMapping() {
        var tags = objectMapper.createObjectNode();
        tags.put("instagram", "barbearia");
        tags.put("name", "Barbearia");

        var element = objectMapper.createObjectNode();
        element.put("type", "node");
        element.put("id", "1");
        element.set("tags", tags);
        element.put("lat", -15.6);
        element.put("lon", -56.1);

        var scope = new GeoScope(-15.6, -56.1, "Cuiabá", "MT", "Brazil", "br", -15.7, -56.2, -15.5, -56.0, true, "relation", 333734L);
        LeadCandidate candidate = provider.mapElement(element, scope, true);

        assertNotNull(candidate);
        assertEquals("barbearia", candidate.getInstagramUsername());
        assertEquals("https://instagram.com/barbearia", candidate.getInstagramUrl());
        assertEquals("FOUND", candidate.getInstagramStatus());
    }

    @Test
    void instagramUrlMapping() {
        var tags = objectMapper.createObjectNode();
        tags.put("instagram", "https://instagram.com/barbearia");
        tags.put("name", "Barbearia");

        var element = objectMapper.createObjectNode();
        element.put("type", "node");
        element.put("id", "1");
        element.set("tags", tags);
        element.put("lat", -15.6);
        element.put("lon", -56.1);

        var scope = new GeoScope(-15.6, -56.1, "Cuiabá", "MT", "Brazil", "br", -15.7, -56.2, -15.5, -56.0, true, "relation", 333734L);
        LeadCandidate candidate = provider.mapElement(element, scope, true);

        assertNotNull(candidate);
        assertEquals("barbearia", candidate.getInstagramUsername());
        assertEquals("https://instagram.com/barbearia", candidate.getInstagramUrl());
        assertEquals("FOUND", candidate.getInstagramStatus());
    }

    @Test
    void addrCityMapping() {
        var tags = objectMapper.createObjectNode();
        tags.put("addr:city", "Cuiabá");
        tags.put("name", "Barbearia");

        var element = objectMapper.createObjectNode();
        element.put("type", "node");
        element.put("id", "1");
        element.set("tags", tags);
        element.put("lat", -15.6);
        element.put("lon", -56.1);

        var scope = new GeoScope(-15.6, -56.1, "Várzea Grande", "MT", "Brazil", "br", -15.7, -56.2, -15.5, -56.0, true, "relation", 333734L);
        LeadCandidate candidate = provider.mapElement(element, scope, true);

        assertNotNull(candidate);
        assertEquals("Cuiabá", candidate.getCity());
    }

    @Test
    void addrTownMapping() {
        var tags = objectMapper.createObjectNode();
        tags.put("addr:town", "Várzea Grande");
        tags.put("name", "Barbearia");

        var element = objectMapper.createObjectNode();
        element.put("type", "node");
        element.put("id", "1");
        element.set("tags", tags);
        element.put("lat", -15.6);
        element.put("lon", -56.1);

        var scope = new GeoScope(-15.6, -56.1, "Cuiabá", "MT", "Brazil", "br", -15.7, -56.2, -15.5, -56.0, true, "relation", 333734L);
        LeadCandidate candidate = provider.mapElement(element, scope, true);

        assertNotNull(candidate);
        assertEquals("Várzea Grande", candidate.getCity());
    }

    @Test
    void addrVillageMapping() {
        var tags = objectMapper.createObjectNode();
        tags.put("addr:village", "Poconé");
        tags.put("name", "Barbearia");

        var element = objectMapper.createObjectNode();
        element.put("type", "node");
        element.put("id", "1");
        element.set("tags", tags);
        element.put("lat", -15.6);
        element.put("lon", -56.1);

        var scope = new GeoScope(-15.6, -56.1, "Cuiabá", "MT", "Brazil", "br", -15.7, -56.2, -15.5, -56.0, true, "relation", 333734L);
        LeadCandidate candidate = provider.mapElement(element, scope, true);

        assertNotNull(candidate);
        assertEquals("Poconé", candidate.getCity());
    }

    @Test
    void addrMunicipalityMapping() {
        var tags = objectMapper.createObjectNode();
        tags.put("addr:municipality", "Poconé");
        tags.put("name", "Barbearia");

        var element = objectMapper.createObjectNode();
        element.put("type", "node");
        element.put("id", "1");
        element.set("tags", tags);
        element.put("lat", -15.6);
        element.put("lon", -56.1);

        var scope = new GeoScope(-15.6, -56.1, "Cuiabá", "MT", "Brazil", "br", -15.7, -56.2, -15.5, -56.0, true, "relation", 333734L);
        LeadCandidate candidate = provider.mapElement(element, scope, true);

        assertNotNull(candidate);
        assertEquals("Poconé", candidate.getCity());
    }

    @Test
    void addrStateMapping() {
        var tags = objectMapper.createObjectNode();
        tags.put("addr:state", "MT");
        tags.put("name", "Barbearia");

        var element = objectMapper.createObjectNode();
        element.put("type", "node");
        element.put("id", "1");
        element.set("tags", tags);
        element.put("lat", -15.6);
        element.put("lon", -56.1);

        var scope = new GeoScope(-15.6, -56.1, "Cuiabá", "MS", "Brazil", "br", -15.7, -56.2, -15.5, -56.0, true);
        LeadCandidate candidate = provider.mapElement(element, scope, true);

        assertNotNull(candidate);
        assertEquals("MT", candidate.getState());
    }

    @Test
    void addrCountryMapping() {
        var tags = objectMapper.createObjectNode();
        tags.put("addr:country", "Brasil");
        tags.put("name", "Barbearia");

        var element = objectMapper.createObjectNode();
        element.put("type", "node");
        element.put("id", "1");
        element.set("tags", tags);
        element.put("lat", -15.6);
        element.put("lon", -56.1);

        var scope = new GeoScope(-15.6, -56.1, "Cuiabá", "MT", "Brazil", "br", -15.7, -56.2, -15.5, -56.0, true, "relation", 333734L);
        LeadCandidate candidate = provider.mapElement(element, scope, true);

        assertNotNull(candidate);
        assertEquals("Brasil", candidate.getCountry());
    }
}