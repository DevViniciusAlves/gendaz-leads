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
    @Mock
    private OsmCatalogSyncStatusService syncStatusService;

    private OsmCatalogSyncService service;

    @BeforeEach
    void setUp() throws Exception {
        service = new OsmCatalogSyncService(
                regionRepository,
                syncRunRepository,
                osmProvider,
                geofabrikResolver,
                githubDispatcher,
                syncStatusService
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
                service.requestSync("Cuiabá", "USA", "barbearia", user));
        
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
                service.requestSync("Cuiabá", "Brasil", "barbearia", user));
        
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
                service.requestSync("Cuiabá", "Brasil", "barbearia", user));
        
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
                service.requestSync("Cuiabá", "Brasil", "barbearia", user));
        
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
        
        OsmSyncRun result = service.requestSync("Cuiabá", "Brasil", "barbearia", user);
        
        assertEquals(100L, result.getId());
        assertEquals("QUEUED", result.getStatus());
        // regionRepository.save is called 3 times: initial save, osmType/osmId update, lastAttemptAt update
        verify(regionRepository, times(3)).save(any(OsmCatalogRegion.class));
        // First call should have EMPTY catalogStatus (at least once)
        verify(regionRepository, atLeastOnce()).save(argThat(r -> "EMPTY".equals(r.getCatalogStatus())));
    }

    private OsmCatalogRegion existingCuiabaRegion() {
        OsmCatalogRegion region = new OsmCatalogRegion();
        region.setId(2L);
        region.setCity("Cuiabá");
        region.setNormalizedCity("cuiaba");
        region.setState("Mato Grosso");
        region.setNormalizedState("mato grosso");
        region.setCountry("Brasil");
        region.setCountryCode("br");
        region.setOsmType("relation");
        region.setOsmId(333734L);
        region.setGeofabrikRegion("centro-oeste");
        region.setCatalogStatus("READY");
        return region;
    }

    @Test
    void requestExistingRegionSyncDoesNotCallNominatim() {
        User user = new User();
        user.setId(1L);

        OsmCatalogRegion region = existingCuiabaRegion();
        when(regionRepository.findById(2L)).thenReturn(Optional.of(region));
        when(syncRunRepository.findActiveByRegionId(eq(2L), anyList())).thenReturn(Optional.empty());

        OsmSyncRun saved = new OsmSyncRun();
        saved.setId(200L);
        saved.setRegion(region);
        saved.setStatus("QUEUED");
        when(syncRunRepository.save(any(OsmSyncRun.class))).thenReturn(saved);

        OsmSyncRun result = service.requestExistingRegionSync(2L, "nail designer", user);

        assertEquals(200L, result.getId());
        verify(osmProvider, never()).resolveScope(any(), any());
    }

    @Test
    void requestExistingRegionSyncCreatesQueuedRunWithTarget50() {
        User user = new User();
        user.setId(1L);

        OsmCatalogRegion region = existingCuiabaRegion();
        when(regionRepository.findById(2L)).thenReturn(Optional.of(region));
        when(syncRunRepository.findActiveByRegionId(eq(2L), anyList())).thenReturn(Optional.empty());
        when(syncRunRepository.save(any(OsmSyncRun.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        OsmSyncRun result = service.requestExistingRegionSync(2L, "nail designer", user);

        assertEquals("QUEUED", result.getStatus());
        assertEquals(Integer.valueOf(50), result.getTargetValid());
        assertEquals("nail designer", result.getRequestedNiche());
        assertNotNull(result.getCanonicalNiche());
        assertFalse(result.getCanonicalNiche().isBlank());
        assertNotNull(result.getNicheStrategyJson());
        assertFalse(result.getNicheStrategyJson().isBlank());
        assertEquals(2L, result.getRegion().getId());
        verify(osmProvider, never()).resolveScope(any(), any());
    }

    @Test
    void requestExistingRegionSyncThrowsWhenRegionNotFound() {
        User user = new User();
        when(regionRepository.findById(999L)).thenReturn(Optional.empty());

        ApiException ex = assertThrows(ApiException.class, () ->
                service.requestExistingRegionSync(999L, "nail designer", user));

        assertEquals(HttpStatus.NOT_FOUND, ex.getStatus());
        assertEquals("OSM_REGION_NOT_FOUND", ex.getCode());
        verify(osmProvider, never()).resolveScope(any(), any());
    }

    @Test
    void requestExistingRegionSyncThrowsWhenOsmIdMissing() {
        User user = new User();

        OsmCatalogRegion region = existingCuiabaRegion();
        region.setOsmId(null);
        when(regionRepository.findById(2L)).thenReturn(Optional.of(region));

        ApiException ex = assertThrows(ApiException.class, () ->
                service.requestExistingRegionSync(2L, "nail designer", user));

        assertEquals(HttpStatus.CONFLICT, ex.getStatus());
        assertEquals("OSM_REGION_BOUNDARY_MISSING", ex.getCode());
        verify(osmProvider, never()).resolveScope(any(), any());
    }

    @Test
    void requestExistingRegionSyncThrowsWhenOsmTypeMissing() {
        User user = new User();

        OsmCatalogRegion region = existingCuiabaRegion();
        region.setOsmType("  ");
        when(regionRepository.findById(2L)).thenReturn(Optional.of(region));

        ApiException ex = assertThrows(ApiException.class, () ->
                service.requestExistingRegionSync(2L, "nail designer", user));

        assertEquals(HttpStatus.CONFLICT, ex.getStatus());
        assertEquals("OSM_REGION_BOUNDARY_MISSING", ex.getCode());
        verify(osmProvider, never()).resolveScope(any(), any());
    }

    @Test
    void requestExistingRegionSyncThrowsWhenGeofabrikMissing() {
        User user = new User();

        OsmCatalogRegion region = existingCuiabaRegion();
        region.setGeofabrikRegion(null);
        when(regionRepository.findById(2L)).thenReturn(Optional.of(region));

        ApiException ex = assertThrows(ApiException.class, () ->
                service.requestExistingRegionSync(2L, "nail designer", user));

        assertEquals(HttpStatus.CONFLICT, ex.getStatus());
        assertEquals("OSM_REGION_GEOFABRIK_MISSING", ex.getCode());
        verify(osmProvider, never()).resolveScope(any(), any());
    }

    @Test
    void requestExistingRegionSyncThrowsWhenAlreadyRunning() {
        User user = new User();

        OsmCatalogRegion region = existingCuiabaRegion();
        when(regionRepository.findById(2L)).thenReturn(Optional.of(region));

        OsmSyncRun activeRun = new OsmSyncRun();
        activeRun.setStatus("RUNNING");
        when(syncRunRepository.findActiveByRegionId(eq(2L), anyList())).thenReturn(Optional.of(activeRun));

        ApiException ex = assertThrows(ApiException.class, () ->
                service.requestExistingRegionSync(2L, "nail designer", user));

        assertEquals(HttpStatus.CONFLICT, ex.getStatus());
        assertEquals("OSM_SYNC_ALREADY_RUNNING", ex.getCode());
        verify(osmProvider, never()).resolveScope(any(), any());
    }

    @Test
    void requestSyncNewCityStillCallsResolveScopeAndPersistsMetadata() {
        User user = new User();
        user.setId(1L);

        GeoScope scope = mock(GeoScope.class);
        when(scope.hasAdminAreaCandidate()).thenReturn(true);
        when(scope.state()).thenReturn("Mato Grosso");
        when(scope.country()).thenReturn("Brasil");
        when(scope.osmType()).thenReturn("relation");
        when(scope.osmId()).thenReturn(333734L);
        when(osmProvider.resolveScope(any(LeadDiscoveryRequest.class), any(DiscoveryBudget.class)))
                .thenReturn(scope);
        when(geofabrikResolver.resolve("Mato Grosso")).thenReturn("centro-oeste");

        when(regionRepository.findByNormalizedCityAndNormalizedStateAndCountryCode(
                anyString(), anyString(), anyString())).thenReturn(Optional.empty());

        OsmCatalogRegion newRegion = new OsmCatalogRegion();
        newRegion.setId(2L);
        when(regionRepository.save(any(OsmCatalogRegion.class))).thenReturn(newRegion);

        OsmSyncRun newRun = new OsmSyncRun();
        newRun.setId(100L);
        when(syncRunRepository.save(any(OsmSyncRun.class))).thenReturn(newRun);

        service.requestSync("Cuiabá", "Brasil", "nail designer", user);

        verify(osmProvider, times(1)).resolveScope(any(LeadDiscoveryRequest.class), any(DiscoveryBudget.class));
        verify(regionRepository, atLeastOnce()).save(argThat(r ->
                "relation".equals(r.getOsmType())
                        && Long.valueOf(333734L).equals(r.getOsmId())
                        && "centro-oeste".equals(r.getGeofabrikRegion())));
    }
}