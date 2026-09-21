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
    void buildStructuredQueryForBarbeariaGeneratesCompactContactFilter() {
        GeoScope scope = new GeoScope(-15.6, -56.1, "Cuiabá", "MT", "Brazil", "br", -15.7, -56.2, -15.5, -56.0, true, "relation", 333734L);
        SearchRegion region = SearchRegion.root(-15.7, -56.2, -15.5, -56.0, -15.6, -56.1);

        String query = provider.buildStructuredQuery("barbearia", scope, GeographicStrategy.ADMIN_AREA, region, 20, 30);

        assertNotNull(query);
        // Should contain the admin area preamble
        assertTrue(query.contains("area("));
        // Should contain the tag filters for barbearia (shop=barber and shop=hairdresser,hairdresser=barber)
        assertTrue(query.contains("[\"shop\"=\"barber\"]"));
        assertTrue(query.contains("[\"shop\"=\"hairdresser\"][\"hairdresser\"=\"barber\"]"));
        // Should contain the compact contact regex filter ONCE per niche filter (not once per contact key)
        // Count occurrences of contact key regex pattern ["~"^...]
        long contactRegexCount = query.split("\\[\"~\"\\^").length - 1;
        // Should have 2 contact regex filters (one for each niche filter), not 22 (2 niche filters × 11 contact keys)
        assertEquals(2, contactRegexCount, "Should have one contact regex per niche filter, not per contact key");
        // Should contain the contact keys regex
        assertTrue(query.contains("contact:phone"));
        assertTrue(query.contains("contact:mobile"));
        assertTrue(query.contains("contact:website"));
        assertTrue(query.contains("contact:email"));
        assertTrue(query.contains("contact:instagram"));
        // Should have proper output
        assertTrue(query.contains("out center tags"));
    }

@Test
    void buildNameFallbackQueryForBarbeariaGeneratesCompactContactFilter() {
        GeoScope scope = new GeoScope(-15.6, -56.1, "Cuiabá", "MT", "Brazil", "br", -15.7, -56.2, -15.5, -56.0, true, "relation", 333734L);
        SearchRegion region = SearchRegion.root(-15.7, -56.2, -15.5, -56.0, -15.6, -56.1);

        String query = provider.buildNameFallbackQuery("barbearia", scope, GeographicStrategy.ADMIN_AREA, region, 20, 30);

        assertNotNull(query);
        // Should contain the admin area preamble
        assertTrue(query.contains("area("));
        // Should contain the name regex for barbearia
        assertTrue(query.contains("name"));
        assertTrue(query.contains("barbearia|barber|barbershop"));
        // Should contain the compact contact regex filter ONCE (not once per contact key)
        // Count occurrences of contact key regex pattern ["~"^...]
        long contactRegexCount = query.split("\\[\"~\"\\^").length - 1;
        // Should have 1 contact regex filter for name fallback (single nwr block)
        assertEquals(1, contactRegexCount, "Should have one contact regex for name fallback, not per contact key");
        // Should contain the contact keys regex
        assertTrue(query.contains("contact:phone"));
        assertTrue(query.contains("contact:mobile"));
        assertTrue(query.contains("contact:website"));
        assertTrue(query.contains("contact:email"));
        assertTrue(query.contains("contact:instagram"));
        // Should have proper output
        assertTrue(query.contains("out center tags"));
    }

    @Test
    void buildStructuredQueryForOtherNicheStillWorks() {
        GeoScope scope = new GeoScope(-15.6, -56.1, "Cuiabá", "MT", "Brazil", "br", -15.7, -56.2, -15.5, -56.0, true);
        SearchRegion region = SearchRegion.root(-15.7, -56.2, -15.5, -56.0, -15.6, -56.1);

        String query = provider.buildStructuredQuery("dentista", scope, GeographicStrategy.BBOX_FALLBACK, region, 20, 30);

        assertNotNull(query);
        // Should contain the tag filter for dentist
        assertTrue(query.contains("[\"amenity\"=\"dentist\"]"));
        // Should contain the compact contact regex filter
        long contactRegexCount = query.chars().filter(ch -> ch == '~').count();
        assertEquals(1, contactRegexCount);
        // Should not have admin area preamble for bbox fallback
        assertFalse(query.contains("area("));
    }
}