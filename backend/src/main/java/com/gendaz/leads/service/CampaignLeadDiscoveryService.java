package com.gendaz.leads.service;

import com.gendaz.leads.domain.LeadCandidate;
import com.gendaz.leads.entity.Campaign;
import com.gendaz.leads.entity.Lead;
import com.gendaz.leads.entity.LeadEvent;
import com.gendaz.leads.repository.CampaignRepository;
import com.gendaz.leads.repository.CampaignLeadRepository;
import com.gendaz.leads.repository.LeadEventRepository;
import com.gendaz.leads.repository.LeadRepository;
import com.gendaz.leads.util.Normalizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import com.gendaz.leads.exception.ApiException;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Service
public class CampaignLeadDiscoveryService {

    private static final Logger log = LoggerFactory.getLogger(CampaignLeadDiscoveryService.class);

    private final CampaignRepository campaignRepository;
    private final CampaignLeadRepository campaignLeadRepository;
    private final LeadRepository leadRepository;
    private final LeadEventRepository leadEventRepository;
    private final DeduplicationService deduplicationService;
    private final CampaignLeadPersistenceService persistenceService;
    private final Normalizer normalizer;
    private final QualifiedLeadPoolService qualifiedPoolService;

    public CampaignLeadDiscoveryService(
            CampaignRepository campaignRepository,
            CampaignLeadRepository campaignLeadRepository,
            LeadRepository leadRepository,
            LeadEventRepository leadEventRepository,
            DeduplicationService deduplicationService,
            CampaignLeadPersistenceService persistenceService,
            Normalizer normalizer,
            QualifiedLeadPoolService qualifiedPoolService
    ) {
        this.campaignRepository = campaignRepository;
        this.campaignLeadRepository = campaignLeadRepository;
        this.leadRepository = leadRepository;
        this.leadEventRepository = leadEventRepository;
        this.deduplicationService = deduplicationService;
        this.persistenceService = persistenceService;
        this.normalizer = normalizer;
        this.qualifiedPoolService = qualifiedPoolService;
    }

    public DiscoveryExecutionResult discoverAndPersist(Campaign campaign, int targetToAdd) {
        if (targetToAdd <= 0) {
            int total = (int) campaignLeadRepository.countByCampaignId(campaign.getId());
            return new DiscoveryExecutionResult(
                    DiscoveryExecutionResult.Outcome.COMPLETE,
                    0, total, 0, 0, 0, 0, true, null, null);
        }

        log.info("[osm-catalog] pool_discovery campaignId={} niche={} city={} country={} target={}",
                campaign.getId(), campaign.getNiche(), campaign.getCity(), campaign.getCountry(), targetToAdd);

        try {
            return discoverFromQualifiedPool(campaign, targetToAdd);
        } catch (IllegalArgumentException e) {
            String message = e.getMessage() == null ? "" : e.getMessage();
            if (message.contains("OSM_TARGET_NOT_FOUND")) {
                return new DiscoveryExecutionResult(
                        DiscoveryExecutionResult.Outcome.EMPTY,
                        0,
                        (int) campaignLeadRepository.countByCampaignId(campaign.getId()),
                        0, 0, 0, 0, true,
                        "OSM_TARGET_NOT_FOUND",
                        message);
            }
            if (message.contains("OSM_CATALOG_NOT_READY")) {
                return new DiscoveryExecutionResult(
                        DiscoveryExecutionResult.Outcome.INFRA_UNAVAILABLE,
                        0,
                        (int) campaignLeadRepository.countByCampaignId(campaign.getId()),
                        0, 0, 0, 0, false,
                        "OSM_CATALOG_NOT_READY",
                        "O catálogo OSM desta cidade ainda não foi sincronizado. Sincronize a cidade antes de gerar leads.");
            }
            if (message.contains("OSM_CATALOG_LOCATION_AMBIGUOUS")) {
                return new DiscoveryExecutionResult(
                        DiscoveryExecutionResult.Outcome.INFRA_UNAVAILABLE,
                        0,
                        (int) campaignLeadRepository.countByCampaignId(campaign.getId()),
                        0, 0, 0, 0, false,
                        "OSM_CATALOG_LOCATION_AMBIGUOUS",
                        "Há mais de uma cidade sincronizada com este nome. Informe uma localização mais específica.");
            }
            throw e;
        }
    }

