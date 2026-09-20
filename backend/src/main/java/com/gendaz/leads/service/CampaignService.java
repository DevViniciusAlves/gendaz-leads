package com.gendaz.leads.service;

import com.gendaz.leads.dto.campaign.CampaignResponse;
import com.gendaz.leads.dto.campaign.CreateCampaignRequest;
import com.gendaz.leads.entity.Campaign;
import com.gendaz.leads.entity.User;
import com.gendaz.leads.exception.ApiException;
import com.gendaz.leads.repository.CampaignLeadRepository;
import com.gendaz.leads.repository.CampaignRepository;
import com.gendaz.leads.repository.UserRepository;
import com.gendaz.leads.security.SecurityService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CampaignService {

    @Value("${app.limits.min-leads:3}")
    private int minLeads;

    @Value("${app.limits.max-leads:30}")
    private int maxLeads;

    private final CampaignRepository campaignRepository;
    private final UserRepository userRepository;
    private final CampaignLeadRepository campaignLeadRepository;
    private final AsyncCampaignProcessor processor;
    private final CampaignRetryTransactionService retryService;
    private final CampaignCreateTransactionService createTransactionService;
    private final SecurityService securityService;

    public CampaignService(CampaignRepository campaignRepository, UserRepository userRepository,
                           CampaignLeadRepository campaignLeadRepository,
                           AsyncCampaignProcessor processor,
                           CampaignRetryTransactionService retryService,
                           CampaignCreateTransactionService createTransactionService,
                           SecurityService securityService) {
        this.campaignRepository = campaignRepository;
        this.userRepository = userRepository;
        this.campaignLeadRepository = campaignLeadRepository;
        this.processor = processor;
        this.retryService = retryService;
        this.createTransactionService = createTransactionService;
        this.securityService = securityService;
    }

    private Long currentUserId() {
        String email = securityService.currentEmail();
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "Usuario nao encontrado."));
        return user.getId();
    }

    public CampaignResponse create(CreateCampaignRequest request) {
        Campaign campaign = createTransactionService.createCampaign(request);
        processor.processCampaign(campaign.getId());
        return toResponse(campaign);
    }

    public Page<CampaignResponse> list(Pageable pageable) {
        return campaignRepository.findByOwnerId(currentUserId(), pageable).map(this::toResponse);
    }

    public CampaignResponse get(Long id) {
        Campaign campaign = campaignRepository.findById(id)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Campanha nao encontrada."));
        ensureOwner(campaign);
        return toResponse(campaign);
    }

    public void retry(Long id) {
        Campaign campaign = requireOwnedCampaign(id);
        
        CampaignRetryTransactionService.RetryPlan plan = retryService.prepareRetry(id);
        
        if (plan == CampaignRetryTransactionService.RetryPlan.DISCOVERY) {
             processor.processCampaign(id);
        } else if (plan == CampaignRetryTransactionService.RetryPlan.ANALYSIS) {
             processor.retryFailedLeads(id);
} else if (plan == CampaignRetryTransactionService.RetryPlan.PARTIAL) {
              long existingCount = campaignLeadRepository.countByCampaignId(id);
              int remaining = campaign.getRequestedQuantity() - (int)existingCount;
              if (remaining <= 0) {
                  processor.recomputeAndFinalizeCampaign(campaign);
              } else {
                  processor.processPartialCampaign(id, remaining);
              }
         }
    }

    private void ensureOwner(Campaign campaign) {
        if (!campaign.getOwnerId().equals(currentUserId())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "Acesso negado a esta campanha.");
        }
    }

    public Campaign requireOwnedCampaign(Long campaignId) {
        Campaign campaign = campaignRepository.findById(campaignId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Campanha nao encontrada."));
        ensureOwner(campaign);
        return campaign;
    }

    public CampaignResponse toResponse(Campaign c) {
        return new CampaignResponse(c.getId(), c.getName(), c.getNiche(), c.getLocation(),
                c.getRequestedQuantity(), c.getStatus(), c.getDiscoveredCount(), c.getAnalyzedCount(),
                c.getMessageCount(), c.getApprovedCount(), c.getSentCount(), c.getRepliedCount(),
                c.getInterestedCount(), c.getConvertedCount(), c.getBlockedCount(),
                c.getProgressStage(), c.getProgressCurrent(), c.getProgressTotal(),
                c.getErrorMessage(), c.getCreatedAt(), c.getUpdatedAt());
    }
}
