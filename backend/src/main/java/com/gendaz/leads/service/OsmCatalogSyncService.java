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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Service
public class OsmCatalogSyncService {

    private static final Logger log = LoggerFactory.getLogger(OsmCatalogSyncService.class);

    private final OsmCatalogRegionRepository regionRepository;
    private final OsmSyncRunRepository syncRunRepository;
    private final OpenStreetMapProvider osmProvider;
    private final BrazilGeofabrikRegionResolver geofabrikResolver;
    private final GitHubOsmSyncDispatcher githubDispatcher;

    @Value("${app.osm-catalog.enabled:true}")
    private boolean catalogEnabled;

    public OsmCatalogSyncService(
            OsmCatalogRegionRepository regionRepository,
            OsmSyncRunRepository syncRunRepository,
            OpenStreetMapProvider osmProvider,
            BrazilGeofabrikRegionResolver geofabrikResolver,
            GitHubOsmSyncDispatcher githubDispatcher
    ) {
        this.regionRepository = regionRepository;
        this.syncRunRepository = syncRunRepository;
        this.osmProvider = osmProvider;
        this.geofabrikResolver = geofabrikResolver;
        this.githubDispatcher = githubDispatcher;
    }

    @Transactional
    public OsmSyncRun requestSync(String city, String country, User requestedBy) {
        if (!catalogEnabled) {
            throw new ApiException(HttpStatus.CONFLICT, "OSM_SYNC_DISABLED",
                    "A sincronização do catálogo OSM está desabilitada.");
        }

        String countryCode = CountryCodeResolver.resolveToIso2(country);
        if (!"br".equalsIgnoreCase(countryCode)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "OSM_CATALOG_COUNTRY_NOT_SUPPORTED",
                    "A sincronização local V1 suporta apenas Brasil. País informado: " + country);
        }

        // Check if catalog sync is enabled
        // This will be validated by the configuration

        DiscoveryBudget budget = DiscoveryBudget.unlimited();
        LeadDiscoveryRequest request = new LeadDiscoveryRequest(
                null, "", city, country, 0
        );
        GeoScope scope = osmProvider.resolveScope(request, budget);

        if (!scope.hasAdminAreaCandidate()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "OSM_ADMIN_BOUNDARY_REQUIRED",
                    "Não foi possível identificar o limite administrativo OSM desta cidade.");
        }

        String geofabrikRegion;
        try {
            geofabrikRegion = geofabrikResolver.resolve(scope.state());
        } catch (IllegalArgumentException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "GEOFABRIK_REGION_NOT_RESOLVED", e.getMessage());
        }

        String normalizedCity = normalizeForCompare(city);
        String normalizedState = normalizeForCompare(scope.state());

        OsmCatalogRegion region = regionRepository
                .findByNormalizedCityAndNormalizedStateAndCountryCode(normalizedCity, normalizedState, countryCode)
                .orElseGet(() -> {
                    OsmCatalogRegion r = new OsmCatalogRegion();
                    r.setCity(city);
                    r.setNormalizedCity(normalizedCity);
                    r.setState(scope.state());
                    r.setNormalizedState(normalizedState);
                    r.setCountry(scope.country());
                    r.setCountryCode(countryCode);
                    r.setOsmType(scope.osmType());
                    r.setOsmId(scope.osmId());
                    r.setGeofabrikRegion(geofabrikRegion);
                    r.setCatalogStatus("EMPTY");
                    return regionRepository.save(r);
                });

        if (region.getOsmType() == null || region.getOsmId() == null) {
            region.setOsmType(scope.osmType());
            region.setOsmId(scope.osmId());
            region.setGeofabrikRegion(geofabrikRegion);
            regionRepository.save(region);
        }

        List<String> activeStatuses = List.of("QUEUED", "RUNNING");
        Optional<OsmSyncRun> activeRun = syncRunRepository.findActiveByRegionId(region.getId(), activeStatuses);
        if (activeRun.isPresent()) {
            throw new ApiException(HttpStatus.CONFLICT, "OSM_SYNC_ALREADY_RUNNING",
                    "Já existe uma sincronização em andamento para esta região.");
        }

        OsmSyncRun syncRun = new OsmSyncRun();
        syncRun.setRegion(region);
        syncRun.setRequestedByUser(requestedBy);
        syncRun.setStatus("QUEUED");
        syncRun = syncRunRepository.save(syncRun);

        region.setLastAttemptAt(Instant.now());
        regionRepository.save(region);

        log.info("[osm-catalog] sync_requested syncRunId={} regionId={} city={} state={} countryCode={} osmType={} osmId={} geofabrikRegion={}",
                syncRun.getId(), region.getId(), city, scope.state(), countryCode, scope.osmType(), scope.osmId(), geofabrikRegion);

        return syncRun;
    }

    @Transactional
    public void dispatchSync(OsmSyncRun syncRun) {
        try {
            GeoScope scope = buildScopeFromSyncRun(syncRun);
            String geofabrikRegion = syncRun.getRegion().getGeofabrikRegion();
            githubDispatcher.dispatch(syncRun, scope, geofabrikRegion);
            syncRun.setStatus("QUEUED");
            syncRunRepository.save(syncRun);
        } catch (Exception e) {
            log.error("[osm-catalog] sync_dispatch_failed syncRunId={} error={}", syncRun.getId(), e.getMessage());
            syncRun.setStatus("FAILED");
            syncRun.setErrorMessage("Falha ao disparar workflow: " + sanitizeError(e.getMessage()));
            syncRun.setFinishedAt(Instant.now());
            syncRunRepository.save(syncRun);
            throw new ApiException(HttpStatus.BAD_GATEWAY, "OSM_SYNC_DISPATCH_FAILED",
                    "Não foi possível iniciar a sincronização. Tente novamente.");
        }
    }

    private GeoScope buildScopeFromSyncRun(OsmSyncRun syncRun) {
        OsmCatalogRegion region = syncRun.getRegion();
        // Build a minimal GeoScope for dispatch using bbox fallback
        return new GeoScope(
                0.0,
                0.0,
                region.getCity(),
                region.getState(),
                region.getCountry(),
                region.getCountryCode(),
                -0.18,
                -0.18,
                0.18,
                0.18,
                false,
                region.getOsmType(),
                region.getOsmId() != null ? region.getOsmId() : -1L
        );
    }

    public List<OsmCatalogRegion> listRegions() {
        return regionRepository.findAll();
    }

    public Optional<OsmSyncRun> getSyncRun(Long id) {
        return syncRunRepository.findById(id);
    }

    public List<OsmSyncRun> getSyncRunsForRegion(Long regionId) {
        return syncRunRepository.findByRegionIdOrderByCreatedAtDesc(regionId);
    }

    private String normalizeForCompare(String s) {
        if (s == null) return "";
        return java.text.Normalizer.normalize(s.trim().toLowerCase(), java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private String sanitizeError(String msg) {
        if (msg == null) return "Erro desconhecido";
        return msg.replaceAll("(?i)(password|token|secret|url|database_url|key)=[^\\s&]+", "$1=***");
    }
}