package com.gendaz.leads.service;

import com.gendaz.leads.domain.LeadCandidate;
import com.gendaz.leads.entity.*;
import com.gendaz.leads.exception.ApiException;
import com.gendaz.leads.repository.*;
import com.gendaz.leads.service.provider.LeadDiscoveryRequest;
import com.gendaz.leads.service.provider.LeadDiscoveryResult;
import com.gendaz.leads.service.provider.OpenStreetMapProvider;
import com.gendaz.leads.util.Normalizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Service
public class AsyncCampaignProcessor {

    private static final Logger log = LoggerFactory.getLogger(AsyncCampaignProcessor.class);

    private final CampaignRepository campaignRepository;
    private final LeadRepository leadRepository;
    private final LeadEventRepository leadEventRepository;
    private final LeadAnalysisService leadAnalysisService;
    private final DeduplicationService deduplicationService;
    private final Normalizer normalizer;
    private final OpenStreetMapProvider openStreetMapProvider;
    private final CampaignLeadPersistenceService persistenceService;
    private final CampaignLeadRepository campaignLeadRepository;

    public AsyncCampaignProcessor(CampaignRepository campaignRepository, LeadRepository leadRepository,
                                  LeadEventRepository leadEventRepository, LeadAnalysisService leadAnalysisService,
                                  DeduplicationService deduplicationService, Normalizer normalizer,
                                  OpenStreetMapProvider openStreetMapProvider,
                                  CampaignLeadPersistenceService persistenceService,
                                  CampaignLeadRepository campaignLeadRepository) {
        this.campaignRepository = campaignRepository;
        this.leadRepository = leadRepository;
        this.leadEventRepository = leadEventRepository;
        this.leadAnalysisService = leadAnalysisService;
        this.deduplicationService = deduplicationService;
        this.normalizer = normalizer;
        this.openStreetMapProvider = openStreetMapProvider;
        this.persistenceService = persistenceService;
        this.campaignLeadRepository = campaignLeadRepository;
    }

    @Async
    public void processCampaign(Long campaignId) {
        Campaign campaign = campaignRepository.findById(campaignId).orElse(null);
        if (campaign == null) return;

        try {
            discoverStage(campaign);
            analyzeStage(campaign);
            finalizeCampaign(campaign);
        } catch (ApiException e) {
            log.error("Erro no processamento da campanha {}: {} ({})", campaignId, e.getMessage(), e.getCode(), e);
            campaign.setStatus("FAILED");
            campaign.setErrorMessage(truncate(e.getMessage()));
            campaignRepository.save(campaign);
        } catch (Exception e) {
            log.error("Erro no processamento da campanha {}: {}", campaignId, e.getMessage(), e);
            campaign.setStatus("FAILED");
            campaign.setErrorMessage(truncate(e.getMessage()));
            campaignRepository.save(campaign);
        }
    }

    @Async
    public void processPartialCampaign(Long campaignId, int remaining) {
        Campaign campaign = campaignRepository.findById(campaignId).orElse(null);
        if (campaign == null) return;

        if (remaining <= 0) {
            recomputeAndFinalize(campaign);
            return;
        }

        try {
            int discovered = discoverAdditionalLeads(campaign, remaining);
            analyzeOnlyPendingOrError(campaign);
            recomputeAndFinalize(campaign);
        } catch (ApiException e) {
            log.error("Discovery falhou na campanha parcial {}: {} ({})", campaignId, e.getMessage(), e.getCode());
            campaign.setStatus("FAILED");
            campaign.setErrorMessage(truncate(e.getMessage()));
            campaignRepository.save(campaign);
        } catch (Exception e) {
            log.error("Erro no processamento parcial da campanha {}: {}", campaignId, e.getMessage(), e);
            campaign.setStatus("FAILED");
            campaign.setErrorMessage(truncate(e.getMessage()));
            campaignRepository.save(campaign);
        }
    }

    private void recomputeAndFinalize(Campaign campaign) {
        recompute(campaign);
        finalizeCampaign(campaign);
    }

    public void recomputeAndFinalizeCampaign(Campaign campaign) {
        recomputeAndFinalize(campaign);
    }

