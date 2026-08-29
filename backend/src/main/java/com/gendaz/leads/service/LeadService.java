package com.gendaz.leads.service;

import com.gendaz.leads.dto.lead.LeadResponse;
import com.gendaz.leads.dto.lead.UpdateLeadMessageRequest;
import com.gendaz.leads.entity.Campaign;
import com.gendaz.leads.entity.CampaignLead;
import com.gendaz.leads.entity.Lead;
import com.gendaz.leads.entity.LeadAnalysis;
import com.gendaz.leads.entity.LeadEvent;
import com.gendaz.leads.entity.LeadMessage;
import com.gendaz.leads.entity.MessageSend;
import com.gendaz.leads.entity.User;
import com.gendaz.leads.exception.ApiException;
import com.gendaz.leads.repository.CampaignLeadRepository;
import com.gendaz.leads.repository.CampaignRepository;
import com.gendaz.leads.repository.LeadAnalysisRepository;
import com.gendaz.leads.repository.LeadEventRepository;
import com.gendaz.leads.repository.LeadMessageRepository;
import com.gendaz.leads.repository.LeadRepository;
import com.gendaz.leads.repository.MessageSendRepository;
import com.gendaz.leads.repository.UserRepository;
import com.gendaz.leads.security.SecurityService;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Service
public class LeadService {

    private static final List<String> ALLOWED_STATUSES = List.of(
            "ANALYZED", "MESSAGE_READY", "REPLIED", "INTERESTED", "SCHEDULED", "CONVERTED", "NOT_INTERESTED");
    private static final List<String> MESSAGE_READY_LIKE = List.of(
            "MESSAGE_READY", "APPROVED", "SENT", "REPLIED", "INTERESTED", "SCHEDULED", "CONVERTED");

    @Value("${app.messaging.provider:log}")
    private String providerName;

    private final LeadRepository leadRepository;
    private final LeadMessageRepository leadMessageRepository;
    private final LeadAnalysisRepository leadAnalysisRepository;
    private final CampaignLeadRepository campaignLeadRepository;
    private final CampaignRepository campaignRepository;
    private final MessageSendRepository messageSendRepository;
    private final LeadEventRepository leadEventRepository;
    private final UserRepository userRepository;
    private final LeadAnalysisService leadAnalysisService;
    private final LeadMapper leadMapper;
    private final SecurityService securityService;

    public LeadService(LeadRepository leadRepository, LeadMessageRepository leadMessageRepository,
                       LeadAnalysisRepository leadAnalysisRepository, CampaignLeadRepository campaignLeadRepository,
                       CampaignRepository campaignRepository, MessageSendRepository messageSendRepository,
                       LeadEventRepository leadEventRepository, UserRepository userRepository,
                       LeadAnalysisService leadAnalysisService, LeadMapper leadMapper, SecurityService securityService) {
        this.leadRepository = leadRepository;
        this.leadMessageRepository = leadMessageRepository;
        this.leadAnalysisRepository = leadAnalysisRepository;
        this.campaignLeadRepository = campaignLeadRepository;
        this.campaignRepository = campaignRepository;
        this.messageSendRepository = messageSendRepository;
        this.leadEventRepository = leadEventRepository;
        this.userRepository = userRepository;
        this.leadAnalysisService = leadAnalysisService;
        this.leadMapper = leadMapper;
        this.securityService = securityService;
    }

