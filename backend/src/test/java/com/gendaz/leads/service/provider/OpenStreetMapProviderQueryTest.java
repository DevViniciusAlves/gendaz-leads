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

class OpenStreetMapProviderQueryTest {

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
    void buildStructuredQueryForBarbeariaDoesNotRequireContactTag() {
        GeoScope scope = new GeoScope(-15.6, -56.1, "Cuiabá", "MT", "Brasil", "br", -15.7, -56.2, -15.5, -56.0, true, "relation", 333734L);
        SearchRegion region = SearchRegion.root(-15.7, -56.2, -15.5, -56.0, -15.6, -56.1);

        String query = provider.buildStructuredQuery("barbearia", scope, GeographicStrategy.ADMIN_AREA, region, 20, 30);

        assertNotNull(query);

        assertTrue(query.contains("[\"shop\"=\"barber\"]"));
        assertTrue(query.contains("[\"shop\"=\"hairdresser\"][\"hairdresser\"=\"barber\"]"));

        assertFalse(query.contains("contact:phone"));
        assertFalse(query.contains("contact:website"));
        assertFalse(query.contains("contact:instagram"));

        assertTrue(query.contains("out center tags"));
        assertTrue(query.contains("area("));
    }

    @Test
    void buildNameFallbackQueryDoesNotRequireContactTag() {
        GeoScope scope = new GeoScope(-15.6, -56.1, "Cuiabá", "MT", "Brasil", "br", -15.7, -56.2, -15.5, -56.0, true, "relation", 333734L);
        SearchRegion region = SearchRegion.root(-15.7, -56.2, -15.5, -56.0, -15.6, -56.1);

        String query = provider.buildNameFallbackQuery("barbearia", scope, GeographicStrategy.ADMIN_AREA, region, 20, 30);

        assertNotNull(query);

        assertTrue(query.contains("[\"name\"~\"barbearia|barber|barbershop\",i]"));

        assertFalse(query.contains("contact:phone"));
        assertFalse(query.contains("contact:website"));

        assertTrue(query.contains("out center tags"));
        assertTrue(query.contains("area("));
    }

    @Test
    void buildStructuredQueryForOtherNicheStillWorks() {
        GeoScope scope = new GeoScope(-15.6, -56.1, "Cuiabá", "MT", "Brazil", "br", -15.7, -56.2, -15.5, -56.0, true);
        SearchRegion region = SearchRegion.root(-15.7, -56.2, -15.5, -56.0, -15.6, -56.1);

        String query = provider.buildStructuredQuery("dentista", scope, GeographicStrategy.BBOX_FALLBACK, region, 20, 30);

        assertNotNull(query);

        assertTrue(query.contains("[\"amenity\"=\"dentist\"]"));

        assertFalse(query.contains("contact:phone"));

        assertTrue(query.contains("out center tags"));
        assertFalse(query.contains("area("));
    }
}