package com.gendaz.leads.service;

import com.gendaz.leads.domain.LeadCandidate;
import com.gendaz.leads.entity.Campaign;
import com.gendaz.leads.entity.Lead;
import com.gendaz.leads.entity.LeadEvent;
import com.gendaz.leads.repository.CampaignRepository;
import com.gendaz.leads.repository.CampaignLeadRepository;
import com.gendaz.leads.repository.LeadEventRepository;
import com.gendaz.leads.repository.LeadRepository;
import com.gendaz.leads.service.provider.AreaQueryPhase;
import com.gendaz.leads.service.provider.AreaQueryResult;
import com.gendaz.leads.service.provider.DiscoveryBudget;
import com.gendaz.leads.service.provider.GeoScope;
import com.gendaz.leads.service.provider.GeographicStrategy;
import com.gendaz.leads.service.provider.LeadDiscoveryRequest;
import com.gendaz.leads.service.provider.NicheMapper;
import com.gendaz.leads.service.provider.OpenStreetMapProvider;
import com.gendaz.leads.service.provider.SearchRegion;
import com.gendaz.leads.util.Normalizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import com.gendaz.leads.exception.ApiException;

import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

@Service
public class CampaignLeadDiscoveryService {

    private static final Logger log =
            LoggerFactory.getLogger(CampaignLeadDiscoveryService.class);

    private final CampaignRepository campaignRepository;
    private final CampaignLeadRepository campaignLeadRepository;
    private final LeadRepository leadRepository;
    private final LeadEventRepository leadEventRepository;
    private final DeduplicationService deduplicationService;
    private final CampaignLeadPersistenceService persistenceService;
    private final WebsiteContactEnricher websiteContactEnricher;
    private final Normalizer normalizer;
    private final OpenStreetMapProvider osm;

    @Value("${app.discovery.osm.base-budget-ms:180000}")
    private long baseBudgetMs;

    @Value("${app.discovery.osm.per-lead-budget-ms:15000}")
    private long perLeadBudgetMs;

    @Value("${app.discovery.osm.max-budget-ms:300000}")
    private long maxBudgetMs;

    @Value("${app.discovery.osm.query-min-raw-limit:20}")
    private int queryMinRawLimit;

    @Value("${app.discovery.osm.query-raw-per-lead:6}")
    private int queryRawPerLead;

    @Value("${app.discovery.osm.adaptive-max-depth:12}")
    private int adaptiveMaxDepth;

    @Value("${app.discovery.osm.adaptive-min-edge-km:1.0}")
    private double adaptiveMinEdgeKm;

    @Value("${app.discovery.osm.query-max-edge-km:12.0}")
    private double queryMaxEdgeKm;

    @Value("${app.discovery.osm.failure-split-threshold-km:6.0}")
    private double failureSplitThresholdKm;

    @Value("${app.discovery.osm.infra-split-threshold-km:2.0}")
    private double infraSplitThresholdKm;

    @Value("${app.discovery.osm.max-consecutive-infra-failures:6}")
    private int maxConsecutiveInfraFailures;

    @Value("${app.discovery.osm.admin-area-infra-failures-before-bbox:2}")
    private int adminAreaInfraFailuresBeforeBbox;

    @Value("${app.discovery.osm.region-budget-ms:30000}")
    private long regionBudgetMs;

    @Value("${app.discovery.osm.fallback-region-budget-ms:20000}")
    private long fallbackRegionBudgetMs;

    @Value("${app.discovery.osm.max-region-deferrals:1}")
    private int maxRegionDeferrals;

    public CampaignLeadDiscoveryService(
            CampaignRepository campaignRepository,
            CampaignLeadRepository campaignLeadRepository,
            LeadRepository leadRepository,
            LeadEventRepository leadEventRepository,
            DeduplicationService deduplicationService,
            CampaignLeadPersistenceService persistenceService,
            WebsiteContactEnricher websiteContactEnricher,
            Normalizer normalizer,
            OpenStreetMapProvider osm
    ) {
        this.campaignRepository = campaignRepository;
        this.campaignLeadRepository = campaignLeadRepository;
        this.leadRepository = leadRepository;
        this.leadEventRepository = leadEventRepository;
        this.deduplicationService = deduplicationService;
        this.persistenceService = persistenceService;
        this.websiteContactEnricher = websiteContactEnricher;
        this.normalizer = normalizer;
        this.osm = osm;
    }