    private DiscoveryExecutionResult discoverFromQualifiedPool(Campaign campaign, int targetToAdd) {
        QualifiedLeadPoolService.ResolvedTarget resolved = qualifiedPoolService.resolveTarget(
                campaign.getNiche(), campaign.getCity(), campaign.getCountry());
        long targetId = resolved.target().getId();

        int initialCampaignLeadCount = (int) campaignLeadRepository.countByCampaignId(campaign.getId());
        log.info("[osm-catalog] pool_query campaignId={} targetId={} niche={} canonical={} city={} target={}",
                campaign.getId(), targetId, campaign.getNiche(), resolved.canonicalNiche(),
                campaign.getCity(), targetToAdd);

        int offset = 0;
        int accepted = 0;
        int scanned = 0;
        int pages = 0;
        int totalDuplicates = 0;
        int totalAlreadySeen = 0;
        int totalPersistenceConflicts = 0;
        boolean exhausted = false;
        Set<String> seenSourceIds = new HashSet<>();

        while (accepted < targetToAdd) {
            QualifiedLeadPoolService.PoolPage page = qualifiedPoolService.discoverPage(targetId, targetToAdd, offset);
            pages++;
            scanned += page.rawRows();

            if (page.rawRows() == 0) {
                exhausted = true;
                break;
            }

            CandidateAcceptanceResult pageResult = acceptQualifiedCatalogCandidates(
                    campaign, page.candidates(), targetToAdd - accepted, seenSourceIds);
            accepted += pageResult.accepted();
            totalDuplicates += pageResult.duplicates();
            totalAlreadySeen += pageResult.alreadySeen();
            totalPersistenceConflicts += pageResult.persistenceConflicts();

            updateProgress(campaign, initialCampaignLeadCount, accepted);

            if (accepted >= targetToAdd) break;

            if (!page.hasMore()) {
                exhausted = true;
                break;
            }

            if (page.nextOffset() <= offset) {
                throw new IllegalStateException("OSM catalog paging stalled");
            }
            offset = page.nextOffset();
        }

        int totalCampaignLeads = (int) campaignLeadRepository.countByCampaignId(campaign.getId());
        DiscoveryExecutionResult.Outcome outcome;
        String code = null;
        String message = null;

        if (accepted >= targetToAdd) {
            outcome = DiscoveryExecutionResult.Outcome.COMPLETE;
        } else if (accepted > 0) {
            outcome = DiscoveryExecutionResult.Outcome.PARTIAL;
            code = "OSM_CATALOG_PARTIAL";
            message = "Foram adicionados " + accepted + " de " + targetToAdd
                    + " leads. O pool possui apenas " + accepted + " leads novos disponíveis no momento.";
            exhausted = true;
        } else if (scanned > 0 && (totalDuplicates + totalAlreadySeen + totalPersistenceConflicts) > 0) {
            outcome = DiscoveryExecutionResult.Outcome.EMPTY;
            code = "OSM_POOL_ALL_DUPLICATES";
            message = "Existem leads qualificados no pool, mas todos já foram utilizados ou já existem no banco de leads.";
            exhausted = true;
        } else {
            outcome = DiscoveryExecutionResult.Outcome.EMPTY;
            code = "OSM_NO_USEFUL_LEADS";
            message = "Não há leads qualificados disponíveis neste target.";
            exhausted = true;
        }

        log.info("[osm-catalog] pool_summary campaignId={} targetId={} target={} scanned={} accepted={} duplicates={} pages={} outcome={}",
                campaign.getId(), targetId, targetToAdd, scanned, accepted,
                totalDuplicates + totalAlreadySeen, pages, outcome);

        return new DiscoveryExecutionResult(outcome, accepted, totalCampaignLeads, scanned, pages,
                scanned, accepted, exhausted, code, message);
    }

    private record CandidateAcceptanceResult(
            int accepted,
            int alreadySeen,
            int duplicates,
            int withoutPhone,
            int withPhone,
            int persistenceConflicts
    ) {}