    private Long currentUserId() {
        String email = securityService.currentEmail();
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "Usuario nao encontrado."));
        return user.getId();
    }

    private void ensureAccess(Lead lead) {
        if (leadRepository.countOwnedByUser(lead.getId(), currentUserId()) == 0) {
            throw new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "Acesso negado a este lead.");
        }
    }

    public Page<LeadResponse> list(Long campaignId, String status, String search, Pageable pageable) {
        Long userId = currentUserId();
        if (campaignId != null) {
            Campaign campaign = campaignRepository.findById(campaignId)
                    .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Campanha nao encontrada."));
            if (!campaign.getOwnerId().equals(userId)) {
                throw new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "Acesso negado.");
            }
        }
        Specification<Lead> spec = buildSpecification(userId, campaignId, status, search);
        Page<Lead> page = leadRepository.findAll(spec, pageable);
        return page.map(this::toResponse);
    }

    private Specification<Lead> buildSpecification(Long userId, Long campaignId, String status, String search) {
        return (Root<Lead> root, CriteriaQuery<?> query, CriteriaBuilder cb) -> {
            List<Predicate> predicates = new ArrayList<>();

            Subquery<Long> ownedCamps = query.subquery(Long.class);
            Root<Campaign> cr = ownedCamps.from(Campaign.class);
            ownedCamps.select(cr.get("id"));
            ownedCamps.where(cb.equal(cr.get("ownerId"), userId));

            Subquery<Long> clSub = query.subquery(Long.class);
            Root<CampaignLead> clr = clSub.from(CampaignLead.class);
            clSub.select(clr.get("leadId"));
            List<Predicate> clPreds = new ArrayList<>();
            clPreds.add(cb.equal(clr.get("leadId"), root.get("id")));
            clPreds.add(clr.get("campaignId").in(ownedCamps));
            if (campaignId != null) {
                clPreds.add(cb.equal(clr.get("campaignId"), campaignId));
            }
            clSub.where(clPreds.toArray(new Predicate[0]));
            predicates.add(cb.exists(clSub));

            if (status != null && !status.isBlank()) {
                predicates.add(cb.equal(root.get("status"), status));
            }
            if (search != null && !search.isBlank()) {
                String like = "%" + search.toLowerCase() + "%";
                predicates.add(cb.or(
                        cb.like(cb.lower(root.get("businessName")), like),
                        cb.like(cb.lower(root.get("instagramUsername")), like),
                        cb.like(cb.lower(root.get("city")), like)));
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }

    public LeadResponse get(Long id) {
        Lead lead = leadRepository.findById(id)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Lead nao encontrado."));
        ensureAccess(lead);
        return toResponse(lead);
    }

    public org.springframework.data.domain.Page<com.gendaz.leads.entity.LeadEvent> events(Long id, org.springframework.data.domain.Pageable pageable) {
        Lead lead = leadRepository.findById(id)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Lead nao encontrado."));
        ensureAccess(lead);
        return leadEventRepository.findByLeadIdOrderByCreatedAtDesc(id, pageable);
    }

    @Transactional
    public LeadResponse editMessage(Long id, UpdateLeadMessageRequest request) {
        Lead lead = leadRepository.findById(id)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Lead nao encontrado."));
        ensureAccess(lead);
        LeadMessage msg = leadMessageRepository.findByLeadId(id)
                .orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST, "NO_MESSAGE", "Lead sem mensagem gerada."));
        msg.setMessageText(request.messageText());
        msg.setEdited(true);
        msg.setApproved(false);
        msg.setEditedAt(Instant.now());
        leadMessageRepository.save(msg);
        if ("APPROVED".equals(lead.getStatus())) {
            lead.setStatus("MESSAGE_READY");
            leadRepository.save(lead);
        }
        leadEventRepository.save(LeadEvent.builder().leadId(id).campaignId(lead.getCurrentCampaignId())
                .eventType("message_edited").build());
        recountCampaign(lead.getCurrentCampaignId());
        return toResponse(lead);
    }

    @Transactional
    public LeadResponse approve(Long id) {
        Lead lead = leadRepository.findById(id)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Lead nao encontrado."));
        ensureAccess(lead);
        if (!MESSAGE_READY_LIKE.contains(lead.getStatus())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_STATE",
                    "O lead precisa ter mensagem pronta para ser aprovado.");
        }
        LeadMessage msg = leadMessageRepository.findByLeadId(id).orElse(null);
        if (msg != null) {
            msg.setApproved(true);
            leadMessageRepository.save(msg);
        }
        lead.setStatus("APPROVED");
        leadRepository.save(lead);
        leadEventRepository.save(LeadEvent.builder().leadId(id).campaignId(lead.getCurrentCampaignId())
                .eventType("message_approved").build());
        enqueueSend(lead);
        recountCampaign(lead.getCurrentCampaignId());
        return toResponse(lead);
    }

    @Transactional
    public LeadResponse doNotContact(Long id) {
        Lead lead = leadRepository.findById(id)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Lead nao encontrado."));
        ensureAccess(lead);
        lead.setDoNotContact(true);
        lead.setStatus("DO_NOT_CONTACT");
        leadRepository.save(lead);
        messageSendRepository.findByLeadId(id).forEach(s -> {
            if ("QUEUED".equals(s.getStatus()) || "SENDING".equals(s.getStatus())) {
                s.setStatus("SKIPPED");
                s.setResult("Lead marcado como nao prospectar.");
                messageSendRepository.save(s);
            }
        });
        leadEventRepository.save(LeadEvent.builder().leadId(id).campaignId(lead.getCurrentCampaignId())
                .eventType("lead_status_changed").eventMetadata("DO_NOT_CONTACT").build());
        recountCampaign(lead.getCurrentCampaignId());
        return toResponse(lead);
    }

    @Transactional
    public LeadResponse setStatus(Long id, String status) {
        Lead lead = leadRepository.findById(id)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Lead nao encontrado."));
        ensureAccess(lead);
        if (!ALLOWED_STATUSES.contains(status)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_STATUS", "Status invalido: " + status);
        }
        if (lead.isDoNotContact() && !"MESSAGE_READY".equals(status)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "BLOCKED", "Lead marcado como nao prospectar.");
        }
        lead.setStatus(status);
        leadRepository.save(lead);
        leadEventRepository.save(LeadEvent.builder().leadId(id).campaignId(lead.getCurrentCampaignId())
                .eventType("lead_status_changed").eventMetadata(status).build());
        recountCampaign(lead.getCurrentCampaignId());
        return toResponse(lead);
    }

    @Transactional
    public LeadResponse regenerate(Long id) {
        Lead lead = leadRepository.findById(id)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Lead nao encontrado."));
        ensureAccess(lead);
        if (lead.isDoNotContact()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "BLOCKED", "Lead marcado como nao prospectar.");
        }
        leadAnalysisService.analyzeAndGenerate(lead, lead.getCurrentCampaignId());
        return toResponse(lead);
    }

    private void enqueueSend(Lead lead) {
        boolean already = messageSendRepository.findByLeadId(lead.getId()).stream()
                .anyMatch(s -> "SENT".equals(s.getStatus()) || "SENDING".equals(s.getStatus()));
        if (already) return;
        MessageSend send = MessageSend.builder()
                .leadId(lead.getId())
                .campaignId(lead.getCurrentCampaignId())
                .provider(providerName)
                .status("QUEUED")
                .build();
        messageSendRepository.save(send);
    }

    private void recountCampaign(Long campaignId) {
        if (campaignId == null) return;
        Campaign campaign = campaignRepository.findById(campaignId).orElse(null);
        if (campaign == null) return;
        List<Lead> leads = leadRepository.findByCampaign(campaignId);
        int approved = 0, sent = 0, replied = 0, interested = 0, converted = 0, blocked = 0, messages = 0;
        for (Lead l : leads) {
            if (l.isDoNotContact()) blocked++;
            if (MESSAGE_READY_LIKE.contains(l.getStatus())) messages++;
            if (List.of("APPROVED", "SENT", "REPLIED", "INTERESTED", "SCHEDULED", "CONVERTED").contains(l.getStatus())) approved++;
            if (List.of("SENT", "REPLIED", "INTERESTED", "SCHEDULED", "CONVERTED").contains(l.getStatus())) sent++;
            if (List.of("REPLIED", "INTERESTED", "SCHEDULED", "CONVERTED").contains(l.getStatus())) replied++;
            if (List.of("INTERESTED", "SCHEDULED", "CONVERTED").contains(l.getStatus())) interested++;
            if ("CONVERTED".equals(l.getStatus())) converted++;
        }
        campaign.setApprovedCount(approved);
        campaign.setSentCount(sent);
        campaign.setRepliedCount(replied);
        campaign.setInterestedCount(interested);
        campaign.setConvertedCount(converted);
        campaign.setBlockedCount(blocked);
        campaign.setMessageCount(messages);
        campaignRepository.save(campaign);
    }

    public LeadResponse toResponse(Lead lead) {
        LeadAnalysis analysis = leadAnalysisRepository.findByLeadId(lead.getId()).orElse(null);
        LeadMessage message = leadMessageRepository.findByLeadId(lead.getId()).orElse(null);
        String campaignName = null;
        if (lead.getCurrentCampaignId() != null) {
            Campaign c = campaignRepository.findById(lead.getCurrentCampaignId()).orElse(null);
            campaignName = c != null ? c.getName() : null;
        }
        return leadMapper.toResponse(lead, analysis, message, campaignName);
    }
}
