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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;

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
                300,
                50.0
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

    @Test
    void baseShopBarberWithoutPhoneCompanionSameNameWithin50mRecoversPhone() {
        OsmCatalogRegion region = new OsmCatalogRegion();
        region.setId(1L);
        region.setCatalogStatus("READY");

        when(regionRepository.findByNormalizedCityAndCountryCodeAndCatalogStatus(
                anyString(), anyString(), eq("READY")))
                .thenReturn(List.of(region));

        OsmPlace base = new OsmPlace();
        base.setId(1L);
        base.setOsmType("node");
        base.setOsmId(100L);
        base.setBusinessName("Barbearia Teste");
        base.setNormalizedName("barbearia teste");
        base.setLatitude(-15.6);
        base.setLongitude(-56.1);
        base.setPhone(null);
        base.setTags("{}");

        OsmPlace companion = new OsmPlace();
        companion.setId(2L);
        companion.setOsmType("node");
        companion.setOsmId(101L);
        companion.setBusinessName("Barbearia Teste");
        companion.setNormalizedName("barbearia teste");
        companion.setLatitude(-15.6001);
        companion.setLongitude(-56.1001);
        companion.setPhone("+5565999999999");
        companion.setTags("{}");

        when(jdbcTemplate.query(anyString(), any(SqlParameterSource.class), any(RowMapper.class)))
                .thenReturn(List.of(base))
                .thenReturn(List.of(companion));

        LocalOsmCatalogProvider.CatalogPage page = provider.discoverPage("barbearia", "Cuiabá", "Brasil", 1, 0);

        assertEquals(1, page.candidates().size());
        assertEquals("+5565999999999", page.candidates().get(0).getPhone());
    }

    @Test
    void baseShopBarberWithoutPhoneCompanionSameNameOver50mDoesNotRecoverPhone() {
        OsmCatalogRegion region = new OsmCatalogRegion();
        region.setId(1L);
        region.setCatalogStatus("READY");

        when(regionRepository.findByNormalizedCityAndCountryCodeAndCatalogStatus(
                anyString(), anyString(), eq("READY")))
                .thenReturn(List.of(region));

        OsmPlace base = new OsmPlace();
        base.setId(1L);
        base.setOsmType("node");
        base.setOsmId(100L);
        base.setBusinessName("Barbearia Teste");
        base.setNormalizedName("barbearia teste");
        base.setLatitude(-15.6);
        base.setLongitude(-56.1);
        base.setPhone(null);
        base.setTags("{}");

        OsmPlace companion = new OsmPlace();
        companion.setId(2L);
        companion.setOsmType("node");
        companion.setOsmId(101L);
        companion.setBusinessName("Barbearia Teste");
        companion.setNormalizedName("barbearia teste");
        companion.setLatitude(-15.7);
        companion.setLongitude(-56.2);
        companion.setPhone("+5565999999999");
        companion.setTags("{}");

        when(jdbcTemplate.query(anyString(), any(SqlParameterSource.class), any(RowMapper.class)))
                .thenReturn(List.of(base))
                .thenReturn(List.of(companion));

        LocalOsmCatalogProvider.CatalogPage page = provider.discoverPage("barbearia", "Cuiabá", "Brasil", 1, 0);

        assertEquals(1, page.candidates().size());
        assertNull(page.candidates().get(0).getPhone());
    }

    @Test
    void offset30ReturnsCorrectNextOffset() {
        OsmCatalogRegion region = new OsmCatalogRegion();
        region.setId(1L);
        region.setCatalogStatus("READY");

        when(regionRepository.findByNormalizedCityAndCountryCodeAndCatalogStatus(
                anyString(), anyString(), eq("READY")))
                .thenReturn(List.of(region));

        // Create 30 places for first page
        List<OsmPlace> page1Places = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            OsmPlace place = new OsmPlace();
            place.setId((long) i);
            place.setOsmType("node");
            place.setOsmId(100L + i);
            place.setBusinessName("Barbearia Teste " + i);
            place.setNormalizedName("barbearia teste " + i);
            place.setLatitude(-15.6);
            place.setLongitude(-56.1);
            place.setPhone("+5565999999999");
            place.setTags("{}");
            page1Places.add(place);
        }

        // discoverPage makes 2 queries per call: basePlaces and companions
        // First call (offset=0): basePlaces returns 30 places, companions returns empty
        // Second call (offset=30): basePlaces returns empty
        when(jdbcTemplate.query(anyString(), any(SqlParameterSource.class), any(RowMapper.class)))
                .thenReturn(page1Places)  // basePlaces for page 1
                .thenReturn(Collections.emptyList())  // companions for page 1
                .thenReturn(Collections.emptyList()); // basePlaces for page 2 (empty)

        LocalOsmCatalogProvider.CatalogPage page1 = provider.discoverPage("barbearia", "Cuiabá", "Brasil", 30, 0);
        LocalOsmCatalogProvider.CatalogPage page2 = provider.discoverPage("barbearia", "Cuiabá", "Brasil", 30, 30);

        assertEquals(30, page1.rawRows());
        assertEquals(30, page1.nextOffset());
        assertEquals(0, page2.rawRows());
    }

    @Test
    void rawRowsEqualsPageLimitHasMoreTrue() {
        OsmCatalogRegion region = new OsmCatalogRegion();
        region.setId(1L);
        region.setCatalogStatus("READY");

        when(regionRepository.findByNormalizedCityAndCountryCodeAndCatalogStatus(
                anyString(), anyString(), eq("READY")))
                .thenReturn(List.of(region));

        OsmPlace place = new OsmPlace();
        place.setId(1L);
        place.setOsmType("node");
        place.setOsmId(100L);
        place.setBusinessName("Barbearia Teste");
        place.setNormalizedName("barbearia teste");
        place.setLatitude(-15.6);
        place.setLongitude(-56.1);
        place.setPhone("+5565999999999");
        place.setTags("{}");

        // target=30 gives pageLimit=300, we return 300 places = hasMore=true
        List<OsmPlace> manyPlaces = new ArrayList<>();
        for (int i = 0; i < 300; i++) {
            OsmPlace p = new OsmPlace();
            p.setId((long) i);
            p.setOsmType("node");
            p.setOsmId(100L + i);
            p.setBusinessName("Barbearia Teste " + i);
            p.setNormalizedName("barbearia teste " + i);
            p.setLatitude(-15.6);
            p.setLongitude(-56.1);
            p.setPhone("+5565999999999");
            p.setTags("{}");
            manyPlaces.add(p);
        }

        when(jdbcTemplate.query(anyString(), any(SqlParameterSource.class), any(RowMapper.class)))
                .thenReturn(manyPlaces)  // basePlaces
                .thenReturn(Collections.emptyList()); // companions

        LocalOsmCatalogProvider.CatalogPage page = provider.discoverPage("barbearia", "Cuiabá", "Brasil", 30, 0);

        assertEquals(300, page.rawRows());
        assertTrue(page.hasMore());
    }

    @Test
    void rawRowsLessThanPageLimitHasMoreFalse() {
        OsmCatalogRegion region = new OsmCatalogRegion();
        region.setId(1L);
        region.setCatalogStatus("READY");

        when(regionRepository.findByNormalizedCityAndCountryCodeAndCatalogStatus(
                anyString(), anyString(), eq("READY")))
                .thenReturn(List.of(region));

        OsmPlace place = new OsmPlace();
        place.setId(1L);
        place.setOsmType("node");
        place.setOsmId(100L);
        place.setBusinessName("Barbearia Teste");
        place.setNormalizedName("barbearia teste");
        place.setLatitude(-15.6);
        place.setLongitude(-56.1);
        place.setPhone("+5565999999999");
        place.setTags("{}");

        // Two queries: basePlaces and companions
        when(jdbcTemplate.query(anyString(), any(SqlParameterSource.class), any(RowMapper.class)))
                .thenReturn(List.of(place))  // basePlaces
                .thenReturn(Collections.emptyList()); // companions

        LocalOsmCatalogProvider.CatalogPage page = provider.discoverPage("barbearia", "Cuiabá", "Brasil", 1, 0);

        assertEquals(1, page.rawRows());
        assertFalse(page.hasMore());
    }
}