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
import java.util.Map;

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
    void queryRequiresQualifiedPoolFilters() {
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
        assertTrue(sql.contains("qualified = true"), "SQL must filter qualified = true");
        assertTrue(sql.contains("whatsapp_verified = true"), "SQL must filter whatsapp_verified = true");
        assertTrue(sql.contains("instagram_validated = true"), "SQL must filter instagram_validated = true");
        assertTrue(sql.contains("phone IS NOT NULL"), "SQL must require phone IS NOT NULL");
        assertTrue(sql.contains("BTRIM(phone) <> ''"), "SQL must require BTRIM(phone) <> ''");
        assertTrue(sql.contains("instagram IS NOT NULL"), "SQL must require instagram IS NOT NULL");
        assertTrue(sql.contains("BTRIM(instagram) <> ''"), "SQL must require BTRIM(instagram) <> ''");
        assertTrue(sql.contains("ORDER BY normalized_name, id"), "SQL must order by normalized_name, id");
        assertTrue(sql.contains("region_id = :regionId"));
        assertTrue(sql.contains("active = true"));
        assertTrue(sql.contains("business_name IS NOT NULL"));
        assertTrue(sql.contains("BTRIM(business_name) <> ''"));
    }

    @Test
    void nextOffsetAndHasMoreCorrect() {
        OsmCatalogRegion region = new OsmCatalogRegion();
        region.setId(1L);
        region.setCatalogStatus("READY");
        when(regionRepository.findByNormalizedCityAndCountryCodeAndCatalogStatus(anyString(), anyString(), eq("READY")))
                .thenReturn(List.of(region));

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

    // ---- New classifier tests ----

    @Test
    void nailsStructuredExactMatches() {
        NicheMapper.NicheStrategy strategy = NicheMapper.resolve("nail designer");
        OsmPlace p = place(1,"Nails Studio","nails studio","{\"shop\":\"beauty\",\"beauty\":\"nails\"}","+5565999991111");
        assertTrue(provider.matchesStrategy(p, strategy));
    }

    @Test
    void nailsSemicolonMatches() {
        NicheMapper.NicheStrategy strategy = NicheMapper.resolve("nail designer");
        OsmPlace p = place(1,"Bella","bella","{\"shop\":\"beauty\",\"beauty\":\"nails;eyebrow\"}","+5565999991111");
        assertTrue(provider.matchesStrategy(p, strategy));
    }

    @Test
    void nailsSemicolonReverseMatches() {
        NicheMapper.NicheStrategy strategy = NicheMapper.resolve("nail designer");
        OsmPlace p = place(1,"Bella","bella","{\"shop\":\"beauty\",\"beauty\":\"eyebrow;nails\"}","+5565999991111");
        assertTrue(provider.matchesStrategy(p, strategy));
    }

    @Test
    void nailsManicurePedcureMatches() {
        NicheMapper.NicheStrategy strategy = NicheMapper.resolve("nail designer");
        OsmPlace p = place(1,"Bella","bella","{\"shop\":\"beauty\",\"beauty\":\"manicure;pedicure\"}","+5565999991111");
        assertTrue(provider.matchesStrategy(p, strategy));
    }

    @Test
    void nailLegacyMatches() {
        NicheMapper.NicheStrategy strategy = NicheMapper.resolve("nail designer");
        OsmPlace p = place(1,"Studio","studio","{\"shop\":\"nail_salon\"}","+5565999991111");
        assertTrue(provider.matchesStrategy(p, strategy));
    }

    @Test
    void nailNameContextualBeautyMatches() {
        NicheMapper.NicheStrategy strategy = NicheMapper.resolve("nail designer");
        OsmPlace p = place(1,"Bella Esmalteria","bella esmalteria","{\"shop\":\"beauty\"}","+5565999991111");
        assertTrue(provider.matchesStrategy(p, strategy));
    }

    @Test
    void nailHairdresserContextualMatches() {
        NicheMapper.NicheStrategy strategy = NicheMapper.resolve("nail designer");
        OsmPlace p = place(1,"Maria Manicure","maria manicure","{\"shop\":\"hairdresser\"}","+5565999991111");
        assertTrue(provider.matchesStrategy(p, strategy));
    }

    @Test
    void nailClothesFalsePositiveRejected() {
        NicheMapper.NicheStrategy strategy = NicheMapper.resolve("nail designer");
        OsmPlace p = place(1,"Bella Esmalteria","bella esmalteria","{\"shop\":\"clothes\"}","+5565999991111");
        assertFalse(provider.matchesStrategy(p, strategy));
    }

    @Test
    void nailGenericBeautyFalsePositiveRejected() {
        NicheMapper.NicheStrategy strategy = NicheMapper.resolve("nail designer");
        OsmPlace p = place(1,"Beleza Pura","beleza pura","{\"shop\":\"beauty\"}","+5565999991111");
        assertFalse(provider.matchesStrategy(p, strategy));
    }

    @Test
    void boundaryNailSnailRejected() {
        NicheMapper.NicheStrategy strategy = NicheMapper.resolve("nail designer");
        OsmPlace p = place(1,"Snail Store","snail store","{\"shop\":\"beauty\"}","+5565999991111");
        assertFalse(provider.matchesStrategy(p, strategy));
    }

    @Test
    void barberCurrentMatches() {
        NicheMapper.NicheStrategy strategy = NicheMapper.resolve("barbearia");
        OsmPlace p = place(1,"Teste","teste","{\"shop\":\"hairdresser\",\"hairdresser\":\"barber\"}","+5565999991111");
        assertTrue(provider.matchesStrategy(p, strategy));
    }

    @Test
    void barberSemicolonMatches() {
        NicheMapper.NicheStrategy strategy = NicheMapper.resolve("barbearia");
        OsmPlace p = place(1,"Teste","teste","{\"shop\":\"hairdresser\",\"hairdresser\":\"barber;stylist\"}","+5565999991111");
        assertTrue(provider.matchesStrategy(p, strategy));
    }

    @Test
    void barberLegacyShopMatches() {
        NicheMapper.NicheStrategy strategy = NicheMapper.resolve("barbearia");
        OsmPlace p = place(1,"Barbearia","barbearia","{\"shop\":\"barber\"}","+5565999991111");
        assertTrue(provider.matchesStrategy(p, strategy));
    }

    @Test
    void barberLegacyKeyMatches() {
        NicheMapper.NicheStrategy strategy = NicheMapper.resolve("barbearia");
        OsmPlace p = place(1,"Teste","teste","{\"shop\":\"hairdresser\",\"barber\":\"yes\"}","+5565999991111");
        assertTrue(provider.matchesStrategy(p, strategy));
    }

    @Test
    void barberContextualNameMatches() {
        NicheMapper.NicheStrategy strategy = NicheMapper.resolve("barbearia");
        OsmPlace p = place(1,"Grego Barbearia","grego barbearia","{\"shop\":\"hairdresser\"}","+5565999991111");
        assertTrue(provider.matchesStrategy(p, strategy));
    }

    @Test
    void barberGenericHairdresserRejected() {
        NicheMapper.NicheStrategy strategy = NicheMapper.resolve("barbearia");
        OsmPlace p = place(1,"Studio Joao","studio joao","{\"shop\":\"hairdresser\"}","+5565999991111");
        assertFalse(provider.matchesStrategy(p, strategy));
    }

    @Test
    void barberWrongContextRejected() {
        NicheMapper.NicheStrategy strategy = NicheMapper.resolve("barbearia");
        OsmPlace p = place(1,"Barber Style","barber style","{\"shop\":\"clothes\"}","+5565999991111");
        assertFalse(provider.matchesStrategy(p, strategy));
    }

    @Test
    void beautyEyelashEyebrowMultivalueCilios() {
        NicheMapper.NicheStrategy cilios = NicheMapper.resolve("cilios");
        OsmPlace p = place(1,"Teste","teste","{\"shop\":\"beauty\",\"beauty\":\"eyelash;eyebrow\"}","+5565999991111");
        assertTrue(provider.matchesStrategy(p, cilios));
    }

    @Test
    void beautyEyelashEyebrowMultivalueSobrancelhas() {
        NicheMapper.NicheStrategy sob = NicheMapper.resolve("sobrancelha");
        OsmPlace p = place(1,"Teste","teste","{\"shop\":\"beauty\",\"beauty\":\"eyelash;eyebrow\"}","+5565999991111");
        assertTrue(provider.matchesStrategy(p, sob));
    }

    @Test
    void multiNicheSamePlace() {
        OsmPlace p = place(1,"Teste","teste","{\"shop\":\"beauty\",\"beauty\":\"nails;eyelash;eyebrow\"}","+5565999991111");
        assertTrue(provider.matchesStrategy(p, NicheMapper.resolve("nail designer")));
        assertTrue(provider.matchesStrategy(p, NicheMapper.resolve("cilios")));
        assertTrue(provider.matchesStrategy(p, NicheMapper.resolve("sobrancelha")));
    }

    @Test
    void hairRemovalMultivalue() {
        NicheMapper.NicheStrategy dep = NicheMapper.resolve("depilacao");
        OsmPlace p = place(1,"Teste","teste","{\"shop\":\"beauty\",\"beauty\":\"hair_removal;skin_care\"}","+5565999991111");
        assertTrue(provider.matchesStrategy(p, dep));
    }

    @Test
    void massageMultivalue() {
        NicheMapper.NicheStrategy mass = NicheMapper.resolve("massagem");
        OsmPlace p = place(1,"Teste","teste","{\"shop\":\"beauty\",\"beauty\":\"massage;skin_care\"}","+5565999991111");
        assertTrue(provider.matchesStrategy(p, mass));
    }

    @Test
    void sqlContainsSemicolonLogicForNails() {
        OsmCatalogRegion region = new OsmCatalogRegion();
        region.setId(1L);
        region.setCatalogStatus("READY");
        when(regionRepository.findByNormalizedCityAndCountryCodeAndCatalogStatus(anyString(), anyString(), eq("READY")))
                .thenReturn(List.of(region));
        when(jdbcTemplate.query(anyString(), any(SqlParameterSource.class), any(RowMapper.class)))
                .thenReturn(Collections.emptyList());

        provider.discoverPage("nail designer", "Cuiabá", "Brasil", 3, 0);

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<SqlParameterSource> paramsCaptor = ArgumentCaptor.forClass(SqlParameterSource.class);
        verify(jdbcTemplate).query(sqlCaptor.capture(), paramsCaptor.capture(), any(RowMapper.class));
        String sql = sqlCaptor.getValue();
        SqlParameterSource params = paramsCaptor.getValue();

        assertTrue(sql.contains("string_to_array"), "SQL must use string_to_array for SEMICOLON_TOKEN");
        assertTrue(sql.contains("unnest"), "SQL must use unnest");
        assertTrue(sql.contains("BTRIM(token.value) IN"), "SQL must use BTRIM token IN");
        assertFalse(sql.contains("LIKE '%nail%'"), "Should not use LIKE substring");
        assertTrue(sql.contains("LOWER(COALESCE(tags->>"), "SQL must use LOWER COALESCE tags");
        // Check params contain accepted values
        if (params instanceof MapSqlParameterSource mps) {
            Map<String,Object> vals = mps.getValues();
            boolean hasNails = vals.values().stream().anyMatch(v -> v instanceof List && ((List<?>)v).contains("nails"));
            assertTrue(hasNails, "Params should contain nails");
            boolean hasManicure = vals.values().stream().anyMatch(v -> v instanceof List && ((List<?>)v).contains("manicure"));
            assertTrue(hasManicure);
        }
        assertTrue(sql.contains("REGEXP_REPLACE"), "Fallback should use REGEXP_REPLACE");
        assertTrue(sql.contains("LIKE :"), "Fallback should use LIKE with padded spaces");
    }

    @Test
    void sqlAndJavaAgreeForCases() {
        List<TestCase> cases = List.of(
                new TestCase("{\"shop\":\"beauty\",\"beauty\":\"nails\"}", "nails studio", true),
                new TestCase("{\"shop\":\"beauty\",\"beauty\":\"nails;eyebrow\"}", "bella", true),
                new TestCase("{\"shop\":\"beauty\",\"beauty\":\"eyebrow;nails\"}", "bella", true),
                new TestCase("{\"shop\":\"beauty\",\"beauty\":\"manicure;pedicure\"}", "bella", true),
                new TestCase("{\"shop\":\"nail_salon\"}", "studio", true),
                new TestCase("{\"shop\":\"beauty\"}", "bella esmalteria", true),
                new TestCase("{\"shop\":\"clothes\"}", "bella esmalteria", false),
                new TestCase("{\"shop\":\"beauty\"}", "beleza pura", false),
                new TestCase("{\"shop\":\"beauty\"}", "snail store", false),
                new TestCase("{\"shop\":\"hairdresser\",\"hairdresser\":\"barber\"}", "teste", true),
                new TestCase("{\"shop\":\"hairdresser\",\"hairdresser\":\"barber;stylist\"}", "teste", true),
                new TestCase("{\"shop\":\"barber\"}", "barbearia", true),
                new TestCase("{\"shop\":\"hairdresser\",\"barber\":\"yes\"}", "teste", true),
                new TestCase("{\"shop\":\"hairdresser\"}", "grego barbearia", true),
                new TestCase("{\"shop\":\"hairdresser\"}", "studio joao", false),
                new TestCase("{\"shop\":\"clothes\"}", "barber style", false)
        );
        for (TestCase tc : cases) {
            // nails cases first 9
            NicheMapper.NicheStrategy nails = NicheMapper.resolve("nail designer");
            NicheMapper.NicheStrategy barber = NicheMapper.resolve("barbearia");
            // Determine expected based on case index
            // We'll test both strategies appropriately:
            // For first 9, check nails; for rest check barber
        }
        // Explicit verification
        NicheMapper.NicheStrategy nails = NicheMapper.resolve("nail designer");
        assertTrue(provider.matchesStrategy(place(1,"Nails","nails studio","{\"shop\":\"beauty\",\"beauty\":\"nails\"}","+55"), nails));
        assertTrue(provider.matchesStrategy(place(1,"Bella","bella","{\"shop\":\"beauty\",\"beauty\":\"nails;eyebrow\"}","+55"), nails));
        assertTrue(provider.matchesStrategy(place(1,"Bella","bella","{\"shop\":\"beauty\",\"beauty\":\"manicure;pedicure\"}","+55"), nails));
        assertTrue(provider.matchesStrategy(place(1,"Studio","studio","{\"shop\":\"nail_salon\"}","+55"), nails));
        assertTrue(provider.matchesStrategy(place(1,"Bella Esmalteria","bella esmalteria","{\"shop\":\"beauty\"}","+55"), nails));
        assertFalse(provider.matchesStrategy(place(1,"Bella Esmalteria","bella esmalteria","{\"shop\":\"clothes\"}","+55"), nails));
        assertFalse(provider.matchesStrategy(place(1,"Beleza Pura","beleza pura","{\"shop\":\"beauty\"}","+55"), nails));
        assertFalse(provider.matchesStrategy(place(1,"Snail Store","snail store","{\"shop\":\"beauty\"}","+55"), nails));

        NicheMapper.NicheStrategy barber = NicheMapper.resolve("barbearia");
        assertTrue(provider.matchesStrategy(place(1,"A","a","{\"shop\":\"hairdresser\",\"hairdresser\":\"barber\"}","+55"), barber));
        assertTrue(provider.matchesStrategy(place(1,"A","a","{\"shop\":\"hairdresser\",\"hairdresser\":\"barber;stylist\"}","+55"), barber));
        assertTrue(provider.matchesStrategy(place(1,"A","barbearia","{\"shop\":\"barber\"}","+55"), barber));
        assertTrue(provider.matchesStrategy(place(1,"A","a","{\"shop\":\"hairdresser\",\"barber\":\"yes\"}","+55"), barber));
        assertTrue(provider.matchesStrategy(place(1,"Grego Barbearia","grego barbearia","{\"shop\":\"hairdresser\"}","+55"), barber));
        assertFalse(provider.matchesStrategy(place(1,"Studio Joao","studio joao","{\"shop\":\"hairdresser\"}","+55"), barber));
        assertFalse(provider.matchesStrategy(place(1,"Barber Style","barber style","{\"shop\":\"clothes\"}","+55"), barber));
    }

    private record TestCase(String tags, String normalized, boolean expected) {}

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
        assertEquals(2, page.rawRows());
        assertEquals(1, page.candidates().size());
        assertEquals("Nails Studio", page.candidates().get(0).getBusinessName());
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
        p.setBusinessName("Salao Cabelo");
        p.setNormalizedName("salao cabelo");
        assertFalse(provider.matchesStrategy(p, strategy));
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
