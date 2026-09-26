package com.gendaz.leads.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gendaz.leads.dto.osm.OsmCatalogTargetResponse;
import com.gendaz.leads.entity.OsmCatalogRegion;
import com.gendaz.leads.entity.OsmCatalogTarget;
import com.gendaz.leads.entity.OsmSyncRun;
import com.gendaz.leads.entity.User;
import com.gendaz.leads.exception.ApiException;
import com.gendaz.leads.repository.OsmCatalogRegionRepository;
import com.gendaz.leads.repository.OsmCatalogTargetRepository;
import com.gendaz.leads.repository.OsmSyncRunRepository;
import com.gendaz.leads.service.provider.BrazilGeofabrikRegionResolver;
import com.gendaz.leads.service.provider.DiscoveryBudget;
import com.gendaz.leads.service.provider.GeoScope;
import com.gendaz.leads.service.provider.LeadDiscoveryRequest;
import com.gendaz.leads.service.provider.NicheMapper;
import com.gendaz.leads.service.provider.OpenStreetMapProvider;
import com.gendaz.leads.util.CountryCodeResolver;
import jakarta.persistence.EntityManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Single owner of target (cidade+nicho) lifecycle.
 * Resync usa SEMPRE o target persistido: zero Nominatim, zero modal.
 * Serializacao por regiao e intencional (mesmo PBF Geofabrik/boundary por cidade).
 */
@Service
public class OsmTargetService {

    private static final Logger log = LoggerFactory.getLogger(OsmTargetService.class);
    private static final List<String> ACTIVE = List.of("QUEUED", "RUNNING");

    private final OsmCatalogRegionRepository regionRepository;
    private final OsmCatalogTargetRepository targetRepository;
    private final OsmSyncRunRepository syncRunRepository;
    private final OpenStreetMapProvider osmProvider;
    private final BrazilGeofabrikRegionResolver geofabrikResolver;
    private final EntityManager entityManager;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public OsmTargetService(
            OsmCatalogRegionRepository regionRepository,
            OsmCatalogTargetRepository targetRepository,
            OsmSyncRunRepository syncRunRepository,
            OpenStreetMapProvider osmProvider,
            BrazilGeofabrikRegionResolver geofabrikResolver,
            EntityManager entityManager) {
        this.regionRepository = regionRepository;
        this.targetRepository = targetRepository;
        this.syncRunRepository = syncRunRepository;
        this.osmProvider = osmProvider;
        this.geofabrikResolver = geofabrikResolver;
        this.entityManager = entityManager;
    }

    @Transactional(readOnly = true)
    public List<OsmCatalogTargetResponse> listTargets() {
        List<OsmCatalogTarget> targets = targetRepository.findAllByOrderByUpdatedAtDesc();
        return targets.stream().map(t -> {
            List<OsmSyncRun> runs = syncRunRepository.findByTargetIdOrderByCreatedAtDesc(t.getId());
            OsmSyncRun latest = runs.isEmpty() ? null : runs.get(0);
            // Fallback legado: run por regiao sem target_id (antes do backfill)
            if (latest == null) {
                List<OsmSyncRun> legacy = syncRunRepository.findByRegionIdOrderByCreatedAtDesc(t.getRegion().getId());
                latest = legacy.stream()
                        .filter(r -> t.getCanonicalNiche().equals(r.getCanonicalNiche()))
                        .findFirst().orElse(null);
            }
            return OsmCatalogTargetResponse.from(t, latest);
        }).collect(Collectors.toList());
    }