    private int discoverAdditionalLeads(Campaign campaign, int needed) {
        campaign.setStatus("DISCOVERING");
        campaign.setProgressStage("Complementando resultados");
        campaign.setProgressTotal(needed);
        campaign.setProgressCurrent(0);
        campaignRepository.save(campaign);

        LeadDiscoveryRequest request = new LeadDiscoveryRequest(
                campaign.getId(),
                campaign.getNiche(),
                campaign.getCity(),
                campaign.getCountry(),
                needed
        );

        LeadDiscoveryResult result;
        try {
            if (openStreetMapProvider.isEnabled()) {
                result = openStreetMapProvider.discover(request);
            } else {
                log.warn("OpenStreetMapProvider não está habilitado para campanha {}", campaign.getId());
                result = LeadDiscoveryResult.empty("OSM_DISABLED", "Provider desabilitado");
            }
        } catch (ApiException e) {
            log.warn("Provider {} falhou: {} ({})", openStreetMapProvider.getName(), e.getMessage(), e.getCode());
            throw e;
        } catch (RuntimeException e) {
            log.warn("Provider {} falhou: {}", openStreetMapProvider.getName(), e.getMessage());
            throw new ApiException(
                    HttpStatus.BAD_GATEWAY,
                    "OSM_OVERPASS_ERROR",
                    e.getMessage() != null ? e.getMessage() : "Falha ao consultar OpenStreetMap/Overpass.");
        }

        if (result.outcome() == LeadDiscoveryResult.DiscoveryOutcome.INFRA_UNAVAILABLE) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, result.errorCode(), result.errorMessage());
        }
        if (result.outcome() == LeadDiscoveryResult.DiscoveryOutcome.DEADLINE_EXCEEDED) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, result.errorCode(), result.errorMessage());
        }

        Set<String> batchSeen = new LinkedHashSet<>();
        int discovered = 0;

        List<Lead> existingLeads = leadRepository.findByCampaign(campaign.getId());
        for (Lead lead : existingLeads) {
            String key = batchKeyFromLead(lead);
            if (key != null) batchSeen.add(key);
        }

        for (LeadCandidate candidate : result.candidates()) {
            if (discovered >= needed) break;

            String key = batchKey(candidate);
            if (key != null && !batchSeen.add(key)) continue;

            var dup = deduplicationService.check(candidate);
            if (dup.existing().isPresent()) {
                leadEventRepository.save(LeadEvent.builder()
                        .leadId(dup.existing().get().getId())
                        .campaignId(campaign.getId())
                        .eventType("lead_duplicate")
                        .eventMetadata("reason=" + dup.reason())
                        .build());
                continue;
            }
            try {
                Lead lead = persistenceService.createLeadForCampaign(candidate, campaign);
                leadEventRepository.save(LeadEvent.builder()
                        .leadId(lead.getId()).campaignId(campaign.getId())
                        .eventType("lead_found").eventMetadata("source=" + candidate.getSource()).build());
                discovered++;
                campaign.setProgressCurrent(discovered);
                campaignRepository.save(campaign);
            } catch (DataIntegrityViolationException e) {
                log.warn("Conflito de unicidade ao inserir lead (possível duplicata): {}", candidate.getBusinessName());
            }
        }
        return discovered;
    }

    private String batchKeyFromLead(Lead lead) {
        if (lead.getNormalizedSourceId() != null) return "src:" + lead.getNormalizedSourceId();
        if (lead.getNormalizedInstagram() != null) return "ig:" + lead.getNormalizedInstagram();
        if (lead.getNormalizedWebsite() != null) return "web:" + lead.getNormalizedWebsite();
        if (lead.getNormalizedPhone() != null) return "ph:" + lead.getNormalizedPhone();
        if (lead.getNormalizedEmail() != null) return "em:" + lead.getNormalizedEmail();
        if (lead.getNormalizedName() != null && lead.getCity() != null && lead.getCountry() != null)
            return "nm:" + lead.getNormalizedName() + "|" + lead.getCity() + "|" + lead.getCountry();
        return null;
    }

    public void retryFailedLeads(Long campaignId) {
        Campaign campaign = campaignRepository.findById(campaignId).orElse(null);
        if (campaign == null) return;
        List<Lead> leads = leadRepository.findByCampaign(campaignId);
        int done = 0;
        for (Lead lead : leads) {
            if (!"ERROR".equals(lead.getStatus())) continue;
            try {
                lead.setStatus("NEW");
                leadRepository.save(lead);
                leadAnalysisService.analyzeAndGenerate(lead, campaignId);
                done++;
            } catch (RuntimeException ex) {
                lead.setStatus("ERROR");
                leadRepository.save(lead);
                log.warn("Falha ao reprocessar lead {}: {}", lead.getId(), ex.getMessage());
            }
        }
        recompute(campaign);
        campaignRepository.save(campaign);
        log.info("Reprocessamento de campanha {} concluído: {} leads recuperados", campaignId, done);
    }

    private void discoverStage(Campaign campaign) {
        campaign.setStatus("DISCOVERING");
        campaign.setProgressStage("Localizando cidade");
        campaign.setProgressTotal(campaign.getRequestedQuantity());
        campaign.setProgressCurrent(0);
        campaignRepository.save(campaign);

        LeadDiscoveryRequest request = new LeadDiscoveryRequest(
                campaign.getId(),
                campaign.getNiche(),
                campaign.getCity(),
                campaign.getCountry(),
                campaign.getRequestedQuantity()
        );

        LeadDiscoveryResult result;
        try {
            if (openStreetMapProvider.isEnabled()) {
                campaign.setProgressStage("Buscando leads");
                campaignRepository.save(campaign);
                result = openStreetMapProvider.discover(request);
            } else {
                log.warn("OpenStreetMapProvider não está habilitado para campanha {}", campaign.getId());
                result = LeadDiscoveryResult.empty("OSM_DISABLED", "Provider desabilitado");
            }
        } catch (ApiException e) {
            log.warn("Provider {} falhou: {} ({})", openStreetMapProvider.getName(), e.getMessage(), e.getCode());
            throw e;
        } catch (RuntimeException e) {
            log.warn("Provider {} falhou: {}", openStreetMapProvider.getName(), e.getMessage());
            throw new ApiException(
                    HttpStatus.BAD_GATEWAY,
                    "OSM_OVERPASS_ERROR",
                    e.getMessage() != null ? e.getMessage() : "Falha ao consultar OpenStreetMap/Overpass.");
        }

        if (result.outcome() == LeadDiscoveryResult.DiscoveryOutcome.INFRA_UNAVAILABLE) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, result.errorCode(), result.errorMessage());
        }
        if (result.outcome() == LeadDiscoveryResult.DiscoveryOutcome.DEADLINE_EXCEEDED) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, result.errorCode(), result.errorMessage());
        }

        Set<String> batchSeen = new LinkedHashSet<>();
        int discovered = 0;

        for (LeadCandidate candidate : result.candidates()) {
            if (discovered >= campaign.getRequestedQuantity()) break;

            String key = batchKey(candidate);
            if (key != null && !batchSeen.add(key)) continue;

            var dup = deduplicationService.check(candidate);
            if (dup.existing().isPresent()) {
                leadEventRepository.save(LeadEvent.builder()
                        .leadId(dup.existing().get().getId())
                        .campaignId(campaign.getId())
                        .eventType("lead_duplicate")
                        .eventMetadata("reason=" + dup.reason())
                        .build());
                continue;
            }
            try {
                Lead lead = persistenceService.createLeadForCampaign(candidate, campaign);
                leadEventRepository.save(LeadEvent.builder()
                        .leadId(lead.getId()).campaignId(campaign.getId())
                        .eventType("lead_found").eventMetadata("source=" + candidate.getSource()).build());
                discovered++;
                campaign.setDiscoveredCount(discovered);
                campaign.setProgressCurrent(discovered);
                campaignRepository.save(campaign);
            } catch (DataIntegrityViolationException e) {
                log.warn("Conflito de unicidade ao inserir lead (possível duplicata): {}", candidate.getBusinessName());
            }
        }
        campaign.setDiscoveredCount(discovered);
        campaignRepository.save(campaign);

        if (result.outcome() == LeadDiscoveryResult.DiscoveryOutcome.EMPTY) {
            throw new ApiException(HttpStatus.NOT_FOUND, "EMPTY", "Nenhum lead com dados de contato encontrado na cidade");
        }
    }

    private void analyzeStage(Campaign campaign) {
        if (campaign.getDiscoveredCount() == 0) return;
        campaign.setStatus("ANALYZING");
        campaign.setProgressStage("Analisando leads");
        campaign.setProgressTotal(campaign.getDiscoveredCount());
        campaign.setProgressCurrent(0);
        campaignRepository.save(campaign);

        List<Lead> leads = leadRepository.findByCampaign(campaign.getId());
        int analyzed = 0;
        int messages = 0;
        int current = 0;
        for (Lead lead : leads) {
            if (lead.isDoNotContact()) {
                current++;
                campaign.setProgressCurrent(current);
                continue;
            }
            try {
                leadAnalysisService.analyzeAndGenerate(lead, campaign.getId());
                analyzed++;
                messages++;
            } catch (RuntimeException e) {
                lead.setStatus("ERROR");
                leadRepository.save(lead);
                leadEventRepository.save(LeadEvent.builder()
                        .leadId(lead.getId()).campaignId(campaign.getId())
                        .eventType("lead_analysis_error").eventMetadata(truncate(e.getMessage())).build());
                log.warn("Erro ao analisar lead {}: {}", lead.getId(), e.getMessage());
            }
            current++;
            campaign.setProgressCurrent(current);
            campaign.setAnalyzedCount(analyzed);
            campaign.setMessageCount(messages);
            campaignRepository.save(campaign);
        }
    }

    private void analyzeOnlyPendingOrError(Campaign campaign) {
        List<Lead> leads = leadRepository.findByCampaign(campaign.getId());
        if (leads.isEmpty()) return;

        campaign.setStatus("ANALYZING");
        campaign.setProgressStage("Reanalisando pendentes e erros");
        campaign.setProgressTotal(leads.size());
        campaign.setProgressCurrent(0);
        campaignRepository.save(campaign);

        int analyzed = 0;
        int messages = 0;
        int current = 0;
        for (Lead lead : leads) {
            if (lead.isDoNotContact()) {
                current++;
                campaign.setProgressCurrent(current);
                continue;
            }
            String status = lead.getStatus();
            if (!"NEW".equals(status) && !"ERROR".equals(status)) {
                current++;
                campaign.setProgressCurrent(current);
                continue;
            }
            try {
                leadAnalysisService.analyzeAndGenerate(lead, campaign.getId());
                analyzed++;
                messages++;
            } catch (RuntimeException e) {
                lead.setStatus("ERROR");
                leadRepository.save(lead);
                leadEventRepository.save(LeadEvent.builder()
                        .leadId(lead.getId()).campaignId(campaign.getId())
                        .eventType("lead_analysis_error").eventMetadata(truncate(e.getMessage())).build());
                log.warn("Erro ao analisar lead {}: {}", lead.getId(), e.getMessage());
            }
            current++;
            campaign.setProgressCurrent(current);
            campaign.setAnalyzedCount(analyzed);
            campaign.setMessageCount(messages);
            campaignRepository.save(campaign);
        }
    }

    private void finalizeCampaign(Campaign campaign) {
        long discovered = campaignLeadRepository.countByCampaignId(campaign.getId());
        long analyzed = leadRepository.countByCampaignIdWithAnalysis(campaign.getId());
        long messages = leadRepository.countByCampaignIdAndStatusIn(campaign.getId(),
                List.of("MESSAGE_READY", "APPROVED", "SENT", "REPLIED", "INTERESTED", "SCHEDULED", "CONVERTED"));
        long errors = leadRepository.countByCampaignIdAndStatusIn(campaign.getId(), List.of("ERROR"));

        if (discovered == 0) {
            campaign.setStatus("FAILED");
            campaign.setErrorMessage("Nenhum lead encontrado para os parâmetros informados.");
        } else if (discovered < campaign.getRequestedQuantity()) {
            campaign.setStatus("PARTIAL");
        } else if (errors > 0) {
            campaign.setStatus("PARTIAL");
        } else if (analyzed >= campaign.getRequestedQuantity() && messages >= campaign.getRequestedQuantity()) {
            campaign.setStatus("COMPLETED");
        } else {
            campaign.setStatus("PARTIAL");
        }
        campaign.setProgressStage(null);
        campaign.setProgressCurrent((int) discovered);
        campaign.setProgressTotal((int) discovered);
        campaignRepository.save(campaign);
        log.info("Campanha {} finalizada com status {} (discovered={}, analyzed={}, messages={}, errors={})",
                campaign.getId(), campaign.getStatus(), discovered, analyzed, messages, errors);
    }

    public void recompute(Campaign campaign) {
        long discoveredCount = campaignLeadRepository.countByCampaignId(campaign.getId());
        campaign.setDiscoveredCount((int) discoveredCount);
        long analyzed = leadRepository.countByCampaignIdWithAnalysis(campaign.getId());
        campaign.setAnalyzedCount((int) analyzed);
        long messages = leadRepository.countByCampaignIdAndStatusIn(campaign.getId(),
                List.of("MESSAGE_READY", "APPROVED", "SENT", "REPLIED", "INTERESTED", "SCHEDULED", "CONVERTED"));
        campaign.setMessageCount((int) messages);
    }

    private String batchKey(LeadCandidate c) {
        String s = normalizer.normalizeSourceId(c.getSource(), c.getSourceId());
        if (s != null) return "src:" + s;
        String ig = normalizer.normalizeInstagram(c.getInstagramUsername() != null ? c.getInstagramUsername() : c.getInstagramUrl());
        if (ig != null) return "ig:" + ig;
        String w = normalizer.normalizeWebsite(c.getWebsite());
        if (w != null) return "web:" + w;
        String p = normalizer.normalizePhone(c.getPhone());
        if (p != null) return "ph:" + p;
        String e = normalizer.normalizeEmail(c.getEmail());
        if (e != null) return "em:" + e;
        String n = normalizer.normalizeName(c.getBusinessName());
        if (n != null && c.getCity() != null && c.getCountry() != null) return "nm:" + n + "|" + c.getCity() + "|" + c.getCountry();
        return null;
    }

    private String truncate(String s) {
        if (s == null) return null;
        return s.length() > 500 ? s.substring(0, 500) : s;
    }
}