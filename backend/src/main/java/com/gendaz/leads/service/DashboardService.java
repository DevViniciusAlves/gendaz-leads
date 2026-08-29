package com.gendaz.leads.service;

import com.gendaz.leads.dto.dashboard.DashboardResponse;
import com.gendaz.leads.entity.User;
import com.gendaz.leads.exception.ApiException;
import com.gendaz.leads.repository.CampaignRepository;
import com.gendaz.leads.repository.LeadRepository;
import com.gendaz.leads.repository.UserRepository;
import com.gendaz.leads.security.SecurityService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class DashboardService {

    private static final List<String> ACTIVE_STATUSES = List.of("CREATED", "DISCOVERING", "ANALYZING", "GENERATING");

    private final LeadRepository leadRepository;
    private final CampaignRepository campaignRepository;
    private final UserRepository userRepository;
    private final SecurityService securityService;

    public DashboardService(LeadRepository leadRepository, CampaignRepository campaignRepository,
                            UserRepository userRepository, SecurityService securityService) {
        this.leadRepository = leadRepository;
        this.campaignRepository = campaignRepository;
        this.userRepository = userRepository;
        this.securityService = securityService;
    }

    private Long currentUserId() {
        String email = securityService.currentEmail();
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "Usuario nao encontrado."));
        return user.getId();
    }

    public DashboardResponse compute() {
        Long userId = currentUserId();
        long total = leadRepository.countOwnedTotal(userId);
        Map<String, Long> breakdown = new HashMap<>();
        for (Object[] row : leadRepository.countOwnedByStatus(userId)) {
            breakdown.put((String) row[0], ((Number) row[1]).longValue());
        }
        long newLeads = breakdown.getOrDefault("NEW", 0L)
                + breakdown.getOrDefault("ANALYZING", 0L)
                + breakdown.getOrDefault("ERROR", 0L);
        long analyzed = sum(breakdown, List.of("ANALYZED", "MESSAGE_READY", "APPROVED", "SENT", "REPLIED", "INTERESTED", "SCHEDULED", "CONVERTED"));
        long messageReady = sum(breakdown, List.of("MESSAGE_READY", "APPROVED"));
        long approved = sum(breakdown, List.of("APPROVED", "SENT", "REPLIED", "INTERESTED", "SCHEDULED", "CONVERTED"));
        long sent = sum(breakdown, List.of("SENT", "REPLIED", "INTERESTED", "SCHEDULED", "CONVERTED"));
        long replied = sum(breakdown, List.of("REPLIED", "INTERESTED", "SCHEDULED", "CONVERTED"));
        long interested = sum(breakdown, List.of("INTERESTED", "SCHEDULED", "CONVERTED"));
        long converted = breakdown.getOrDefault("CONVERTED", 0L);
        long blocked = breakdown.getOrDefault("DO_NOT_CONTACT", 0L);
        long activeCampaigns = campaignRepository.countByOwnerIdAndStatusIn(userId, ACTIVE_STATUSES);

        return new DashboardResponse(total, newLeads, analyzed, messageReady, approved, sent, replied,
                interested, converted, blocked, activeCampaigns, breakdown);
    }

    private long sum(Map<String, Long> map, List<String> keys) {
        long total = 0;
        for (String k : keys) total += map.getOrDefault(k, 0L);
        return total;
    }
}
