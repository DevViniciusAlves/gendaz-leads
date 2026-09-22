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

    private static final String EXPECTED_CONTACT_FILTER =
            "[~\"^(phone|contact:phone|mobile|contact:mobile|website|contact:website|url|email|contact:email|instagram|contact:instagram)$\"~\".+\"]";

    @BeforeEach
    void setUp() {
        restClientBuilder = mock(RestClient.Builder.class);
        objectMapper = new ObjectMapper();
        normalizer = new Normalizer();
        ssrfGuard = new SsrfGuard();
        provider = new OpenStreetMapProvider(restClientBuilder, objectMapper, normalizer, ssrfGuard);
    }

    @Test
    void buildStructuredQueryForBarbeariaUsesValidCompactContactFilter() {
        GeoScope scope = new GeoScope(-15.6, -56.1, "Cuiabá", "MT", "Brasil", "br", -15.7, -56.2, -15.5, -56.0, true, "relation", 333734L);
        SearchRegion region = SearchRegion.root(-15.7, -56.2, -15.5, -56.0, -15.6, -56.1);

        String query = provider.buildStructuredQuery("barbearia", scope, GeographicStrategy.ADMIN_AREA, region, 20, 30);

        assertNotNull(query);

        assertTrue(query.contains("[\"shop\"=\"barber\"]"));
        assertTrue(query.contains("[\"shop\"=\"hairdresser\"][\"hairdresser\"=\"barber\"]"));

        assertEquals(2, countOccurrences(query, EXPECTED_CONTACT_FILTER));

        assertFalse(query.contains("[\"~\""));

        assertTrue(query.contains("out center tags"));
    }

    @Test
    void buildNameFallbackQueryUsesValidCompactContactFilter() {
        GeoScope scope = new GeoScope(-15.6, -56.1, "Cuiabá", "MT", "Brasil", "br", -15.7, -56.2, -15.5, -56.0, true, "relation", 333734L);
        SearchRegion region = SearchRegion.root(-15.7, -56.2, -15.5, -56.0, -15.6, -56.1);

        String query = provider.buildNameFallbackQuery("barbearia", scope, GeographicStrategy.ADMIN_AREA, region, 20, 30);

        assertNotNull(query);

        assertTrue(query.contains("[\"name\"~\"barbearia|barber|barbershop\",i]"));

        assertEquals(1, countOccurrences(query, EXPECTED_CONTACT_FILTER));

        assertFalse(query.contains("[\"~\""));
    }

    @Test
    void buildStructuredQueryForOtherNicheStillWorks() {
        GeoScope scope = new GeoScope(-15.6, -56.1, "Cuiabá", "MT", "Brazil", "br", -15.7, -56.2, -15.5, -56.0, true);
        SearchRegion region = SearchRegion.root(-15.7, -56.2, -15.5, -56.0, -15.6, -56.1);

        String query = provider.buildStructuredQuery("dentista", scope, GeographicStrategy.BBOX_FALLBACK, region, 20, 30);

        assertNotNull(query);

        assertTrue(query.contains("[\"amenity\"=\"dentist\"]"));

        assertEquals(1, countOccurrences(query, EXPECTED_CONTACT_FILTER));

        assertFalse(query.contains("area("));
    }

    private static int countOccurrences(String text, String fragment) {
        int count = 0;
        int from = 0;

        while ((from = text.indexOf(fragment, from)) >= 0) {
            count++;
            from += fragment.length();
        }

        return count;
    }
}