    private record LazyPlanStep(
            SearchRegion region,
            int splitsPerformed
    ) {
    }

    private LazyPlanStep pollNextQueryableRegion(
            PriorityQueue<SearchRegion> queue,
            GeoScope scope,
            Long campaignId
    ) {
        if (queue.isEmpty()) {
            return null;
        }

        SearchRegion region = queue.poll();

        int splits = 0;

        while (region.maxEdgeKm() > queryMaxEdgeKm && region.depth() < adaptiveMaxDepth) {
            List<SearchRegion> children = region.split(scope.lat(), scope.lon());

            if (children.isEmpty()) {
                break;
            }

            SearchRegion next = children.get(0);

            for (int i = 1; i < children.size(); i++) {
                queue.add(children.get(i));
            }

            log.info("[osm] planner_lazy_split campaignId={} parentDepth={} parentMaxEdgeKm={} childDepth={} selectedChildMaxEdgeKm={} queuedSiblings={} queryMaxEdgeKm={}",
                    campaignId, region.depth(), region.maxEdgeKm(), next.depth(), next.maxEdgeKm(), Math.max(0, children.size() - 1), queryMaxEdgeKm);

            region = next;
            splits++;
        }

        if (region.maxEdgeKm() > queryMaxEdgeKm) {
            log.warn("[osm] planner_depth_limit campaignId={} depth={} maxEdgeKm={} queryMaxEdgeKm={} maxDepth={}",
                    campaignId, region.depth(), region.maxEdgeKm(), queryMaxEdgeKm, adaptiveMaxDepth);
        }

        return new LazyPlanStep(region, splits);
    }

