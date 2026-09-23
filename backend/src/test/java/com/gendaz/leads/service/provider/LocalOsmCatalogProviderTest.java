package com.gendaz.leads.service.provider;

import com.gendaz.leads.entity.OsmCatalogRegion;
import com.gendaz.leads.entity.OsmPlace;
import com.gendaz.leads.repository.OsmCatalogRegionRepository;
import com.gendaz.leads.repository.OsmPlaceRepository;
import com.gendaz.leads.util.Normalizer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;

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

    @Test
    void queryRequiresPhoneNotNullAndNotBlank() {
        OsmCatalogRegion region = new OsmCatalogRegion();
        region.setId(1L);
        region.setCatalogStatus("READY");
        when(regionRepository.findByNormalizedCityAndCountryCodeAndCatalogStatus(anyString(), anyString(), eq("READY")))
                .thenReturn(List.of(region));
        when(jdbcTemplate.query(anyString(), any(SqlParameterSource.class), any(RowMapper.class)))
                .thenReturn(Collections.emptyList());

        provider.discoverPage("barbearia", "Cuiabá", "Brasil", 1, 0);

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).query(sqlCaptor.capture(), any(SqlParameterSource.class), any(RowMapper.class));
        String sql = sqlCaptor.getValue();
        assertTrue(sql.contains("phone IS NOT NULL"), "SQL must require phone IS NOT NULL");
        assertTrue(sql.contains("BTRIM(phone) <> ''"), "SQL must require BTRIM(phone) <> ''");
        assertTrue(sql.contains("ORDER BY normalized_name, id"), "SQL must order by normalized_name, id");
        assertFalse(sql.contains("CASE WHEN NULLIF"), "SQL should not prioritize phone case");
    }

    @Test
    void nextOffsetAndHasMoreCorrect() {
        OsmCatalogRegion region = new OsmCatalogRegion();
        region.setId(1L);
        region.setCatalogStatus("READY");
        when(regionRepository.findByNormalizedCityAndCountryCodeAndCatalogStatus(anyString(), anyString(), eq("READY")))
                .thenReturn(List.of(region));

        // target 1 => pageLimit =30, return 30 rows => hasMore true
        List<OsmPlace> thirty = new ArrayList<>();
        for (int i=0;i<30;i++) {
            OsmPlace p = place(i, "Barbearia Teste "+i, "barbearia teste "+i, "{\"shop\":\"barber\"}", "+5565999991111");
            thirty.add(p);
        }
        when(jdbcTemplate.query(anyString(), any(SqlParameterSource.class), any(RowMapper.class)))
                .thenReturn(thirty);
        var page = provider.discoverPage("barbearia", "Cuiabá", "Brasil", 1, 0);
        assertEquals(30, page.rawRows());
        assertEquals(30, page.nextOffset());
        assertTrue(page.hasMore());

        // next page: less than limit => hasMore false
        OsmCatalogRegion region2 = new OsmCatalogRegion();
        region2.setId(1L);
        region2.setCatalogStatus("READY");
        when(regionRepository.findByNormalizedCityAndCountryCodeAndCatalogStatus(anyString(), anyString(), eq("READY")))
                .thenReturn(List.of(region2));
        List<OsmPlace> one = List.of(place(0,"Barbearia Teste","barbearia teste","{\"shop\":\"barber\"}","+5565999991111"));
        when(jdbcTemplate.query(anyString(), any(SqlParameterSource.class), any(RowMapper.class)))
                .thenReturn(one);
        var page2 = provider.discoverPage("barbearia", "Cuiabá", "Brasil", 1, 30);
        assertEquals(1, page2.rawRows());
        assertEquals(31, page2.nextOffset());
        assertFalse(page2.hasMore());
    }

    @Test
    void offset30ReturnsCorrectNextOffset() {
        OsmCatalogRegion region = new OsmCatalogRegion();
        region.setId(1L);
        region.setCatalogStatus("READY");
        when(regionRepository.findByNormalizedCityAndCountryCodeAndCatalogStatus(anyString(), anyString(), eq("READY")))
                .thenReturn(List.of(region));
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
            place.setTags("{\"shop\":\"barber\"}");
            page1Places.add(place);
        }
        when(jdbcTemplate.query(anyString(), any(SqlParameterSource.class), any(RowMapper.class)))
                .thenReturn(page1Places)
                .thenReturn(Collections.emptyList());
        var page1 = provider.discoverPage("barbearia", "Cuiabá", "Brasil", 30, 0);
        var page2 = provider.discoverPage("barbearia", "Cuiabá", "Brasil", 30, 30);
        assertEquals(30, page1.rawRows());
        assertEquals(30, page1.nextOffset());
        assertEquals(0, page2.rawRows());
    }

    @Test
    void rawRowsEqualsPageLimitHasMoreTrue() {
        OsmCatalogRegion region = new OsmCatalogRegion();
        region.setId(1L);
        region.setCatalogStatus("READY");
        when(regionRepository.findByNormalizedCityAndCountryCodeAndCatalogStatus(anyString(), anyString(), eq("READY")))
                .thenReturn(List.of(region));
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
            p.setTags("{\"shop\":\"barber\"}");
            manyPlaces.add(p);
        }
        when(jdbcTemplate.query(anyString(), any(SqlParameterSource.class), any(RowMapper.class)))
                .thenReturn(manyPlaces);
        var page = provider.discoverPage("barbearia", "Cuiabá", "Brasil", 30, 0);
        assertEquals(300, page.rawRows());
        assertTrue(page.hasMore());
    }

    @Test
    void rawRowsLessThanPageLimitHasMoreFalse() {
        OsmCatalogRegion region = new OsmCatalogRegion();
        region.setId(1L);
        region.setCatalogStatus("READY");
        when(regionRepository.findByNormalizedCityAndCountryCodeAndCatalogStatus(anyString(), anyString(), eq("READY")))
                .thenReturn(List.of(region));
        OsmPlace place = place(1,"Barbearia Teste","barbearia teste","{\"shop\":\"barber\"}","+5565999999999");
        when(jdbcTemplate.query(anyString(), any(SqlParameterSource.class), any(RowMapper.class)))
                .thenReturn(List.of(place));
        var page = provider.discoverPage("barbearia", "Cuiabá", "Brasil", 1, 0);
        assertEquals(1, page.rawRows());
        assertFalse(page.hasMore());
    }

    @Test
    void nailDesignerStrictShopBeautyNailsMatches() {
        NicheMapper.NicheStrategy strategy = NicheMapper.resolve("nail designer");
        OsmPlace p = place(1,"Nails Studio","nails studio","{\"shop\":\"beauty\",\"beauty\":\"nails\"}","+5565999991111");
        p.setNormalizedName("nails studio");
        assertTrue(provider.matchesStrategy(p, strategy));
    }

    @Test
    void nailDesignerShopBeautyAloneRejected() {
        NicheMapper.NicheStrategy strategy = NicheMapper.resolve("nail designer");
        OsmPlace p = place(1,"Beleza Pura","beleza pura","{\"shop\":\"beauty\"}","+5565999991111");
        assertFalse(provider.matchesStrategy(p, strategy));
    }

    @Test
    void nailDesignerShopClothesRejected() {
        NicheMapper.NicheStrategy strategy = NicheMapper.resolve("nail designer");
        OsmPlace p = place(1,"Loja Roupas","loja roupas","{\"shop\":\"clothes\"}","+5565999991111");
        assertFalse(provider.matchesStrategy(p, strategy));
    }

    @Test
    void nailDesignerFallbackNameMatches() {
        NicheMapper.NicheStrategy strategy = NicheMapper.resolve("nail designer");
        OsmPlace p = place(1,"Nail Designer Studio","nail designer studio","{\"shop\":\"clothes\"}","+5565999991111");
        // tags don't match but name contains fallback "nail designer" => should match
        assertTrue(provider.matchesStrategy(p, strategy));
    }

    @Test
    void barbeariaStrictShopBarberMatches() {
        NicheMapper.NicheStrategy strategy = NicheMapper.resolve("barbearia");
        OsmPlace p = place(1,"Barbearia Teste","barbearia teste","{\"shop\":\"barber\"}","+5565999991111");
        assertTrue(provider.matchesStrategy(p, strategy));
    }

    @Test
    void barbeariaShopHairdresserBarberMatches() {
        NicheMapper.NicheStrategy strategy = NicheMapper.resolve("barbearia");
        OsmPlace p = place(2,"Salao Teste","salao teste","{\"shop\":\"hairdresser\",\"hairdresser\":\"barber\"}","+5565999991111");
        assertTrue(provider.matchesStrategy(p, strategy));
    }

    @Test
    void barbeariaShopHairdresserAloneRejected() {
        NicheMapper.NicheStrategy strategy = NicheMapper.resolve("barbearia");
        OsmPlace p = place(3,"Salao Cabelo","salao cabelo","{\"shop\":\"hairdresser\"}","+5565999991111");
        // name not containing barbearia/barber
        p.setBusinessName("Salao Cabelo");
        p.setNormalizedName("salao cabelo");
        assertFalse(provider.matchesStrategy(p, strategy));
    }

    @Test
    void barbeariaFallbackNameMatches() {
        NicheMapper.NicheStrategy strategy = NicheMapper.resolve("barbearia");
        OsmPlace p = place(4,"Barbearia Central","barbearia central","{\"shop\":\"hairdresser\"}","+5565999991111");
        assertTrue(provider.matchesStrategy(p, strategy));
    }

    @Test
    void providerFiltersNicheMismatch() {
        OsmCatalogRegion region = new OsmCatalogRegion();
        region.setId(1L);
        region.setCatalogStatus("READY");
        when(regionRepository.findByNormalizedCityAndCountryCodeAndCatalogStatus(anyString(), anyString(), eq("READY")))
                .thenReturn(List.of(region));
        OsmPlace valid = place(1,"Nails Studio","nails studio","{\"shop\":\"beauty\",\"beauty\":\"nails\"}","+5565999991111");
        OsmPlace invalid = place(2,"Loja Roupas","loja roupas","{\"shop\":\"clothes\"}","+5565999991111");
        when(jdbcTemplate.query(anyString(), any(SqlParameterSource.class), any(RowMapper.class)))
                .thenReturn(List.of(valid, invalid));
        var page = provider.discoverPage("nail designer", "Cuiabá", "Brasil", 10, 0);
        // valid passes matchesStrategy, invalid fails => only 1 candidate
        assertEquals(2, page.rawRows());
        assertEquals(1, page.candidates().size());
        assertEquals("Nails Studio", page.candidates().get(0).getBusinessName());
    }

    private OsmPlace place(long id, String name, String normalized, String tags, String phone) {
        OsmPlace p = new OsmPlace();
        p.setId(id);
        p.setOsmType("node");
        p.setOsmId(100L + id);
        p.setBusinessName(name);
        p.setNormalizedName(normalized);
        p.setLatitude(-15.6);
        p.setLongitude(-56.1);
        p.setPhone(phone);
        p.setTags(tags);
        p.setCity("Cuiabá");
        p.setState("MT");
        p.setCountry("Brasil");
        p.setCountryCode("br");
        return p;
    }
}
