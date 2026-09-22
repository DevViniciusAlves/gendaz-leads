package com.gendaz.leads.service;

import com.gendaz.leads.entity.OsmCatalogRegion;
import com.gendaz.leads.entity.OsmSyncRun;
import com.gendaz.leads.entity.User;
import com.gendaz.leads.exception.ApiException;
import com.gendaz.leads.repository.OsmCatalogRegionRepository;
import com.gendaz.leads.repository.OsmSyncRunRepository;
import com.gendaz.leads.service.provider.BrazilGeofabrikRegionResolver;
import com.gendaz.leads.service.provider.GeoScope;
import com.gendaz.leads.service.provider.LeadDiscoveryRequest;
import com.gendaz.leads.service.provider.OpenStreetMapProvider;
import com.gendaz.leads.service.provider.DiscoveryBudget;
import com.gendaz.leads.util.CountryCodeResolver;
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
class OsmCatalogSyncServiceTest {

    @Mock
    private OsmCatalogRegionRepository regionRepository;
    @Mock
    private OsmSyncRunRepository syncRunRepository;
    @Mock
    private OpenStreetMapProvider osmProvider;
    @Mock
    private BrazilGeofabrikRegionResolver geofabrikResolver;
    @Mock
    private GitHubOsmSyncDispatcher githubDispatcher;

    private OsmCatalogSyncService service;

    @BeforeEach
    void setUp() throws Exception {
        service = new OsmCatalogSyncService(
                regionRepository,
                syncRunRepository,
                osmProvider,
                geofabrikResolver,
                githubDispatcher
        );
        // Enable catalog for tests
        var field = OsmCatalogSyncService.class.getDeclaredField("catalogEnabled");
        field.setAccessible(true);
        field.set(service, true);
    }

    @Test
    void requestSyncThrowsWhenCountryNotBrazil() {
        User user = new User();
        ApiException ex = assertThrows(ApiException.class, () ->
                service.requestSync("Cuiabá", "USA", user));
        
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
        assertEquals("OSM_CATALOG_COUNTRY_NOT_SUPPORTED", ex.getCode());
    }

    @Test
    void requestSyncThrowsWhenNoAdminBoundary() {
        User user = new User();
        
        GeoScope scope = mock(GeoScope.class);
        when(scope.hasAdminAreaCandidate()).thenReturn(false);
        when(osmProvider.resolveScope(any(LeadDiscoveryRequest.class), any(DiscoveryBudget.class)))
                .thenReturn(scope);
        
        ApiException ex = assertThrows(ApiException.class, () ->
                service.requestSync("Cuiabá", "Brasil", user));
        
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
        assertEquals("OSM_ADMIN_BOUNDARY_REQUIRED", ex.getCode());
    }

    @Test
    void requestSyncThrowsWhenGeofabrikRegionNotResolved() {
        User user = new User();
        
        GeoScope scope = mock(GeoScope.class);
        when(scope.hasAdminAreaCandidate()).thenReturn(true);
        when(scope.state()).thenReturn("Mato Grosso");
        when(osmProvider.resolveScope(any(LeadDiscoveryRequest.class), any(DiscoveryBudget.class)))
                .thenReturn(scope);
        when(geofabrikResolver.resolve("Mato Grosso")).thenThrow(new IllegalArgumentException("Unknown state"));
        
        ApiException ex = assertThrows(ApiException.class, () ->
                service.requestSync("Cuiabá", "Brasil", user));
        
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
        assertEquals("GEOFABRIK_REGION_NOT_RESOLVED", ex.getCode());
    }

    @Test
    void requestSyncThrowsWhenAlreadyRunning() {
        User user = new User();
        
        GeoScope scope = mock(GeoScope.class);
        when(scope.hasAdminAreaCandidate()).thenReturn(true);
        when(scope.state()).thenReturn("Mato Grosso");
        when(osmProvider.resolveScope(any(LeadDiscoveryRequest.class), any(DiscoveryBudget.class)))
                .thenReturn(scope);
        when(geofabrikResolver.resolve("Mato Grosso")).thenReturn("centro-oeste");
        
        OsmCatalogRegion region = new OsmCatalogRegion();
        region.setId(1L);
        region.setCatalogStatus("READY");
        when(regionRepository.findByNormalizedCityAndNormalizedStateAndCountryCode(
                anyString(), anyString(), anyString())).thenReturn(Optional.of(region));
        
        OsmSyncRun activeRun = new OsmSyncRun();
        activeRun.setStatus("RUNNING");
        when(syncRunRepository.findActiveByRegionId(eq(1L), anyList())).thenReturn(Optional.of(activeRun));
        
        ApiException ex = assertThrows(ApiException.class, () ->
                service.requestSync("Cuiabá", "Brasil", user));
        
        assertEquals(HttpStatus.CONFLICT, ex.getStatus());
        assertEquals("OSM_SYNC_ALREADY_RUNNING", ex.getCode());
    }

    @Test
    void requestSyncCreatesNewRegionAndRun() {
        User user = new User();
        user.setId(1L);
        
        GeoScope scope = mock(GeoScope.class);
        when(scope.hasAdminAreaCandidate()).thenReturn(true);
        when(scope.state()).thenReturn("Mato Grosso");
        when(scope.country()).thenReturn("Brasil");
        when(scope.osmType()).thenReturn("relation");
        when(scope.osmId()).thenReturn(12345L);
        when(osmProvider.resolveScope(any(LeadDiscoveryRequest.class), any(DiscoveryBudget.class)))
                .thenReturn(scope);
        when(geofabrikResolver.resolve("Mato Grosso")).thenReturn("centro-oeste");
        
        when(regionRepository.findByNormalizedCityAndNormalizedStateAndCountryCode(
                anyString(), anyString(), anyString())).thenReturn(Optional.empty());
        
        OsmCatalogRegion newRegion = new OsmCatalogRegion();
        newRegion.setId(1L);
        when(regionRepository.save(any(OsmCatalogRegion.class))).thenReturn(newRegion);
        
        OsmSyncRun newRun = new OsmSyncRun();
        newRun.setId(100L);
        when(syncRunRepository.save(any(OsmSyncRun.class))).thenReturn(newRun);
        
        OsmSyncRun result = service.requestSync("Cuiabá", "Brasil", user);
        
        assertEquals(100L, result.getId());
        assertEquals("QUEUED", result.getStatus());
        // regionRepository.save is called 3 times: initial save, osmType/osmId update, lastAttemptAt update
        verify(regionRepository, times(3)).save(any(OsmCatalogRegion.class));
        // First call should have EMPTY catalogStatus (at least once)
        verify(regionRepository, atLeastOnce()).save(argThat(r -> "EMPTY".equals(r.getCatalogStatus())));
    }
}