    private CandidateAcceptanceResult acceptQualifiedCatalogCandidates(
            Campaign campaign,
            List<LeadCandidate> candidates,
            int remainingNeeded,
            Set<String> seenSourceIds
    ) {
        int accepted = 0;
        int alreadySeen = 0;
        int duplicates = 0;
        int withoutPhone = 0;
        int withPhone = 0;
        int persistenceConflicts = 0;

        for (LeadCandidate candidate : candidates) {
            if (accepted >= remainingNeeded) break;

            String sourceKey = normalizer.normalizeSourceId(candidate.getSource(), candidate.getSourceId());
            if (sourceKey == null) continue;

            if (!seenSourceIds.add(sourceKey)) {
                alreadySeen++;
                log.info("[osm-catalog] candidate_rejected campaignId={} reason=already_seen_this_run source={} sourceId={}",
                        campaign.getId(), candidate.getSource(), candidate.getSourceId());
                continue;
            }

            normalizeCandidatePhone(candidate);

            var duplicateCheck = deduplicationService.check(candidate);
            if (duplicateCheck.existing().isPresent()) {
                duplicates++;
                Lead existing = duplicateCheck.existing().get();
                registerDuplicateEvent(campaign, duplicateCheck, candidate);
                log.info("[osm-catalog] candidate_rejected campaignId={} reason=duplicate_global duplicateReason={} existingLeadId={} source={} sourceId={}",
                        campaign.getId(), duplicateCheck.reason(), existing.getId(),
                        candidate.getSource(), candidate.getSourceId());
                continue;
            }

            if (!hasRequiredProspectingContact(candidate)) {
                withoutPhone++;
                log.info("[osm-catalog] candidate_no_contact campaignId={} sourceId={} businessName={}",
                        campaign.getId(), candidate.getSourceId(), candidate.getBusinessName());
                registerSkippedNoContact(campaign, candidate);
                continue;
            }

            try {
                Lead lead = persistenceService.createLeadForCampaign(candidate, campaign);
                leadEventRepository.save(LeadEvent.builder()
                        .leadId(lead.getId())
                        .campaignId(campaign.getId())
                        .eventType("lead_found")
                        .eventMetadata("source=" + candidate.getSource())
                        .build());

                log.info("[osm-catalog] candidate_accepted campaignId={} reason=new_qualified_lead leadId={} source={} sourceId={}",
                        campaign.getId(), lead.getId(), candidate.getSource(), candidate.getSourceId());

                withPhone++;
                accepted++;

            } catch (DataIntegrityViolationException e) {
                persistenceConflicts++;
                log.info("[osm-catalog] candidate_rejected campaignId={} reason=persistence_conflict source={} sourceId={}",
                        campaign.getId(), candidate.getSource(), candidate.getSourceId());
            }
        }

        return new CandidateAcceptanceResult(accepted, alreadySeen, duplicates, withoutPhone, withPhone, persistenceConflicts);
    }

    private void normalizeCandidatePhone(LeadCandidate candidate) {
        if (candidate == null || candidate.getPhone() == null || candidate.getPhone().isBlank()) return;
        String normalized = normalizer.normalizePhone(candidate.getPhone());
        candidate.setPhone(normalized);
    }

    private boolean hasRequiredProspectingContact(LeadCandidate candidate) {
        return candidate != null && candidate.getPhone() != null && !candidate.getPhone().isBlank();
    }

    private void registerDuplicateEvent(Campaign campaign, DeduplicationService.DuplicateCheck check, LeadCandidate candidate) {
        Lead existing = check.existing().get();
        leadEventRepository.save(LeadEvent.builder()
                .leadId(existing.getId())
                .campaignId(campaign.getId())
                .eventType("lead_duplicate")
                .eventMetadata("reason=" + check.reason())
                .build());
    }

    private void registerSkippedNoContact(Campaign campaign, LeadCandidate candidate) {
        log.info("[osm] candidate_skipped campaignId={} reason=no_phone_for_whatsapp source={} sourceId={} businessName={}",
                campaign.getId(), candidate.getSource(), candidate.getSourceId(), candidate.getBusinessName());
    }

    private void updateProgress(Campaign campaign, int initialCampaignLeadCount, int acceptedThisRun) {
        int current = initialCampaignLeadCount + acceptedThisRun;
        int total = campaign.getRequestedQuantity();
        campaign.setProgressCurrent(Math.min(current, total));
        campaign.setProgressTotal(total);
        campaign.setProgressStage("Buscando leads (" + Math.min(current, total) + "/" + total + ")");
        campaignRepository.save(campaign);
    }
}