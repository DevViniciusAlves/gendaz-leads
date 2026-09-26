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
import com.gendaz.leads.service.provider.GeoScope;
import com.gendaz.leads.service.provider.LeadDiscoveryRequest;
import com.gendaz.leads.service.provider.OpenStreetMapProvider;
import com.gendaz.leads.service.provider.DiscoveryBudget;
import com.gendaz.leads.util.CountryCodeResolver;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.Mockito;
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
    @Mock
    private EntityManager entityManager;
    @Mock
    private OsmCatalogTargetRepository targetRepository;

    private OsmCatalogSyncService service;

    @BeforeEach
    void setUp() throws Exception {
        service = new OsmCatalogSyncService(
                regionRepository,
                syncRunRepository,
                osmProvider,
                geofabrikResolver,
                githubDispatcher,
                syncStatusService,
                entityManager,
                targetRepository
        );
        // Enable catalog for tests
        var field = OsmCatalogSyncService.class.getDeclaredField("catalogEnabled");
        field.setAccessible(true);
        field.set(service, true);

        // Default: no existing target, return new target on save (lenient to avoid unnecessary stubbing errors)
        Mockito.lenient().when(targetRepository.findByRegionIdAndCanonicalNiche(anyLong(), anyString()))
                .thenReturn(Optional.empty());
        Mockito.lenient().when(targetRepository.saveAndFlush(any(OsmCatalogTarget.class)))
                .thenAnswer(inv -> inv.getArgument(0));
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
        when(scope.osmType()).thenReturn("relation");
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
        when(scope.osmType()).thenReturn("relation");
        when(osmProvider.resolveScope(any(LeadDiscoveryRequest.class), any(DiscoveryBudget.class)))
                .thenReturn(scope);
        when(geofabrikResolver.resolve("Mato Grosso")).thenReturn("centro-oeste");
        
        OsmCatalogRegion region = new OsmCatalogRegion();
        region.setId(1L);
        region.setCatalogStatus("READY");
        region.setCountryCode("br");
        region.setOsmType("relation");
        region.setOsmId(333734L);
        region.setGeofabrikRegion("centro-oeste");
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
        when(osmProvider.resolveScope(any(LeadDiscoveryRequest.class), any(DiscoveryBudget.class)))
                .thenReturn(scope);
        when(geofabrikResolver.resolve("Mato Grosso")).thenReturn("centro-oeste");
        
        when(regionRepository.findByNormalizedCityAndNormalizedStateAndCountryCode(
                anyString(), anyString(), anyString())).thenReturn(Optional.empty());
        
        OsmCatalogRegion newRegion = new OsmCatalogRegion();
        newRegion.setId(1L);
        newRegion.setCountryCode("br");
        newRegion.setOsmType("relation");
        newRegion.setOsmId(12345L);
        newRegion.setGeofabrikRegion("centro-oeste");
        when(regionRepository.saveAndFlush(any(OsmCatalogRegion.class))).thenReturn(newRegion);
        // save() legado nao e mais usado no fluxo; stub defensivo
        when(regionRepository.save(any(OsmCatalogRegion.class))).thenReturn(newRegion);

        OsmSyncRun newRun = new OsmSyncRun();
        newRun.setId(100L);
        when(syncRunRepository.saveAndFlush(any(OsmSyncRun.class))).thenReturn(newRun);

        OsmSyncRun result = service.requestSync("Cuiabá", "Brasil", "barbearia", user);

        assertEquals(100L, result.getId());
        assertEquals("QUEUED", result.getStatus());
        // region create via saveAndFlush once + lastAttemptAt save once
        verify(regionRepository, times(1)).saveAndFlush(any(OsmCatalogRegion.class));
        verify(regionRepository, times(1)).save(any(OsmCatalogRegion.class));
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
        when(syncRunRepository.saveAndFlush(any(OsmSyncRun.class))).thenReturn(saved);

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
        when(syncRunRepository.saveAndFlush(any(OsmSyncRun.class)))
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
        newRegion.setCountryCode("br");
        newRegion.setOsmType("relation");
        newRegion.setOsmId(333734L);
        newRegion.setGeofabrikRegion("centro-oeste");
        when(regionRepository.saveAndFlush(any(OsmCatalogRegion.class))).thenReturn(newRegion);

        OsmSyncRun newRun = new OsmSyncRun();
        newRun.setId(100L);
        when(syncRunRepository.saveAndFlush(any(OsmSyncRun.class))).thenReturn(newRun);

        service.requestSync("Cuiabá", "Brasil", "nail designer", user);

        verify(osmProvider, times(1)).resolveScope(any(LeadDiscoveryRequest.class), any(DiscoveryBudget.class));
        verify(regionRepository, atLeastOnce()).saveAndFlush(argThat(r ->
                "relation".equals(r.getOsmType())
                        && Long.valueOf(333734L).equals(r.getOsmId())
                        && "centro-oeste".equals(r.getGeofabrikRegion())));
    }

    @Test
    void requestSyncNewCityWithNonRelationScopeReturns409() {
        User user = new User();

        GeoScope scope = mock(GeoScope.class);
        when(scope.hasAdminAreaCandidate()).thenReturn(true);
        when(scope.osmType()).thenReturn("way");
        when(osmProvider.resolveScope(any(LeadDiscoveryRequest.class), any(DiscoveryBudget.class)))
                .thenReturn(scope);

        ApiException ex = assertThrows(ApiException.class, () ->
                service.requestSync("Cuiabá", "Brasil", "nail designer", user));

        assertEquals(HttpStatus.CONFLICT, ex.getStatus());
        assertEquals("OSM_REGION_RELATION_REQUIRED", ex.getCode());
    }

    @Test
    void requestSyncGenericWithExistingCityReusesRegionWithoutNominatim() {
        User user = new User();
        user.setId(1L);

        OsmCatalogRegion region = existingCuiabaRegion();
        when(regionRepository.findByNormalizedCityAndCountryCode("cuiaba", "br"))
                .thenReturn(List.of(region));
        when(syncRunRepository.findActiveByRegionId(eq(2L), anyList())).thenReturn(Optional.empty());
        when(syncRunRepository.saveAndFlush(any(OsmSyncRun.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        OsmSyncRun result = service.requestSync("Cuiabá", "Brasil", "nail designer", user);

        assertEquals("QUEUED", result.getStatus());
        assertEquals(Integer.valueOf(50), result.getTargetValid());
        assertEquals(2L, result.getRegion().getId());
        verify(osmProvider, never()).resolveScope(any(), any());
        verify(geofabrikResolver, never()).resolve(anyString());
    }

    @Test
    void requestSyncGenericWithAmbiguousCityReturns409WithoutNominatim() {
        User user = new User();

        OsmCatalogRegion r1 = existingCuiabaRegion();
        OsmCatalogRegion r2 = existingCuiabaRegion();
        r2.setId(3L);
        when(regionRepository.findByNormalizedCityAndCountryCode("cuiaba", "br"))
                .thenReturn(List.of(r1, r2));

        ApiException ex = assertThrows(ApiException.class, () ->
                service.requestSync("Cuiabá", "Brasil", "nail designer", user));

        assertEquals(HttpStatus.CONFLICT, ex.getStatus());
        assertEquals("OSM_REGION_AMBIGUOUS", ex.getCode());
        verify(osmProvider, never()).resolveScope(any(), any());
    }

    @Test
    void requestExistingRegionSyncThrowsWhenNotRelation() {
        User user = new User();

        OsmCatalogRegion region = existingCuiabaRegion();
        region.setOsmType("way");
        when(regionRepository.findById(2L)).thenReturn(Optional.of(region));

        ApiException ex = assertThrows(ApiException.class, () ->
                service.requestExistingRegionSync(2L, "nail designer", user));

        assertEquals(HttpStatus.CONFLICT, ex.getStatus());
        assertEquals("OSM_REGION_RELATION_REQUIRED", ex.getCode());
        verify(osmProvider, never()).resolveScope(any(), any());
    }

    @Test
    void requestExistingRegionSyncRaceOnSaveBecomes409() {
        User user = new User();

        OsmCatalogRegion region = existingCuiabaRegion();
        when(regionRepository.findById(2L)).thenReturn(Optional.of(region));
        when(syncRunRepository.findActiveByRegionId(eq(2L), anyList())).thenReturn(Optional.empty());
        when(syncRunRepository.saveAndFlush(any(OsmSyncRun.class)))
                .thenThrow(new org.springframework.dao.DataIntegrityViolationException("uq active run"));

        ApiException ex = assertThrows(ApiException.class, () ->
                service.requestExistingRegionSync(2L, "nail designer", user));

        assertEquals(HttpStatus.CONFLICT, ex.getStatus());
        assertEquals("OSM_SYNC_ALREADY_RUNNING", ex.getCode());
        verify(osmProvider, never()).resolveScope(any(), any());
    }

    @Test
    void requestSyncNewCityRegionRaceReloadsExisting() {
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

        when(regionRepository.findByNormalizedCityAndCountryCode(anyString(), anyString()))
                .thenReturn(List.of());
        when(regionRepository.findByNormalizedCityAndNormalizedStateAndCountryCode(
                anyString(), anyString(), anyString()))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(existingCuiabaRegion()));
        when(regionRepository.saveAndFlush(any(OsmCatalogRegion.class)))
                .thenThrow(new org.springframework.dao.DataIntegrityViolationException("uq region"));
        when(syncRunRepository.findActiveByRegionId(eq(2L), anyList())).thenReturn(Optional.empty());
        when(syncRunRepository.saveAndFlush(any(OsmSyncRun.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        OsmSyncRun result = service.requestSync("Cuiabá", "Brasil", "nail designer", user);

        assertEquals("QUEUED", result.getStatus());
        assertEquals(2L, result.getRegion().getId());
        verify(entityManager).clear();
    }

    @Test
    void requestExistingRegionSyncClearsStaleLastError() {
        User user = new User();

        OsmCatalogRegion region = existingCuiabaRegion();
        region.setLastError("Falha antiga");
        when(regionRepository.findById(2L)).thenReturn(Optional.of(region));
        when(syncRunRepository.findActiveByRegionId(eq(2L), anyList())).thenReturn(Optional.empty());
        when(syncRunRepository.saveAndFlush(any(OsmSyncRun.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        service.requestExistingRegionSync(2L, "nail designer", user);

        verify(regionRepository).save(argThat(r -> r.getLastError() == null));
    }
}