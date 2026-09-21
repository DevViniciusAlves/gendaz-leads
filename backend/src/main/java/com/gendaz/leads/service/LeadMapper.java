package com.gendaz.leads.service;

import com.gendaz.leads.dto.lead.LeadAnalysisResponse;
import com.gendaz.leads.dto.lead.LeadMessageResponse;
import com.gendaz.leads.dto.lead.LeadResponse;
import com.gendaz.leads.entity.Lead;
import com.gendaz.leads.entity.LeadAnalysis;
import com.gendaz.leads.entity.LeadMessage;
import org.springframework.stereotype.Component;

@Component
public class LeadMapper {

    public LeadResponse toResponse(Lead lead, LeadAnalysis analysis, LeadMessage message, String campaignName) {
        LeadAnalysisResponse a = analysis == null ? null : new LeadAnalysisResponse(
                analysis.getBusinessType(), analysis.getServices(), analysis.getDigitalPresence(),
                analysis.getUsesBookingSystem(), analysis.getBookingSystemStatus(), analysis.getDetectedSystem(),
                analysis.getManualAttendanceSignals(), analysis.getPainPoints(), analysis.getCommercialOpportunity(),
                analysis.getOpportunityScore(), analysis.getReasoningSummary(), analysis.getModel(),
                analysis.getAnalyzedAt());

        LeadMessageResponse m = message == null ? null : new LeadMessageResponse(
                message.getMessageText(), message.isEdited(), message.isApproved(),
                message.getGeneratedAt(), message.getEditedAt());

        return new LeadResponse(
                lead.getId(), lead.getBusinessName(), lead.getCategory(), lead.getAddress(),
                lead.getCity(), lead.getState(), lead.getCountry(), lead.getPhone(), lead.getEmail(),
                lead.getWebsite(),
                lead.getInstagramUsername(), lead.getInstagramUrl(), lead.getInstagramStatus(),
                lead.getSource(), lead.getSourceId(), lead.isDoNotContact(), lead.getStatus(),
                lead.getCurrentCampaignId(), campaignName, lead.getCreatedAt(), a, m);
    }
}