    @Transactional
    public OsmSyncRun requestNewTargetSync(String city, String country, String niche, User requestedBy, String requestKey) {
        String countryCode = CountryCodeResolver.resolveToIso2(country);
        if (!"br".equalsIgnoreCase(countryCode)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "OSM_CATALOG_COUNTRY_NOT_SUPPORTED",
                    "A sincronização local suporta apenas Brasil.");
        }
        if (city == null || city.isBlank() || niche == null || niche.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "OSM_SYNC_INVALID_INPUT",
                    "Cidade e nicho são obrigatórios.");
        }
        String key = normalizeKey(requestKey);
        if (key != null) {
            Optional<OsmSyncRun> existing = syncRunRepository.findByRequestKey(key);
            if (existing.isPresent()) return existing.get();
        }

        NicheMapper.NicheStrategy strategy = NicheMapper.resolve(niche);
        String normalizedCity = normalizeForCompare(city);

        // Cidade conhecida: zero Nominatim, reusa region.
        List<OsmCatalogRegion> existingRegions =
                regionRepository.findByNormalizedCityAndCountryCode(normalizedCity, countryCode);
        OsmCatalogRegion region;
        if (existingRegions.size() == 1) {
            region = existingRegions.get(0);
            log.info("[osm-target] region_reused city={} regionId={}", city, region.getId());
        } else if (existingRegions.size() > 1) {
            throw new ApiException(HttpStatus.CONFLICT, "OSM_REGION_AMBIGUOUS",
                    "Há mais de uma região com este nome. Informe uma localização mais específica.");
        } else {
            region = resolveAndPersistNewRegion(city, country, normalizedCity, countryCode);
        }

        OsmCatalogTarget target = getOrCreateTarget(region, niche.trim(), strategy.canonicalName());
        return createRunForTarget(target, requestedBy, key);
    }

    @Transactional
    public OsmSyncRun requestTargetResync(Long targetId, User requestedBy, String requestKey) {
        OsmCatalogTarget target = targetRepository.findById(targetId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "OSM_TARGET_NOT_FOUND",
                        "Sincronização não encontrada. Crie uma nova sincronização."));
        if (target.getCanonicalNiche() == null || target.getCanonicalNiche().isBlank()
                || target.getRegion() == null) {
            throw new ApiException(HttpStatus.CONFLICT, "OSM_TARGET_CONFIGURATION_REQUIRED",
                    "Esta sincronização antiga precisa ser configurada novamente.");
        }
        String key = normalizeKey(requestKey);
        if (key != null) {
            Optional<OsmSyncRun> existing = syncRunRepository.findByRequestKey(key);
            if (existing.isPresent()) return existing.get();
        }
        // ZERO resolveScope(), ZERO Nominatim: usa target persistido.
        return createRunForTarget(target, requestedBy, key);
    }

    @Transactional
    public OsmCatalogTarget getOrCreateTarget(OsmCatalogRegion region, String requestedNiche, String canonicalNiche) {
        String canonical = canonicalNiche == null || canonicalNiche.isBlank() ? "unknown" : canonicalNiche.trim();
        Optional<OsmCatalogTarget> existing = targetRepository.findByRegionIdAndCanonicalNiche(region.getId(), canonical);
        if (existing.isPresent()) return existing.get();
        OsmCatalogTarget target = OsmCatalogTarget.builder()
                .region(region)
                .requestedNiche(requestedNiche)
                .canonicalNiche(canonical)
                .targetValid(50)
                .qualifiedCount(0)
                .availableNewCount(0)
                .poolStatus("EMPTY")
                .build();
        try {
            return targetRepository.saveAndFlush(target);
        } catch (DataIntegrityViolationException race) {
            entityManager.clear();
            return targetRepository.findByRegionIdAndCanonicalNiche(region.getId(), canonical)
                    .orElseThrow(() -> new ApiException(HttpStatus.CONFLICT, "OSM_SYNC_ALREADY_RUNNING",
                            "Já existe uma sincronização em andamento para este target."));
        }
    }

    private OsmSyncRun createRunForTarget(OsmCatalogTarget target, User requestedBy, String requestKey) {
        // Idempotencia: mesma key retorna o mesmo run (checado antes; re-checa aqui por corrida).
        if (requestKey != null) {
            Optional<OsmSyncRun> same = syncRunRepository.findByRequestKey(requestKey);
            if (same.isPresent()) return same.get();
        }
        // Protecao por target + por regiao (mesmo PBF: serializa por cidade).
        if (syncRunRepository.findActiveByTargetId(target.getId(), ACTIVE).isPresent()
                || syncRunRepository.findActiveByRegionId(target.getRegion().getId(), ACTIVE).isPresent()) {
            throw new ApiException(HttpStatus.CONFLICT, "OSM_SYNC_ALREADY_RUNNING",
                    "Já existe uma sincronização em andamento para este target.");
        }
        NicheMapper.NicheStrategy strategy = NicheMapper.resolve(target.getRequestedNiche());
        String strategyJson;
        try {
            strategyJson = objectMapper.writeValueAsString(strategy);
        } catch (Exception e) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "OSM_NICHE_SERIALIZATION_FAILED",
                    "Não foi possível preparar a estratégia do nicho.");
        }
        OsmSyncRun run = new OsmSyncRun();
        run.setRegion(target.getRegion());
        run.setTarget(target);
        run.setRequestedByUser(requestedBy);
        run.setStatus("QUEUED");
        run.setRequestedNiche(target.getRequestedNiche());
        run.setCanonicalNiche(target.getCanonicalNiche());
        run.setNicheStrategyJson(strategyJson);
        run.setTargetValid(target.getTargetValid() == null ? 50 : target.getTargetValid());
        run.setRequestKey(requestKey);
        try {
            run = syncRunRepository.saveAndFlush(run);
        } catch (DataIntegrityViolationException race) {
            entityManager.clear();
            if (requestKey != null) {
                Optional<OsmSyncRun> same = syncRunRepository.findByRequestKey(requestKey);
                if (same.isPresent()) return same.get();
            }
            throw new ApiException(HttpStatus.CONFLICT, "OSM_SYNC_ALREADY_RUNNING",
                    "Já existe uma sincronização em andamento para este target.");
        }
        // Novo run limpa erro visual anterior imediatamente.
        target.setLastError(null);
        target.setLastAttemptAt(Instant.now());
        targetRepository.save(target);
        OsmCatalogRegion region = target.getRegion();
        region.setLastError(null);
        region.setLastAttemptAt(Instant.now());
        regionRepository.save(region);
        log.info("[osm-target] run_queued runId={} targetId={} regionId={} canonical={} requestKey={}",
                run.getId(), target.getId(), target.getRegion().getId(), target.getCanonicalNiche(),
                requestKey == null ? "-" : "present");
        return run;
    }

    private OsmCatalogRegion resolveAndPersistNewRegion(String city, String country, String normalizedCity, String countryCode) {
        DiscoveryBudget budget = DiscoveryBudget.unlimited();
        LeadDiscoveryRequest request = new LeadDiscoveryRequest(null, "", city, country, 0);
        GeoScope scope = osmProvider.resolveScope(request, budget);
        if (!scope.hasAdminAreaCandidate()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "OSM_ADMIN_BOUNDARY_REQUIRED",
                    "Não foi possível identificar o limite administrativo OSM desta cidade.");
        }
        if (!"relation".equalsIgnoreCase(scope.osmType())) {
            throw new ApiException(HttpStatus.CONFLICT, "OSM_REGION_RELATION_REQUIRED",
                    "A cidade não possui relation administrativa OSM válida.");
        }
        String geofabrikRegion;
        try {
            geofabrikRegion = geofabrikResolver.resolve(scope.state());
        } catch (IllegalArgumentException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "GEOFABRIK_REGION_NOT_RESOLVED", e.getMessage());
        }
        String normalizedState = normalizeForCompare(scope.state());
        Optional<OsmCatalogRegion> rechecked = regionRepository
                .findByNormalizedCityAndNormalizedStateAndCountryCode(normalizedCity, normalizedState, countryCode);
        if (rechecked.isPresent()) return rechecked.get();
        OsmCatalogRegion candidate = new OsmCatalogRegion();
        candidate.setCity(city);
        candidate.setNormalizedCity(normalizedCity);
        candidate.setState(scope.state());
        candidate.setNormalizedState(normalizedState);
        candidate.setCountry(scope.country());
        candidate.setCountryCode(countryCode);
        candidate.setOsmType(scope.osmType());
        candidate.setOsmId(scope.osmId());
        candidate.setGeofabrikRegion(geofabrikRegion);
        candidate.setCatalogStatus("EMPTY");
        try {
            return regionRepository.saveAndFlush(candidate);
        } catch (DataIntegrityViolationException race) {
            entityManager.clear();
            return regionRepository
                    .findByNormalizedCityAndNormalizedStateAndCountryCode(normalizedCity, normalizedState, countryCode)
                    .orElseThrow(() -> new ApiException(HttpStatus.CONFLICT, "OSM_SYNC_ALREADY_RUNNING",
                            "Já existe uma sincronização em andamento para esta região."));
        }
    }

    private String normalizeKey(String key) {
        if (key == null || key.isBlank()) return null;
        String t = key.trim();
        if (t.length() > 64) t = t.substring(0, 64);
        // Aceita UUID ou token opaco simples.
        if (!t.matches("[A-Za-z0-9\\-_:]{8,64}")) {
            return UUID.randomUUID().toString();
        }
        return t;
    }

    private String normalizeForCompare(String s) {
        if (s == null) return "";
        return java.text.Normalizer.normalize(s.trim().toLowerCase(), java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .replaceAll("\\s+", " ")
                .trim();
    }
}
