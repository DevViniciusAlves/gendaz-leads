package com.gendaz.leads.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gendaz.leads.entity.OsmCatalogRegion;
import com.gendaz.leads.entity.OsmSyncRun;
import com.gendaz.leads.entity.User;
import com.gendaz.leads.exception.ApiException;
import com.gendaz.leads.repository.OsmCatalogRegionRepository;
import com.gendaz.leads.repository.OsmSyncRunRepository;
import com.gendaz.leads.service.provider.BrazilGeofabrikRegionResolver;
import com.gendaz.leads.service.provider.GeoScope;
import com.gendaz.leads.service.provider.LeadDiscoveryRequest;
import com.gendaz.leads.service.provider.LocalOsmCatalogProvider;
import com.gendaz.leads.service.provider.NicheMapper;
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
    private final OsmCatalogSyncStatusService syncStatusService;

    @Value("${app.osm-catalog.enabled:true}")
    private boolean catalogEnabled;

    public OsmCatalogSyncService(
            OsmCatalogRegionRepository regionRepository,
            OsmSyncRunRepository syncRunRepository,
            OpenStreetMapProvider osmProvider,
            BrazilGeofabrikRegionResolver geofabrikResolver,
            GitHubOsmSyncDispatcher githubDispatcher,
            OsmCatalogSyncStatusService syncStatusService
    ) {
        this.regionRepository = regionRepository;
        this.syncRunRepository = syncRunRepository;
        this.osmProvider = osmProvider;
        this.geofabrikResolver = geofabrikResolver;
        this.githubDispatcher = githubDispatcher;
        this.syncStatusService = syncStatusService;
    }

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Transactional
    public OsmSyncRun requestSync(String city, String country, String niche, User requestedBy) {
        if (!catalogEnabled) {
            throw new ApiException(HttpStatus.CONFLICT, "OSM_SYNC_DISABLED",
                    "A sincronização do catálogo OSM está desabilitada.");
        }

        String countryCode = CountryCodeResolver.resolveToIso2(country);
        if (!"br".equalsIgnoreCase(countryCode)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "OSM_CATALOG_COUNTRY_NOT_SUPPORTED",
                    "A sincronização local V1 suporta apenas Brasil. País informado: " + country);
        }

        // Resolve niche strategy and serialize
        NicheMapper.NicheStrategy strategy = NicheMapper.resolve(niche);
        String strategyJson;
        try {
            strategyJson = objectMapper.writeValueAsString(strategy);
        } catch (Exception e) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "OSM_NICHE_SERIALIZATION_FAILED",
                    "Não foi possível preparar a estratégia do nicho.");
        }

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
        syncRun.setRequestedNiche(niche.trim());
        syncRun.setCanonicalNiche(strategy.canonicalName());
        syncRun.setNicheStrategyJson(strategyJson);
        syncRun.setTargetValid(50);
        syncRun = syncRunRepository.save(syncRun);

        region.setLastAttemptAt(Instant.now());
        regionRepository.save(region);

        log.info("[osm-catalog] sync_requested syncRunId={} regionId={} city={} state={} countryCode={} osmType={} osmId={} geofabrikRegion={} canonicalNiche={} targetValid=50",
                syncRun.getId(), region.getId(), city, scope.state(), countryCode, scope.osmType(), scope.osmId(), geofabrikRegion, strategy.canonicalName());

        return syncRun;
    }

    @Transactional
    public OsmSyncRun requestExistingRegionSync(
            Long regionId,
            String niche,
            User requestedBy
    ) {
        if (!catalogEnabled) {
            throw new ApiException(HttpStatus.CONFLICT, "OSM_SYNC_DISABLED",
                    "A sincronização do catálogo OSM está desabilitada.");
        }

        OsmCatalogRegion region =
                regionRepository
                        .findById(regionId)
                        .orElseThrow(() ->
                                new ApiException(
                                        HttpStatus.NOT_FOUND,
                                        "OSM_REGION_NOT_FOUND",
                                        "Região OSM não encontrada."
                                )
                        );

        if (
                !"br".equalsIgnoreCase(
                        region.getCountryCode()
                )
        ) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "OSM_CATALOG_COUNTRY_NOT_SUPPORTED",
                    "A sincronização local V1 suporta apenas Brasil."
            );
        }

        if (
                region.getOsmType() == null
                        || region.getOsmType().isBlank()
                        || region.getOsmId() == null
                        || region.getOsmId() <= 0
        ) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "OSM_REGION_BOUNDARY_MISSING",
                    "A região não possui identificador OSM válido."
            );
        }

        if (
                region.getGeofabrikRegion() == null
                        || region.getGeofabrikRegion().isBlank()
        ) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "OSM_REGION_GEOFABRIK_MISSING",
                    "A região não possui mapeamento Geofabrik."
            );
        }

        // Resolve niche strategy and serialize (same logic as first-time sync)
        NicheMapper.NicheStrategy strategy = NicheMapper.resolve(niche);
        String strategyJson;
        try {
            strategyJson = objectMapper.writeValueAsString(strategy);
        } catch (Exception e) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "OSM_NICHE_SERIALIZATION_FAILED",
                    "Não foi possível preparar a estratégia do nicho.");
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
        syncRun.setRequestedNiche(niche.trim());
        syncRun.setCanonicalNiche(strategy.canonicalName());
        syncRun.setNicheStrategyJson(strategyJson);
        syncRun.setTargetValid(50);
        syncRun = syncRunRepository.save(syncRun);

        region.setLastAttemptAt(Instant.now());
        regionRepository.save(region);

        log.info("[osm-catalog] existing_region_sync_requested syncRunId={} regionId={} city={} state={} countryCode={} osmType={} osmId={} geofabrikRegion={} canonicalNiche={} targetValid=50",
                syncRun.getId(), region.getId(), region.getCity(), region.getState(), region.getCountryCode(),
                region.getOsmType(), region.getOsmId(), region.getGeofabrikRegion(), strategy.canonicalName());

        return syncRun;
    }

    public void dispatchSync(OsmSyncRun syncRun) {
        try {
            GeoScope scope = buildScopeFromSyncRun(syncRun);

            String geofabrikRegion =
                    syncRun.getRegion().getGeofabrikRegion();

            githubDispatcher.dispatch(
                    syncRun,
                    scope,
                    geofabrikRegion
            );

            log.info(
                    "[osm-catalog] sync_dispatched syncRunId={}",
                    syncRun.getId()
            );

        } catch (IllegalStateException e) {
            if ("OSM_SYNC_GITHUB_NOT_CONFIGURED".equals(e.getMessage())) {
                String message =
                        "Token do GitHub Actions não configurado.";

                syncStatusService.markDispatchFailed(
                        syncRun.getId(),
                        message
                );

                throw new ApiException(
                        HttpStatus.CONFLICT,
                        "OSM_SYNC_GITHUB_NOT_CONFIGURED",
                        "Configure OSM_SYNC_GITHUB_TOKEN antes de sincronizar."
                );
            }

            handleDispatchFailure(syncRun, e);

        } catch (Exception e) {
            handleDispatchFailure(syncRun, e);
        }
    }

    private void handleDispatchFailure(
            OsmSyncRun syncRun,
            Exception exception
    ) {
        String safeMessage =
                "Falha ao disparar workflow: "
                        + sanitizeError(exception.getMessage());

        log.error(
                "[osm-catalog] sync_dispatch_failed syncRunId={} error={}",
                syncRun.getId(),
                safeMessage
        );

        syncStatusService.markDispatchFailed(
                syncRun.getId(),
                safeMessage
        );

        throw new ApiException(
                HttpStatus.BAD_GATEWAY,
                "OSM_SYNC_DISPATCH_FAILED",
                "Não foi possível iniciar a sincronização. Tente novamente."
        );
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