    public DiscoveryExecutionResult discoverAndPersist(
            Campaign campaign,
            int targetToAdd
    ) {
        if (targetToAdd <= 0) {
            int total = (int) campaignLeadRepository
                    .countByCampaignId(campaign.getId());

            return new DiscoveryExecutionResult(
                    DiscoveryExecutionResult.Outcome.COMPLETE,
                    0,
                    total,
                    0,
                    0,
                    0,
                    0,
                    true,
                    null,
                    null
            );
        }

        validateCampaignLocation(campaign);

        DiscoveryBudget budget =
                DiscoveryBudget.forTarget(
                        targetToAdd,
                        baseBudgetMs,
                        perLeadBudgetMs,
                        maxBudgetMs
                );

        LeadDiscoveryRequest request =
                new LeadDiscoveryRequest(
                        campaign.getId(),
                        campaign.getNiche(),
                        campaign.getCity(),
                        campaign.getCountry(),
                        targetToAdd
                );

        GeoScope scope =
                osm.resolveScope(
                        request,
                        budget
                );

        int initialCampaignLeadCount =
                (int) campaignLeadRepository
                        .countByCampaignId(
                                campaign.getId()
                        );

        PriorityQueue<SearchRegion> queue =
                new PriorityQueue<>(
                        Comparator
                                .comparingDouble(
                                        SearchRegion::distanceFromCityCenterKm
                                )
                                .thenComparingInt(SearchRegion::depth)
                                .thenComparingDouble(SearchRegion::south)
                                .thenComparingDouble(SearchRegion::west)
                );

        SearchRegion rootRegion = scope.rootRegion();

        queue.add(rootRegion);

        log.info("[osm] planner_initialized campaignId={} rootMaxEdgeKm={} queryMaxEdgeKm={} maxDepth={} cityLat={} cityLon={}",
                campaign.getId(), rootRegion.maxEdgeKm(), queryMaxEdgeKm, adaptiveMaxDepth, scope.lat(), scope.lon());

        GeographicStrategy geographicStrategy = scope.hasAdminAreaCandidate()
                ? GeographicStrategy.ADMIN_AREA
                : GeographicStrategy.BBOX_FALLBACK;

        log.info("[osm] geographic_strategy campaignId={} strategy={} osmType={} osmId={} bboxValid={}",
                campaign.getId(), geographicStrategy, scope.osmType(), scope.osmId(), scope.bboxValid());

        Set<String> seenSourceIds =
                new HashSet<>();

        Map<String, WebsiteContactEnricher.WebsiteContactData>
                enrichmentCache =
                new HashMap<>();

        int accepted = 0;
        int areasAttempted = 0;
        int areasSucceeded = 0;
        int areasSplit = 0;
        int areasSkipped = 0;

        boolean anyValidQuery = false;
        boolean infraDegraded = false;

        int consecutiveInfraFailures = 0;
        int adminAreaInfraFailures = 0;
        Set<String> failedHostsThisCampaign = new HashSet<>();

        String finalErrorCode = null;
        String finalErrorMessage = null;

        while (
                !queue.isEmpty()
                && accepted < targetToAdd
                && !budget.expired()
        ) {
            LazyPlanStep planStep = pollNextQueryableRegion(queue, scope, campaign.getId());

            if (planStep == null) {
                break;
            }

            SearchRegion region = planStep.region();
            areasSplit += planStep.splitsPerformed();

            log.info(
                    "[osm] planner_http_leaf campaignId={} depth={} maxEdgeKm={} bbox={} queueRemaining={} geographicStrategy={}",
                    campaign.getId(),
                    region.depth(),
                    region.maxEdgeKm(),
                    region.bbox(),
                    queue.size(),
                    geographicStrategy
            );

            int remainingUseful =
                    targetToAdd - accepted;

            int rawLimit =
                    calculateRawLimit(remainingUseful);

            NicheMapper.NicheStrategy strategy =
                    NicheMapper.resolve(campaign.getNiche());

            boolean hasStructured =
                    !strategy.tagFilters().isEmpty();

            boolean shouldRunNameFallback = !hasStructured;

            if (hasStructured) {
                areasAttempted++;

                AreaQueryResult structured =
                        osm.queryRegionWithStrategy(
                                scope,
                                geographicStrategy,
                                campaign.getNiche(),
                                region,
                                AreaQueryPhase.STRUCTURED,
                                rawLimit,
                                budget,
                                campaign.getId()
                        );

                if (structured.outcome()
                        == AreaQueryResult.Outcome.ADMIN_AREA_UNAVAILABLE
                        && geographicStrategy == GeographicStrategy.ADMIN_AREA) {

                    geographicStrategy = GeographicStrategy.BBOX_FALLBACK;
                    queue.add(region);

                    log.warn("[osm] geographic_strategy_fallback campaignId={} from=ADMIN_AREA to=BBOX_FALLBACK osmType={} osmId={} regionDepth={}",
                            campaign.getId(), scope.osmType(), scope.osmId(), region.depth());

                    continue;
                }

                if (structured.outcome()
                        == AreaQueryResult.Outcome.SUCCESS) {

                    anyValidQuery = true;
                    areasSucceeded++;
                    consecutiveInfraFailures = 0;
                    adminAreaInfraFailures = 0;
                    failedHostsThisCampaign.clear();

                    List<LeadCandidate> structuredCandidates = structured.candidates();
                    boolean structuredFoundCandidates =
                            structuredCandidates != null
                                    && !structuredCandidates.isEmpty();

                    accepted += acceptCandidates(
                            campaign,
                            structuredCandidates,
                            targetToAdd - accepted,
                            seenSourceIds,
                            enrichmentCache
                    );

                    updateProgress(
                            campaign,
                            initialCampaignLeadCount,
                            accepted
                    );

                    if (accepted >= targetToAdd) {
                        break;
                    }

                    shouldRunNameFallback = !structuredFoundCandidates;

                    if (structured.saturated()) {
                        if (region.canSplit(adaptiveMaxDepth, adaptiveMinEdgeKm)) {
                            queue.addAll(region.split(scope.lat(), scope.lon()));
                            areasSplit++;
                            continue;
                        }

                        infraDegraded = true;
                        finalErrorCode = "OSM_REGION_SATURATED";
                        finalErrorMessage = "Região atingiu o limite de resultados e não pode ser subdividida novamente.";
                        continue;
                    }

                } else if (structured.outcome()
                        == AreaQueryResult.Outcome.SPLIT_REQUIRED) {

                    if (region.canSplit(adaptiveMaxDepth, adaptiveMinEdgeKm)) {
                        queue.addAll(region.split(scope.lat(), scope.lon()));
                        areasSplit++;
                        continue;
                    }

                    infraDegraded = true;
                    finalErrorCode = structured.errorCode();
                    finalErrorMessage = structured.errorMessage();
                    continue;

                } else if (structured.outcome()
                        == AreaQueryResult.Outcome.INFRA_UNAVAILABLE) {

                    if (structured.httpAttemptMade()) {
                        consecutiveInfraFailures++;
                    }
                    infraDegraded = true;
                    finalErrorCode = structured.errorCode();
                    finalErrorMessage = structured.errorMessage();

                    if (geographicStrategy == GeographicStrategy.ADMIN_AREA) {
                        if (structured.httpAttemptMade()) {
                            adminAreaInfraFailures++;
                        }
                        log.warn("[osm] admin_area_infra_failure campaignId={} count={} threshold={} regionDepth={} httpAttempt={}",
                                campaign.getId(), adminAreaInfraFailures, adminAreaInfraFailuresBeforeBbox, region.depth(), structured.httpAttemptMade());
                        if (adminAreaInfraFailures >= adminAreaInfraFailuresBeforeBbox && scope.bboxValid()) {
                            geographicStrategy = GeographicStrategy.BBOX_FALLBACK;
                            queue.add(region);
                            log.warn("[osm] switching_to_bbox_fallback campaignId={} reason=admin_area_infra_unstable adminAreaInfraFailures={} threshold={}",
                                    campaign.getId(), adminAreaInfraFailures, adminAreaInfraFailuresBeforeBbox);
                            continue;
                        }
                    }

                    if (queue.isEmpty() || consecutiveInfraFailures >= maxConsecutiveInfraFailures) {
                        break;
                    }

                    if (region.maxEdgeKm() <= infraSplitThresholdKm
                            && region.canSplit(adaptiveMaxDepth, adaptiveMinEdgeKm)
                            && budget.remainingMs() > 0) {

                        log.info("[osm] infra_split_small_region campaignId={} maxEdgeKm={} thresholdKm={} depth={} queueRemaining={}",
                                campaign.getId(), region.maxEdgeKm(), infraSplitThresholdKm, region.depth(), queue.size());
                        queue.addAll(region.split(scope.lat(), scope.lon()));
                        areasSplit++;
                        continue;
                    }

                    log.info("[osm] infra_failure_continue_queue campaignId={} queueRemaining={} consecutiveFailures={} maxConsecutive={} httpAttempt={}",
                            campaign.getId(), queue.size(), consecutiveInfraFailures, maxConsecutiveInfraFailures, structured.httpAttemptMade());
                    continue;

                } else {
                    throw new ApiException(
                            HttpStatus.BAD_GATEWAY,
                            structured.errorCode(),
                            structured.errorMessage()
                    );
                }
            }

            if (
                    shouldRunNameFallback
                    && accepted < targetToAdd
                    && !budget.expired()
            ) {
                areasAttempted++;

                AreaQueryResult fallback =
                        osm.queryRegionWithStrategy(
                                scope,
                                geographicStrategy,
                                campaign.getNiche(),
                                region,
                                AreaQueryPhase.NAME_FALLBACK,
                                rawLimit,
                                budget,
                                campaign.getId()
                        );

                if (fallback.outcome()
                        == AreaQueryResult.Outcome.ADMIN_AREA_UNAVAILABLE
                        && geographicStrategy == GeographicStrategy.ADMIN_AREA) {

                    geographicStrategy = GeographicStrategy.BBOX_FALLBACK;
                    queue.add(region);

                    log.warn("[osm] geographic_strategy_fallback campaignId={} from=ADMIN_AREA to=BBOX_FALLBACK osmType={} osmId={} regionDepth={}",
                            campaign.getId(), scope.osmType(), scope.osmId(), region.depth());

                    continue;
                }

                if (fallback.outcome()
                        == AreaQueryResult.Outcome.SUCCESS) {

                    anyValidQuery = true;
                    areasSucceeded++;
                    consecutiveInfraFailures = 0;
                    adminAreaInfraFailures = 0;
                    failedHostsThisCampaign.clear();

                    accepted += acceptCandidates(
                            campaign,
                            fallback.candidates(),
                            targetToAdd - accepted,
                            seenSourceIds,
                            enrichmentCache
                    );

                    updateProgress(
                            campaign,
                            initialCampaignLeadCount,
                            accepted
                    );

                    if (accepted >= targetToAdd) {
                        break;
                    }

                    if (fallback.saturated()) {
                        if (region.canSplit(adaptiveMaxDepth, adaptiveMinEdgeKm)) {
                            queue.addAll(region.split(scope.lat(), scope.lon()));
                            areasSplit++;
                        } else {
                            infraDegraded = true;
                            finalErrorCode = "OSM_REGION_SATURATED";
                            finalErrorMessage = "Fallback atingiu o limite e a região não pode ser subdividida novamente.";
                        }
                    }

                } else if (
                        fallback.outcome()
                                == AreaQueryResult.Outcome.SPLIT_REQUIRED
                ) {

                    if (region.canSplit(adaptiveMaxDepth, adaptiveMinEdgeKm)) {
                        queue.addAll(region.split(scope.lat(), scope.lon()));
                        areasSplit++;
                    } else {
                        infraDegraded = true;
                        finalErrorCode = fallback.errorCode();
                        finalErrorMessage = fallback.errorMessage();
                    }

                } else if (
                        fallback.outcome()
                                == AreaQueryResult.Outcome.INFRA_UNAVAILABLE
                ) {

                    if (fallback.httpAttemptMade()) {
                        consecutiveInfraFailures++;
                    }
                    infraDegraded = true;
                    finalErrorCode = fallback.errorCode();
                    finalErrorMessage = fallback.errorMessage();

                    if (geographicStrategy == GeographicStrategy.ADMIN_AREA) {
                        if (fallback.httpAttemptMade()) {
                            adminAreaInfraFailures++;
                        }
                        log.warn("[osm] admin_area_infra_failure campaignId={} count={} threshold={} regionDepth={} httpAttempt={}",
                                campaign.getId(), adminAreaInfraFailures, adminAreaInfraFailuresBeforeBbox, region.depth(), fallback.httpAttemptMade());
                        if (adminAreaInfraFailures >= adminAreaInfraFailuresBeforeBbox && scope.bboxValid()) {
                            geographicStrategy = GeographicStrategy.BBOX_FALLBACK;
                            queue.add(region);
                            log.warn("[osm] switching_to_bbox_fallback campaignId={} reason=admin_area_infra_unstable adminAreaInfraFailures={} threshold={}",
                                    campaign.getId(), adminAreaInfraFailures, adminAreaInfraFailuresBeforeBbox);
                            continue;
                        }
                    }

                    if (queue.isEmpty() || consecutiveInfraFailures >= maxConsecutiveInfraFailures) {
                        break;
                    }

                    if (region.maxEdgeKm() <= infraSplitThresholdKm
                            && region.canSplit(adaptiveMaxDepth, adaptiveMinEdgeKm)
                            && budget.remainingMs() > 0) {

                        log.info("[osm] infra_split_small_region campaignId={} maxEdgeKm={} thresholdKm={} depth={} queueRemaining={}",
                                campaign.getId(), region.maxEdgeKm(), infraSplitThresholdKm, region.depth(), queue.size());
                        queue.addAll(region.split(scope.lat(), scope.lon()));
                        areasSplit++;
                        continue;
                    }

                    log.info("[osm] infra_failure_continue_queue campaignId={} queueRemaining={} consecutiveFailures={} maxConsecutive={} httpAttempt={}",
                            campaign.getId(), queue.size(), consecutiveInfraFailures, maxConsecutiveInfraFailures, fallback.httpAttemptMade());
                    continue;

                } else {
                    throw new ApiException(
                            HttpStatus.BAD_GATEWAY,
                            fallback.errorCode(),
                            fallback.errorMessage()
                    );
                }
            }
        }

        boolean coverageExhausted = queue.isEmpty();

        int totalCampaignLeads =
                (int) campaignLeadRepository
                        .countByCampaignId(
                                campaign.getId()
                        );

        DiscoveryExecutionResult.Outcome outcome;

        if (accepted >= targetToAdd) {
            outcome =
                    DiscoveryExecutionResult.Outcome.COMPLETE;

        } else if (accepted > 0) {
            outcome =
                    DiscoveryExecutionResult.Outcome.PARTIAL;

            if (finalErrorMessage == null) {
                finalErrorMessage =
                        budget.expired()
                                ? "Budget de descoberta esgotado após encontrar parte dos leads."
                                : "A região pesquisada não forneceu a quantidade total de leads úteis.";
            }

        } else if (!anyValidQuery && infraDegraded) {
            outcome =
                    DiscoveryExecutionResult.Outcome.INFRA_UNAVAILABLE;

            if (finalErrorCode == null || finalErrorCode.equals("OSM_ALL_ENDPOINTS_FAILED")) {
                finalErrorCode = "OSM_OVERPASS_TEMPORARILY_UNAVAILABLE";
                finalErrorMessage = "Os servidores públicos do OpenStreetMap/Overpass estão instáveis agora. Tente novamente em alguns minutos.";
            }

        } else if (budget.expired() && !coverageExhausted) {
            outcome =
                    DiscoveryExecutionResult.Outcome.BUDGET_EXHAUSTED;

            finalErrorCode =
                    "OSM_DISCOVERY_BUDGET_EXHAUSTED";

            finalErrorMessage =
                    "A busca atingiu o tempo máximo antes de concluir. Tente novamente.";

        } else if (
                anyValidQuery
                && coverageExhausted
                && !infraDegraded
        ) {
            outcome =
                    DiscoveryExecutionResult.Outcome.EMPTY;

            finalErrorCode =
                    "OSM_NO_USEFUL_LEADS";

            finalErrorMessage =
                    "Nenhum lead útil encontrado após consultas válidas.";
        } else {
            outcome =
                    DiscoveryExecutionResult.Outcome.INFRA_UNAVAILABLE;

            if (finalErrorCode == null) {
                finalErrorCode =
                        "OSM_OVERPASS_TEMPORARILY_UNAVAILABLE";
            }

            if (finalErrorMessage == null) {
                finalErrorMessage =
                        "Os servidores públicos do OpenStreetMap/Overpass estão instáveis agora. Tente novamente em alguns minutos.";
            }
        }

        log.info(
                "[osm] discovery_summary campaignId={} acceptedThisRun={} totalCampaignLeads={} geographicStrategy={} areasAttempted={} areasSucceeded={} areasSplit={} queueRemaining={} coverageExhausted={} queryMaxEdgeKm={} failureSplitThresholdKm={} budgetElapsedMs={} budgetTotalMs={} outcome={}",
                campaign.getId(),
                accepted,
                totalCampaignLeads,
                geographicStrategy,
                areasAttempted,
                areasSucceeded,
                areasSplit,
                queue.size(),
                coverageExhausted,
                queryMaxEdgeKm,
                failureSplitThresholdKm,
                budget.elapsedMs(),
                budget.totalMs(),
                outcome
        );

        return new DiscoveryExecutionResult(
                outcome,
                accepted,
                totalCampaignLeads,
                areasAttempted,
                areasSucceeded,
                areasSplit,
                areasSkipped,
                coverageExhausted,
                finalErrorCode,
                finalErrorMessage
        );
    }

