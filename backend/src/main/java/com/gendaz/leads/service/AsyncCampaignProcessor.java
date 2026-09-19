package com.gendaz.leads.service;

import com.gendaz.leads.domain.LeadCandidate;
import com.gendaz.leads.entity.Campaign;
import com.gendaz.leads.entity.CampaignLead;
import com.gendaz.leads.entity.Lead;
import com.gendaz.leads.entity.LeadEvent;
import com.gendaz.leads.entity.LeadSource;
import com.gendaz.leads.exception.ApiException;
import com.gendaz.leads.repository.CampaignLeadRepository;
import com.gendaz.leads.repository.CampaignRepository;
import com.gendaz.leads.repository.LeadEventRepository;
import com.gendaz.leads.repository.LeadRepository;
import com.gendaz.leads.repository.LeadSourceRepository;
import com.gendaz.leads.service.provider.OpenStreetMapProvider;
import com.gendaz.leads.util.Normalizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Service
public class AsyncCampaignProcessor {

    private static final Logger log = LoggerFactory.getLogger(AsyncCampaignProcessor.class);

    @Value("${app.limits.discovery-multiplier:3}")
    private int discoveryMultiplier;

    private final CampaignRepository campaignRepository;
    private final LeadRepository leadRepository;
    private final CampaignLeadRepository campaignLeadRepository;
    private final LeadSourceRepository leadSourceRepository;
    private final LeadEventRepository leadEventRepository;
    private final LeadAnalysisService leadAnalysisService;
    private final DeduplicationService deduplicationService;
    private final Normalizer normalizer;
    private final OpenStreetMapProvider openStreetMapProvider;

    public AsyncCampaignProcessor(CampaignRepository campaignRepository, LeadRepository leadRepository,
                                  CampaignLeadRepository campaignLeadRepository, LeadSourceRepository leadSourceRepository,
                                  LeadEventRepository leadEventRepository, LeadAnalysisService leadAnalysisService,
                                  DeduplicationService deduplicationService, Normalizer normalizer,
                                  OpenStreetMapProvider openStreetMapProvider) {
        this.campaignRepository = campaignRepository;
        this.leadRepository = leadRepository;
        this.campaignLeadRepository = campaignLeadRepository;
        this.leadSourceRepository = leadSourceRepository;
        this.leadEventRepository = leadEventRepository;
        this.leadAnalysisService = leadAnalysisService;
        this.deduplicationService = deduplicationService;
        this.normalizer = normalizer;
        this.openStreetMapProvider = openStreetMapProvider;
    }

    @Async
    public void processCampaign(Long campaignId) {
        Campaign campaign = campaignRepository.findById(campaignId).orElse(null);
        if (campaign == null) return;
        synchronized (campaign) {
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
    }

    @Async
    public void retry(Long campaignId) {
        Campaign campaign = campaignRepository.findById(campaignId).orElse(null);
        if (campaign == null) return;

        // 409 guard: se ja nao esta em estado FAILED, nao re-tente.
        if (!"FAILED".equals(campaign.getStatus())) {
            log.warn("Campanha {} nao pode ser re-tentada: status atual={}", campaignId, campaign.getStatus());
            throw new ApiException(org.springframework.http.HttpStatus.CONFLICT,
                    "CONCURRENT_PROCESSING", "Campanha nao esta em estado FAILED; nao ha o que re-tentar.");
        }

        // CAS A: FAILED + discoveredCount=0 => restart discovery via processCampaign
        if (campaign.getDiscoveredCount() == 0) {
            log.info("CAS A: campanha {} FAILED sem leads descobertos -> reiniciando discovery", campaignId);
            try {
                discoverStage(campaign);
                analyzeStage(campaign);
                finalizeCampaign(campaign);
            } catch (Exception e) {
                log.error("CAS A falha ao reiniciar discovery para campanha {}: {}", campaignId, e.getMessage(), e);
                campaign.setStatus("FAILED");
                campaign.setErrorMessage(truncate(e.getMessage()));
                campaignRepository.save(campaign);
            }
            return;
        }

        // CAS B: FAILED com leads com status ERROR => retryFailedLeads
        List<Lead> leads = leadRepository.findByCampaign(campaign.getId());
        long errorLeads = leads.stream().filter(l -> "ERROR".equals(l.getStatus())).count();
        if (errorLeads > 0) {
            log.info("CAS B: campanha {} FAILED com {} leads com ERROR -> retryFailedLeads", campaignId, errorLeads);
            retryFailedLeads(campaignId);
            return;
        }

        // Nenhuma das condicoes aplica: ja tem leads nao-ERROR em campanha finalizada.
        log.warn("Campanha {} FAILED mas nao ha leads ERROR nem discoveredCount=0; nada a fazer.", campaignId);
    }

    @Async
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
        log.info("Reprocessamento de campanha {} concluido: {} leads recuperados", campaignId, done);
    }

