package com.gendaz.leads.service;

import com.gendaz.leads.entity.OsmCatalogRegion;
import com.gendaz.leads.entity.OsmCatalogTarget;
import com.gendaz.leads.repository.OsmCatalogRegionRepository;
import com.gendaz.leads.repository.OsmCatalogTargetRepository;
import com.gendaz.leads.util.Normalizer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class QualifiedLeadPoolServiceTest {

    @Mock OsmCatalogRegionRepository regionRepository;
    @Mock OsmCatalogTargetRepository targetRepository;
    @Mock NamedParameterJdbcTemplate jdbcTemplate;
    @Mock Normalizer normalizer;

    private QualifiedLeadPoolService service;

    @BeforeEach
    void setUp() {
        service = new QualifiedLeadPoolService(regionRepository, targetRepository, jdbcTemplate,
                normalizer, 10, 300);
    }

    private OsmCatalogRegion region() {
        OsmCatalogRegion r = new OsmCatalogRegion();
        r.setId(2L);
        r.setCity("Cuiabá");
        r.setCatalogStatus("READY");
        return r;
    }

    @Test
    void resolveTargetThrowsWhenTargetMissing() {
        when(regionRepository.findByNormalizedCityAndCountryCode(
                anyString(), anyString())).thenReturn(List.of(region()));
        when(targetRepository.findByRegionIdAndCanonicalNiche(eq(2L), anyString()))
                .thenReturn(Optional.empty());

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.resolveTarget("nail designer", "Cuiabá", "Brasil"));
        assertTrue(ex.getMessage().contains("OSM_TARGET_NOT_FOUND"));
        assertTrue(ex.getMessage().contains("Sincronize esse nicho"));
    }

    @Test
    void resolveTargetReturnsTargetWithoutNicheRematch() {
        OsmCatalogTarget t = new OsmCatalogTarget();
        t.setId(10L);
        t.setRegion(region());
        t.setCanonicalNiche("nails");
        when(regionRepository.findByNormalizedCityAndCountryCode(
                anyString(), anyString())).thenReturn(List.of(region()));
        when(targetRepository.findByRegionIdAndCanonicalNiche(eq(2L), eq("nails")))
                .thenReturn(Optional.of(t));

        QualifiedLeadPoolService.ResolvedTarget resolved =
                service.resolveTarget("NAIL DESIGNER", "Cuiabá", "Brasil");
        assertEquals(10L, resolved.target().getId());
        assertEquals("nails", resolved.canonicalNiche());
    }
}
