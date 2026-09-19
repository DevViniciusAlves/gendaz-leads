package com.gendaz.leads.service;

import com.gendaz.leads.entity.Campaign;
import com.gendaz.leads.exception.ApiException;
import com.gendaz.leads.repository.CampaignRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CampaignRetryTransactionService {

    private final CampaignRepository campaignRepository;

    public CampaignRetryTransactionService(CampaignRepository campaignRepository) {
        this.campaignRepository = campaignRepository;
    }

    public enum RetryPlan {
        DISCOVERY,
        ANALYSIS,
        PARTIAL
    }

    @Transactional
    public RetryPlan prepareRetry(Long id) {
        Campaign campaign = campaignRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Campanha nao encontrada."));
        
        String status = campaign.getStatus();
        
        // 6. GUARD de Campaign processando
        if ("CREATED".equals(status) || "DISCOVERING".equals(status) || 
            "ANALYZING".equals(status) || "GENERATING".equals(status)) {
             throw new ApiException(HttpStatus.CONFLICT, "CAMPAIGN_ALREADY_PROCESSING", "Esta campanha já está sendo processada.");
        }

        // 7. FAILED + ZERO LEADS
        if ("FAILED".equals(status) && campaign.getDiscoveredCount() == 0) {
             campaign.setErrorMessage(null);
             campaign.setProgressCurrent(0);
             campaign.setProgressStage("DISCOVERY");
             campaign.setStatus("DISCOVERING");
             campaignRepository.save(campaign);
             return RetryPlan.DISCOVERY;
        } 
        
        // 8. FAILED COM LEADS ERROR
        else if ("FAILED".equals(status) || "ERROR".equals(status)) {
             return RetryPlan.ANALYSIS;
        } 
        
        // 9. PARTIAL RETRY
        else if ("PARTIAL".equals(status)) {
             return RetryPlan.PARTIAL;
        } 
        
        else {
             throw new ApiException(HttpStatus.CONFLICT, "INVALID_STATUS", "Status de campanha inválido para retry.");
        }
    }
}