    private void discoverStage(Campaign campaign) {
        campaign.setStatus("DISCOVERING");
        campaign.setProgressStage("Buscando leads");
        campaign.setProgressTotal(campaign.getRequestedQuantity());
        campaign.setProgressCurrent(0);
        campaignRepository.save(campaign);

        int multiplier = discoveryMultiplier <= 0 ? 3 : discoveryMultiplier;
        int totalBudget = campaign.getRequestedQuantity() * multiplier;
        // Garante margem minima para compensar duplicados/invalidos e impõe teto
        // para nao gerar consultas gigantes no Overpass.
        totalBudget = Math.max(totalBudget, campaign.getRequestedQuantity() + 10);
        totalBudget = Math.min(totalBudget, 200);

        List<LeadCandidate> candidates = new ArrayList<>();

        try {
            if (openStreetMapProvider.isEnabled()) {
                candidates.addAll(openStreetMapProvider.discover(campaign.getNiche(), campaign.getLocation(), totalBudget));
            } else {
                log.warn("OpenStreetMapProvider nao esta habilitado para campanha {}", campaign.getId());
            }
        } catch (ApiException e) {
            // Provider falhou (erro tipado): propaga para processCampaign marcar FAILED
            // com errorMessage correto. NAO executa finalizeCampaign normal.
            log.warn("Provider {} falhou: {} ({})", openStreetMapProvider.getName(), e.getMessage(), e.getCode());
            throw e;
        } catch (RuntimeException e) {
            log.warn("Provider {} falhou: {}", openStreetMapProvider.getName(), e.getMessage());
            throw new ApiException(
                    org.springframework.http.HttpStatus.BAD_GATEWAY,
                    "OSM_OVERPASS_ERROR",
                    e.getMessage() != null ? e.getMessage() : "Falha ao consultar OpenStreetMap/Overpass.");
        }

        Set<String> batchSeen = new LinkedHashSet<>();
        int discovered = 0;
        for (LeadCandidate candidate : candidates) {
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
                Lead lead = createLeadForCampaign(candidate, campaign);
                leadEventRepository.save(LeadEvent.builder()
                        .leadId(lead.getId()).campaignId(campaign.getId())
                        .eventType("lead_found").eventMetadata("source=" + candidate.getSource()).build());
                discovered++;
                campaign.setDiscoveredCount(discovered);
                campaign.setProgressCurrent(discovered);
                campaignRepository.save(campaign);
            } catch (DataIntegrityViolationException e) {
                log.warn("Conflito de unicidade ao inserir lead (possivel duplicata): {}", candidate.getBusinessName());
            }
        }
        campaign.setDiscoveredCount(discovered);
        campaignRepository.save(campaign);
        // Zero real do provider (sem exception): segue para analyze/finalize,
        // que marcara "Nenhum lead encontrado para os parametros informados."
    }

    private void analyzeStage(Campaign campaign) {
        if (campaign.getDiscoveredCount() == 0) return;
        campaign.setStatus("ANALYZING");
        campaign.setProgressStage("Analisando");
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

    private void finalizeCampaign(Campaign campaign) {
        if (campaign.getDiscoveredCount() == 0) {
            campaign.setStatus("FAILED");
            campaign.setErrorMessage("Nenhum lead encontrado para os parametros informados.");
        } else if (campaign.getDiscoveredCount() < campaign.getRequestedQuantity()) {
            campaign.setStatus("PARTIAL");
        } else {
            campaign.setStatus("COMPLETED");
        }
        campaign.setProgressStage(null);
        campaign.setProgressCurrent(campaign.getDiscoveredCount());
        campaign.setProgressTotal(campaign.getDiscoveredCount());
        campaignRepository.save(campaign);
        log.info("Campanha {} finalizada com status {}", campaign.getId(), campaign.getStatus());
    }

    private void recompute(Campaign campaign) {
        List<Lead> leads = leadRepository.findByCampaign(campaign.getId());
        campaign.setDiscoveredCount(leads.size());
        int analyzed = 0, messages = 0;
        for (Lead l : leads) {
            if ("MESSAGE_READY".equals(l.getStatus()) || "APPROVED".equals(l.getStatus())
                    || "SENT".equals(l.getStatus()) || "REPLIED".equals(l.getStatus())
                    || "INTERESTED".equals(l.getStatus()) || "SCHEDULED".equals(l.getStatus())
                    || "CONVERTED".equals(l.getStatus())) {
                analyzed++;
                messages++;
            }
        }
        campaign.setAnalyzedCount(analyzed);
        campaign.setMessageCount(messages);
    }

    @Transactional
    protected Lead createLeadForCampaign(LeadCandidate candidate, Campaign campaign) {
        Lead lead = Lead.builder()
                .businessName(candidate.getBusinessName())
                .normalizedName(normalizer.normalizeName(candidate.getBusinessName()))
                .category(candidate.getCategory())
                .address(candidate.getAddress())
                .city(candidate.getCity())
                .state(candidate.getState())
                .country(candidate.getCountry() != null ? candidate.getCountry() : "BR")
                .phone(candidate.getPhone())
                .normalizedPhone(normalizer.normalizePhone(candidate.getPhone()))
                .website(candidate.getWebsite())
                .normalizedWebsite(normalizer.normalizeWebsite(candidate.getWebsite()))
                .instagramUsername(candidate.getInstagramUsername())
                .instagramUrl(candidate.getInstagramUrl())
                .instagramStatus(candidate.getInstagramStatus())
                .normalizedInstagram(normalizer.normalizeInstagram(
                        candidate.getInstagramUsername() != null ? candidate.getInstagramUsername() : candidate.getInstagramUrl()))
                .source(candidate.getSource())
                .sourceId(candidate.getSourceId())
                .normalizedSourceId(normalizer.normalizeSourceId(candidate.getSource(), candidate.getSourceId()))
                .doNotContact(false)
                .status("NEW")
                .currentCampaignId(campaign.getId())
                .build();
        lead = leadRepository.save(lead);

        campaignLeadRepository.save(CampaignLead.builder()
                .campaignId(campaign.getId()).leadId(lead.getId()).build());
        leadSourceRepository.save(LeadSource.builder()
                .leadId(lead.getId()).source(candidate.getSource())
                .sourceId(candidate.getSourceId()).sourceUrl(candidate.getWebsite()).build());
        return lead;
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
        String n = normalizer.normalizeName(c.getBusinessName());
        if (n != null && c.getCity() != null && c.getState() != null) return "nm:" + n + "|" + c.getCity() + "|" + c.getState();
        return null;
    }

    private String truncate(String s) {
        if (s == null) return null;
        return s.length() > 500 ? s.substring(0, 500) : s;
    }
}