package com.gendaz.leads.service;

import com.gendaz.leads.dto.campaign.CampaignResponse;
import com.gendaz.leads.dto.campaign.CreateCampaignRequest;
import com.gendaz.leads.entity.Campaign;
import com.gendaz.leads.entity.User;
import com.gendaz.leads.exception.ApiException;
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
    private final AsyncCampaignProcessor processor;
    private final SecurityService securityService;

    public CampaignService(CampaignRepository campaignRepository, UserRepository userRepository,
                           AsyncCampaignProcessor processor, SecurityService securityService) {
        this.campaignRepository = campaignRepository;
        this.userRepository = userRepository;
        this.processor = processor;
        this.securityService = securityService;
    }

    private Long currentUserId() {
        String email = securityService.currentEmail();
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "Usuario nao encontrado."));
        return user.getId();
    }

    @Transactional
    public CampaignResponse create(CreateCampaignRequest request) {
        if (request.quantity() < minLeads || request.quantity() > maxLeads) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_QUANTITY",
                    String.format("A quantidade deve estar entre %d e %d leads.", minLeads, maxLeads));
        }
        Long ownerId = currentUserId();
        Campaign campaign = Campaign.builder()
                .ownerId(ownerId)
                .name(String.format("%s — %s", request.niche(), request.location()))
                .niche(request.niche())
                .location(request.location())
                .requestedQuantity(request.quantity())
                .status("CREATED")
                .build();
        campaign = campaignRepository.save(campaign);
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

    @Transactional
    public void retry(Long id) {
        // 1. Lock for update
        Campaign campaign = campaignRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Campanha nao encontrada."));
        ensureOwner(campaign);

        String status = campaign.getStatus();

        // 2. validar status atual
        if ("CREATED".equals(status) || "DISCOVERING".equals(status) || 
            "ANALYZING".equals(status) || "GENERATING".equals(status)) {
             throw new ApiException(HttpStatus.CONFLICT, "CAMPAIGN_ALREADY_PROCESSING", "Esta campanha já está sendo processada.");
        }

        // 3. decidir qual retry executar
        if ("FAILED".equals(status) && campaign.getDiscoveredCount() == 0) {
             // Caso A: FAILED + ZERO LEADS -> Reinicia DISCOVERY real.
             campaign.setErrorMessage(null);
             campaign.setProgressCurrent(0);
             campaign.setProgressStage("DISCOVERY");
             campaign.setStatus("DISCOVERING");
             campaignRepository.save(campaign);
             // 4. disparar processamento apropriado (fora da transação)
             processor.processCampaign(id);
        } else if ("FAILED".equals(status) || "ERROR".equals(status)) {
             // Caso B: FAILED COM LEADS ERROR -> retry específico de análise
             processor.retryFailedLeads(id);
        } else if ("PARTIAL".equals(status)) {
             // Caso C: PARTIAL -> Tentar completar.
             processor.retryFailedLeads(id); // Placeholder para PARTIAL, precisará de ajuste
        } else {
             throw new ApiException(HttpStatus.CONFLICT, "INVALID_STATUS", "Status de campanha inválido para retry.");
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
