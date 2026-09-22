package com.gendaz.leads.service.provider;

import com.gendaz.leads.domain.LeadCandidate;
import com.gendaz.leads.entity.OsmCatalogRegion;
import com.gendaz.leads.entity.OsmPlace;
import com.gendaz.leads.repository.OsmCatalogRegionRepository;
import com.gendaz.leads.repository.OsmPlaceRepository;
import com.gendaz.leads.util.Normalizer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class LocalOsmCatalogProviderTest {

    @Mock
    private OsmCatalogRegionRepository regionRepository;
    @Mock
    private OsmPlaceRepository placeRepository;
    @Mock
    private NamedParameterJdbcTemplate jdbcTemplate;
    @Mock
    private Normalizer normalizer;

    private LocalOsmCatalogProvider provider;

    @BeforeEach
    void setUp() {
        provider = new LocalOsmCatalogProvider(
                regionRepository,
                placeRepository,
                jdbcTemplate,
                normalizer,
                10,
                300
        );
    }

    @Test
    void getNameReturnsCorrectValue() {
        assertEquals("osm-local-catalog", provider.getName());
    }

    @Test
    void isEnabledReturnsTrue() {
        assertTrue(provider.isEnabled());
    }

    @Test
    void discoverThrowsWhenNoReadyRegion() {
        when(regionRepository.findByNormalizedCityAndCountryCodeAndCatalogStatus(
                anyString(), anyString(), eq("READY")))
                .thenReturn(Collections.emptyList());

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                provider.discover("barbearia", "Cuiabá", "Brasil", 10));

        assertTrue(ex.getMessage().contains("OSM_CATALOG_NOT_READY"));
    }

    @Test
    void discoverThrowsWhenMultipleReadyRegions() {
        OsmCatalogRegion region1 = new OsmCatalogRegion();
        region1.setId(1L);
        region1.setCatalogStatus("READY");

        OsmCatalogRegion region2 = new OsmCatalogRegion();
        region2.setId(2L);
        region2.setCatalogStatus("READY");

        when(regionRepository.findByNormalizedCityAndCountryCodeAndCatalogStatus(
                anyString(), anyString(), eq("READY")))
                .thenReturn(List.of(region1, region2));

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                provider.discover("barbearia", "Cuiabá", "Brasil", 10));

        assertTrue(ex.getMessage().contains("OSM_CATALOG_LOCATION_AMBIGUOUS"));
    }
}