    private void validateCampaignLocation(Campaign campaign) {
        if (campaign.getCity() == null || campaign.getCity().isBlank()) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "LEGACY_CAMPAIGN_LOCATION_MISSING",
                    "Esta campanha antiga não possui cidade e país estruturados. Crie uma nova campanha para refazer a descoberta."
            );
        }
        if (campaign.getCountry() == null || campaign.getCountry().isBlank()) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "LEGACY_CAMPAIGN_LOCATION_MISSING",
                    "Esta campanha antiga não possui cidade e país estruturados. Crie uma nova campanha para refazer a descoberta."
            );
        }
    }

    private int calculateRawLimit(int remainingUseful) {
        int calculated = Math.max(
                queryMinRawLimit,
                remainingUseful * queryRawPerLead
        );
        return Math.min(calculated, 200);
    }

    private int acceptCandidates(
            Campaign campaign,
            List<LeadCandidate> candidates,
            int remainingNeeded,
            Set<String> seenSourceIds,
            Map<String, WebsiteContactEnricher.WebsiteContactData> enrichmentCache
    ) {
        int accepted = 0;

        for (LeadCandidate candidate : candidates) {
            if (accepted >= remainingNeeded) {
                break;
            }

            String sourceKey =
                    normalizer.normalizeSourceId(
                            candidate.getSource(),
                            candidate.getSourceId()
                    );

            if (sourceKey == null) {
                continue;
            }

            if (!seenSourceIds.add(sourceKey)) {
                log.info(
                        "[osm] candidate_rejected campaignId={} reason=already_seen_this_run source={} sourceId={}",
                        campaign.getId(),
                        candidate.getSource(),
                        candidate.getSourceId()
                );
                continue;
            }

            var beforeEnrichment =
                    deduplicationService.check(candidate);

            if (beforeEnrichment.existing().isPresent()) {
                if (tryLinkExistingLead(
                        campaign,
                        candidate,
                        beforeEnrichment
                )) {
                    accepted++;
                }
                continue;
            }

            if (!isUseful(candidate)) {
                registerSkippedNoContact(
                        campaign,
                        candidate
                );
                continue;
            }

            enrichCandidateIfNeeded(
                    candidate,
                    enrichmentCache
            );

            var afterEnrichment =
                    deduplicationService.check(candidate);

            if (afterEnrichment.existing().isPresent()) {
                if (tryLinkExistingLead(
                        campaign,
                        candidate,
                        afterEnrichment
                )) {
                    accepted++;
                }
                continue;
            }

            if (!isUseful(candidate)) {
                registerSkippedNoContact(
                        campaign,
                        candidate
                );
                continue;
            }

            try {
                Lead lead =
                        persistenceService
                                .createLeadForCampaign(
                                        candidate,
                                        campaign
                                );

                leadEventRepository.save(
                        LeadEvent.builder()
                                .leadId(lead.getId())
                                .campaignId(campaign.getId())
                                .eventType("lead_found")
                                .eventMetadata(
                                        "source="
                                                + candidate.getSource()
                                )
                                .build()
                );

                log.info(
                        "[osm] candidate_accepted campaignId={} reason=new_lead leadId={} source={} sourceId={}",
                        campaign.getId(),
                        lead.getId(),
                        candidate.getSource(),
                        candidate.getSourceId()
                );

                accepted++;

            } catch (DataIntegrityViolationException e) {
                log.info(
                        "[osm] candidate_rejected campaignId={} reason=persistence_conflict source={} sourceId={}",
                        campaign.getId(),
                        candidate.getSource(),
                        candidate.getSourceId()
                );
            }
        }

        return accepted;
    }

    private void enrichCandidateIfNeeded(
            LeadCandidate candidate,
            Map<String, WebsiteContactEnricher.WebsiteContactData> cache
    ) {
        if (!needsEnrichment(candidate)) {
            return;
        }

        String website =
                normalizer.normalizeWebsite(
                        candidate.getWebsite()
                );

        if (website == null) {
            return;
        }

        WebsiteContactEnricher.WebsiteContactData data =
                cache.computeIfAbsent(
                        website,
                        websiteContactEnricher::enrich
                );

        if (data == null) {
            return;
        }

        if (!hasText(candidate.getPhone())
                && hasText(data.phone())) {
            candidate.setPhone(data.phone());
        }

        if (!hasText(candidate.getEmail())
                && hasText(data.email())) {
            candidate.setEmail(data.email());
        }

        if (!hasText(candidate.getInstagramUsername())
                && hasText(data.instagramUsername())) {

            candidate.setInstagramUsername(
                    data.instagramUsername()
            );

            candidate.setInstagramUrl(
                    "https://instagram.com/"
                            + data.instagramUsername()
            );

            candidate.setInstagramStatus("FOUND");
        }
    }

    private boolean needsEnrichment(LeadCandidate c) {
        return hasText(c.getWebsite())
                && (
                !hasText(c.getPhone())
                || !hasText(c.getEmail())
                || !hasText(c.getInstagramUsername())
        );
    }

    private boolean isUseful(LeadCandidate c) {
        return hasText(c.getPhone())
                || hasText(c.getEmail())
                || hasText(c.getInstagramUsername())
                || hasText(c.getInstagramUrl())
                || hasText(c.getWebsite());
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private boolean isUseful(Lead lead) {
        return hasText(lead.getPhone())
                || hasText(lead.getEmail())
                || hasText(lead.getInstagramUsername())
                || hasText(lead.getInstagramUrl())
                || hasText(lead.getWebsite());
    }

    private boolean tryLinkExistingLead(
            Campaign campaign,
            LeadCandidate candidate,
            DeduplicationService.DuplicateCheck duplicateCheck
    ) {
        if (duplicateCheck.existing().isEmpty()) {
            return false;
        }

        Lead existing = duplicateCheck.existing().get();

        if (existing.isDoNotContact()) {
            log.info(
                    "[osm] candidate_rejected campaignId={} reason=do_not_contact_existing duplicateReason={} leadId={} source={} sourceId={}",
                    campaign.getId(),
                    duplicateCheck.reason(),
                    existing.getId(),
                    candidate.getSource(),
                    candidate.getSourceId()
            );
            return false;
        }

        if (!isUseful(existing)) {
            log.info(
                    "[osm] candidate_rejected campaignId={} reason=existing_without_useful_contact duplicateReason={} leadId={} source={} sourceId={}",
                    campaign.getId(),
                    duplicateCheck.reason(),
                    existing.getId(),
                    candidate.getSource(),
                    candidate.getSourceId()
            );
            return false;
        }

        boolean linked =
                persistenceService.linkExistingLeadToCampaign(
                        existing,
                        campaign
                );

        if (!linked) {
            log.info(
                    "[osm] candidate_rejected campaignId={} reason=already_linked_to_campaign duplicateReason={} leadId={} source={} sourceId={}",
                    campaign.getId(),
                    duplicateCheck.reason(),
                    existing.getId(),
                    candidate.getSource(),
                    candidate.getSourceId()
            );
            return false;
        }

        leadEventRepository.save(
                LeadEvent.builder()
                        .leadId(existing.getId())
                        .campaignId(campaign.getId())
                        .eventType("lead_found")
                        .eventMetadata(
                                "source="
                                        + candidate.getSource()
                                        + ";reused_existing=true"
                                        + ";duplicate_reason="
                                        + duplicateCheck.reason()
                        )
                        .build()
        );

        log.info(
                "[osm] candidate_accepted campaignId={} reason=reused_existing duplicateReason={} leadId={} source={} sourceId={}",
                campaign.getId(),
                duplicateCheck.reason(),
                existing.getId(),
                candidate.getSource(),
                candidate.getSourceId()
        );

        return true;
    }

    private void registerDuplicateEvent(
            Campaign campaign,
            DeduplicationService.DuplicateCheck check,
            LeadCandidate candidate
    ) {
        Lead existing = check.existing().get();
        leadEventRepository.save(
                LeadEvent.builder()
                        .leadId(existing.getId())
                        .campaignId(campaign.getId())
                        .eventType("lead_duplicate")
                        .eventMetadata("reason=" + check.reason())
                        .build()
        );
    }

    private void registerSkippedNoContact(
            Campaign campaign,
            LeadCandidate candidate
    ) {
        log.info(
                "[osm] candidate_skipped campaignId={} reason=no_contact source={} sourceId={} businessName={}",
                campaign.getId(),
                candidate.getSource(),
                candidate.getSourceId(),
                candidate.getBusinessName()
        );
    }

    private void updateProgress(
            Campaign campaign,
            int initialCampaignLeadCount,
            int acceptedThisRun
    ) {
        int current = initialCampaignLeadCount + acceptedThisRun;
        int total = campaign.getRequestedQuantity();
        campaign.setProgressCurrent(Math.min(current, total));
        campaign.setProgressTotal(total);
        campaign.setProgressStage("Buscando leads (" + Math.min(current, total) + "/" + total + ")");
        campaignRepository.save(campaign);
    }

    private String regionKey(
            SearchRegion region,
            GeographicStrategy strategy
    ) {
        return strategy.name()
                + "|"
                + region.depth()
                + "|"
                + region.bbox();
    }

    private boolean isRegionalBudgetExhausted(
            AreaQueryResult result,
            DiscoveryBudget regionBudget,
            DiscoveryBudget globalBudget
    ) {
        return result != null
                && result.outcome() == AreaQueryResult.Outcome.INFRA_UNAVAILABLE
                && "OSM_DISCOVERY_TIMEOUT".equals(result.errorCode())
                && regionBudget.expired()
                && !globalBudget.expired();
    }

    private boolean hasRequiredProspectingContact(LeadCandidate candidate) {
        return candidate != null && hasText(candidate.getPhone());
    }
}