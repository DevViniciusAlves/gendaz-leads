package com.gendaz.leads.service;

import com.gendaz.leads.entity.Campaign;
import com.gendaz.leads.exception.ApiException;
import com.gendaz.leads.repository.CampaignLeadRepository;
import com.gendaz.leads.repository.CampaignRepository;
import com.gendaz.leads.repository.LeadRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class CampaignRetryTransactionService {

    private final CampaignRepository campaignRepository;
    private final CampaignLeadRepository campaignLeadRepository;
    private final LeadRepository leadRepository;

    public CampaignRetryTransactionService(CampaignRepository campaignRepository, CampaignLeadRepository campaignLeadRepository, LeadRepository leadRepository) {
        this.campaignRepository = campaignRepository;
        this.campaignLeadRepository = campaignLeadRepository;
        this.leadRepository = leadRepository;
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
        
        if ("CREATED".equals(status) || "DISCOVERING".equals(status) || 
            "ANALYZING".equals(status) || "GENERATING".equals(status)) {
             throw new ApiException(HttpStatus.CONFLICT, "CAMPAIGN_ALREADY_PROCESSING", "Esta campanha já está sendo processada.");
        }

        long existingCount = campaignLeadRepository.countByCampaignId(id);
        long analyzedCount = leadRepository.countByCampaignIdWithAnalysis(id);
        long errorCount = leadRepository.countByCampaignIdAndStatusIn(id, List.of("ERROR"));
        
        if ("FAILED".equals(status) && existingCount == 0) {
             campaign.setErrorMessage(null);
             campaign.setProgressCurrent(0);
             campaign.setProgressStage("DISCOVERY");
             campaign.setStatus("DISCOVERING");
             campaignRepository.save(campaign);
             return RetryPlan.DISCOVERY;
        } 
        
        else if ("FAILED".equals(status) || "ERROR".equals(status)) {
             return RetryPlan.ANALYSIS;
        } 
        
        else if ("PARTIAL".equals(status)) {
             if (existingCount < campaign.getRequestedQuantity()) {
                 return RetryPlan.PARTIAL;
             } else if (errorCount > 0) {
                 return RetryPlan.ANALYSIS;
             } else if (analyzedCount < campaign.getRequestedQuantity()) {
                 return RetryPlan.ANALYSIS;
             } else {
                 return RetryPlan.ANALYSIS;
             }
        } 
        
        else {
             throw new ApiException(HttpStatus.CONFLICT, "INVALID_STATUS", "Status de campanha inválido para retry.");
        }
    }
}
