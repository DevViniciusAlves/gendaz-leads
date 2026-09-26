package com.gendaz.leads.service;

import com.gendaz.leads.entity.OsmCatalogRegion;
import com.gendaz.leads.entity.OsmCatalogTarget;
import com.gendaz.leads.entity.OsmSyncRun;
import com.gendaz.leads.entity.User;
import com.gendaz.leads.exception.ApiException;
import com.gendaz.leads.repository.OsmCatalogRegionRepository;
import com.gendaz.leads.repository.OsmCatalogTargetRepository;
import com.gendaz.leads.repository.OsmSyncRunRepository;
import com.gendaz.leads.service.provider.BrazilGeofabrikRegionResolver;
import com.gendaz.leads.service.provider.OpenStreetMapProvider;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OsmTargetServiceTest {

    @Mock OsmCatalogRegionRepository regionRepository;
    @Mock OsmCatalogTargetRepository targetRepository;
    @Mock OsmSyncRunRepository syncRunRepository;
    @Mock OpenStreetMapProvider osmProvider;
    @Mock BrazilGeofabrikRegionResolver geofabrikResolver;
    @Mock EntityManager entityManager;

    private OsmTargetService service;

    @BeforeEach
    void setUp() {
        service = new OsmTargetService(regionRepository, targetRepository, syncRunRepository,
                osmProvider, geofabrikResolver, entityManager);
    }

    private OsmCatalogRegion cuiaba() {
        OsmCatalogRegion r = new OsmCatalogRegion();
        r.setId(2L);
        r.setCity("Cuiabá");
        r.setNormalizedCity("cuiaba");
        r.setState("Mato Grosso");
        r.setCountry("Brasil");
        r.setCountryCode("br");
        r.setOsmType("relation");
        r.setOsmId(333734L);
        r.setGeofabrikRegion("centro-oeste");
        return r;
    }

    private OsmCatalogTarget target(OsmCatalogRegion r) {
        OsmCatalogTarget t = new OsmCatalogTarget();
        t.setId(10L);
        t.setRegion(r);
        t.setRequestedNiche("NAIL DESIGNER");
        t.setCanonicalNiche("nails");
        t.setTargetValid(50);
        return t;
    }

    @Test
    void resyncUsesStoredTargetWithoutNominatim() {
        OsmCatalogRegion r = cuiaba();
        OsmCatalogTarget t = target(r);
        when(targetRepository.findById(10L)).thenReturn(Optional.of(t));
        when(syncRunRepository.findActiveByTargetId(eq(10L), anyList())).thenReturn(Optional.empty());
        when(syncRunRepository.findActiveByRegionId(eq(2L), anyList())).thenReturn(Optional.empty());
        when(syncRunRepository.saveAndFlush(any(OsmSyncRun.class))).thenAnswer(inv -> inv.getArgument(0));

        OsmSyncRun run = service.requestTargetResync(10L, new User(), null);

        assertEquals("QUEUED", run.getStatus());
        assertEquals(Integer.valueOf(50), run.getTargetValid());
        assertEquals(10L, run.getTarget().getId());
        verify(osmProvider, never()).resolveScope(any(), any());
    }

    @Test
    void resyncSameIdempotencyKeyReturnsSameRun() {
        OsmSyncRun existing = new OsmSyncRun();
        existing.setId(99L);
        when(targetRepository.findById(10L)).thenReturn(Optional.of(target(cuiaba())));
        when(syncRunRepository.findByRequestKey("abc-12345")).thenReturn(Optional.of(existing));

        OsmSyncRun run = service.requestTargetResync(10L, new User(), "abc-12345");

        assertEquals(99L, run.getId());
        verify(syncRunRepository, never()).saveAndFlush(any());
        verify(osmProvider, never()).resolveScope(any(), any());
    }

    @Test
    void resyncDifferentKeyDuringActiveRunReturns409() {
        OsmCatalogRegion r = cuiaba();
        when(targetRepository.findById(10L)).thenReturn(Optional.of(target(r)));
        when(syncRunRepository.findActiveByTargetId(eq(10L), anyList()))
                .thenReturn(Optional.of(new OsmSyncRun()));

        ApiException ex = assertThrows(ApiException.class,
                () -> service.requestTargetResync(10L, new User(), "other-key-123"));
        assertEquals(HttpStatus.CONFLICT, ex.getStatus());
        assertEquals("OSM_SYNC_ALREADY_RUNNING", ex.getCode());
    }

    @Test
    void newSyncSameCityNewNicheReusesRegionWithoutNominatim() {
        OsmCatalogRegion r = cuiaba();
        when(regionRepository.findByNormalizedCityAndCountryCode("cuiaba", "br"))
                .thenReturn(List.of(r));
        OsmCatalogTarget t = target(r);
        t.setCanonicalNiche("barber");
        when(targetRepository.findByRegionIdAndCanonicalNiche(eq(2L), eq("barber")))
                .thenReturn(Optional.of(t));
        when(syncRunRepository.findActiveByTargetId(eq(10L), anyList())).thenReturn(Optional.empty());
        when(syncRunRepository.findActiveByRegionId(eq(2L), anyList())).thenReturn(Optional.empty());
        when(syncRunRepository.saveAndFlush(any(OsmSyncRun.class))).thenAnswer(inv -> inv.getArgument(0));

        OsmSyncRun run = service.requestNewTargetSync("Cuiabá", "Brasil", "barbearia", new User(), null);

        assertEquals("QUEUED", run.getStatus());
        verify(osmProvider, never()).resolveScope(any(), any());
    }
}
