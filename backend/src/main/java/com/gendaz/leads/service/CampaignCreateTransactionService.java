package com.gendaz.leads.service;

import com.gendaz.leads.dto.campaign.CreateCampaignRequest;
import com.gendaz.leads.entity.Campaign;
import com.gendaz.leads.entity.User;
import com.gendaz.leads.exception.ApiException;
import com.gendaz.leads.repository.CampaignRepository;
import com.gendaz.leads.repository.UserRepository;
import com.gendaz.leads.security.SecurityService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CampaignCreateTransactionService {

    @Value("${app.limits.min-leads:3}")
    private int minLeads;

    @Value("${app.limits.max-leads:30}")
    private int maxLeads;

    private final CampaignRepository campaignRepository;
    private final UserRepository userRepository;
    private final SecurityService securityService;

    public CampaignCreateTransactionService(CampaignRepository campaignRepository, UserRepository userRepository, SecurityService securityService) {
        this.campaignRepository = campaignRepository;
        this.userRepository = userRepository;
        this.securityService = securityService;
    }

    @Transactional
    public Campaign createCampaign(CreateCampaignRequest request) {
        if (request.quantity() < minLeads || request.quantity() > maxLeads) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_QUANTITY",
                    String.format("A quantidade deve estar entre %d e %d leads.", minLeads, maxLeads));
        }
        
        String email = securityService.currentEmail();
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "Usuario nao encontrado."));
        
        Campaign campaign = Campaign.builder()
                .ownerId(user.getId())
                .name(String.format("%s — %s", request.niche(), request.location()))
                .niche(request.niche())
                .location(request.location())
                .requestedQuantity(request.quantity())
                .status("CREATED")
                .build();
        
        return campaignRepository.saveAndFlush(campaign);
    }
}
