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
        // hairdresser token barber should be regex semicolon token
        assertTrue(query.contains("hairdresser") && query.contains("barber") && query.contains("(^|;)"));
        assertTrue(query.contains("[\"barber\"=\"yes\"]"));

        assertFalse(query.contains("contact:phone"));
        assertFalse(query.contains("contact:website"));
        assertFalse(query.contains("contact:instagram"));

        assertTrue(query.contains("out center tags"));
        assertTrue(query.contains("area("));
    }

    @Test
    void buildStructuredQueryForNailsContainsSemicolonToken() {
        GeoScope scope = new GeoScope(-15.6, -56.1, "Cuiabá", "MT", "Brasil", "br", -15.7, -56.2, -15.5, -56.0, true, "relation", 333734L);
        SearchRegion region = SearchRegion.root(-15.7, -56.2, -15.5, -56.0, -15.6, -56.1);

        String query = provider.buildStructuredQuery("nail designer", scope, GeographicStrategy.ADMIN_AREA, region, 20, 30);

        assertNotNull(query);
        // should contain beauty token with nails|manicure|pedicure
        assertTrue(query.contains("beauty"));
        assertTrue(query.contains("nails"));
        assertTrue(query.contains("manicure"));
        assertTrue(query.contains("pedicure"));
        assertTrue(query.contains("(^|;)"));
        assertTrue(query.contains("[\"shop\"=\"beauty\"]"));
        assertTrue(query.contains("[\"shop\"=\"nail_salon\"]"));
        assertTrue(query.contains("out center tags"));
    }

    @Test
    void buildNameFallbackQueryForBarberIsContextual() {
        GeoScope scope = new GeoScope(-15.6, -56.1, "Cuiabá", "MT", "Brasil", "br", -15.7, -56.2, -15.5, -56.0, true, "relation", 333734L);
        SearchRegion region = SearchRegion.root(-15.7, -56.2, -15.5, -56.0, -15.6, -56.1);

        String query = provider.buildNameFallbackQuery("barbearia", scope, GeographicStrategy.ADMIN_AREA, region, 20, 30);

        assertNotNull(query);
        // Should contain contextual nwr with shop hairdresser and shop barber plus name regex
        assertTrue(query.contains("[\"shop\"=\"hairdresser\"]"));
        assertTrue(query.contains("[\"shop\"=\"barber\"]"));
        assertTrue(query.contains("[\"name\"~\""));
        assertTrue(query.contains("barbearia"));
        assertTrue(query.contains("barber"));
        assertTrue(query.contains("barbershop"));
        // Should NOT be a single global name regex without context
        // Count nwr occurrences: should be 2 (one per context)
        int nwrCount = query.split("nwr").length - 1;
        assertEquals(2, nwrCount, "Barber fallback should have 2 contextual nwr queries");
        assertTrue(query.contains("out center tags"));
    }

    @Test
    void buildNameFallbackQueryForNailsIsContextual() {
        GeoScope scope = new GeoScope(-15.6, -56.1, "Cuiabá", "MT", "Brasil", "br", -15.7, -56.2, -15.5, -56.0, true, "relation", 333734L);
        SearchRegion region = SearchRegion.root(-15.7, -56.2, -15.5, -56.0, -15.6, -56.1);

        String query = provider.buildNameFallbackQuery("nail designer", scope, GeographicStrategy.ADMIN_AREA, region, 20, 30);

        assertNotNull(query);
        // Should have 3 contextual queries: beauty, hairdresser, nail_salon
        int nwrCount = query.split("nwr").length - 1;
        assertEquals(3, nwrCount, "Nails fallback should have 3 contextual nwr queries");
        assertTrue(query.contains("[\"shop\"=\"beauty\"]"));
        assertTrue(query.contains("[\"shop\"=\"hairdresser\"]"));
        assertTrue(query.contains("[\"shop\"=\"nail_salon\"]"));
        assertTrue(query.contains("esmalteria"));
        assertTrue(query.contains("nail"));
        assertTrue(query.contains("manicure"));
        assertFalse(query.contains("shop=clothes"));
    }

    @Test
    void buildNameFallbackQueryDoesNotRequireContactTag() {
        GeoScope scope = new GeoScope(-15.6, -56.1, "Cuiabá", "MT", "Brasil", "br", -15.7, -56.2, -15.5, -56.0, true, "relation", 333734L);
        SearchRegion region = SearchRegion.root(-15.7, -56.2, -15.5, -56.0, -15.6, -56.1);

        String query = provider.buildNameFallbackQuery("barbearia", scope, GeographicStrategy.ADMIN_AREA, region, 20, 30);

        assertNotNull(query);
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

    @Test
    void buildStructuredQueryUnknownNicheReturnsNull() {
        GeoScope scope = new GeoScope(-15.6, -56.1, "Cuiabá", "MT", "Brasil", "br", -15.7, -56.2, -15.5, -56.0, true);
        SearchRegion region = SearchRegion.root(-15.7, -56.2, -15.5, -56.0, -15.6, -56.1);
        String query = provider.buildStructuredQuery("unknownniche123", scope, GeographicStrategy.BBOX_FALLBACK, region, 20, 30);
        assertNull(query);
    }

    @Test
    void buildNameFallbackForUnknownIsSimpleNameRegex() {
        GeoScope scope = new GeoScope(-15.6, -56.1, "Cuiabá", "MT", "Brasil", "br", -15.7, -56.2, -15.5, -56.0, true);
        SearchRegion region = SearchRegion.root(-15.7, -56.2, -15.5, -56.0, -15.6, -56.1);
        String query = provider.buildNameFallbackQuery("unknownniche123", scope, GeographicStrategy.BBOX_FALLBACK, region, 20, 30);
        assertNotNull(query);
        assertTrue(query.contains("unknownniche123"));
        // No context, so single nwr
        int nwrCount = query.split("nwr").length - 1;
        assertEquals(1, nwrCount);
    }
}
