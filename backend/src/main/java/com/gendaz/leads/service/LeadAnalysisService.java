package com.gendaz.leads.service;

import com.gendaz.leads.domain.AnalysisResult;
import com.gendaz.leads.domain.LeadCandidate;
import com.gendaz.leads.entity.Lead;
import com.gendaz.leads.entity.LeadAnalysis;
import com.gendaz.leads.entity.LeadEvent;
import com.gendaz.leads.entity.LeadMessage;
import com.gendaz.leads.repository.LeadAnalysisRepository;
import com.gendaz.leads.repository.LeadEventRepository;
import com.gendaz.leads.repository.LeadMessageRepository;
import com.gendaz.leads.repository.LeadRepository;
import com.gendaz.leads.service.BookingSystemDetector.Detection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class LeadAnalysisService {

    private static final Logger log = LoggerFactory.getLogger(LeadAnalysisService.class);

    private final LeadRepository leadRepository;
    private final LeadAnalysisRepository leadAnalysisRepository;
    private final LeadMessageRepository leadMessageRepository;
    private final LeadEventRepository leadEventRepository;
    private final GroqService groqService;
    private final BookingSystemDetector bookingSystemDetector;
    private final ScoringService scoringService;

    public LeadAnalysisService(LeadRepository leadRepository, LeadAnalysisRepository leadAnalysisRepository,
                               LeadMessageRepository leadMessageRepository, LeadEventRepository leadEventRepository,
                               GroqService groqService, BookingSystemDetector bookingSystemDetector,
                               ScoringService scoringService) {
        this.leadRepository = leadRepository;
        this.leadAnalysisRepository = leadAnalysisRepository;
        this.leadMessageRepository = leadMessageRepository;
        this.leadEventRepository = leadEventRepository;
        this.groqService = groqService;
        this.bookingSystemDetector = bookingSystemDetector;
        this.scoringService = scoringService;
    }

    public void analyzeAndGenerate(Lead lead, Long campaignId) {
        if (lead.isDoNotContact()) {
            return;
        }
        LeadCandidate candidate = toCandidate(lead);
        Detection detection = bookingSystemDetector.detect(candidate);
        AnalysisResult analysis = groqService.analyze(candidate, detection);
        int score = scoringService.compute(candidate, analysis, detection);

        AnalysisResult finalAnalysis = new AnalysisResult(
                analysis.businessType(), analysis.services(), analysis.digitalPresence(),
                analysis.usesBookingSystem(), analysis.bookingSystemStatus(), analysis.detectedSystem(),
                analysis.manualAttendanceSignals(), analysis.painPoints(), analysis.commercialOpportunity(),
                score, analysis.reasoningSummary());

        persistAnalysisAndMessage(lead, campaignId, finalAnalysis, score, candidate);
    }

    @Transactional
    public void persistAnalysisAndMessage(Lead lead, Long campaignId, AnalysisResult finalAnalysis, int score, LeadCandidate candidate) {
        LeadAnalysis entity = LeadAnalysis.builder()
                .leadId(lead.getId())
                .businessType(orUnknown(finalAnalysis.businessType()))
                .services(orUnknown(finalAnalysis.services()))
                .digitalPresence(orUnknown(finalAnalysis.digitalPresence()))
                .usesBookingSystem(finalAnalysis.usesBookingSystem())
                .bookingSystemStatus(finalAnalysis.bookingSystemStatus())
                .detectedSystem(finalAnalysis.detectedSystem())
                .manualAttendanceSignals(orUnknown(finalAnalysis.manualAttendanceSignals()))
                .painPoints(orUnknown(finalAnalysis.painPoints()))
                .commercialOpportunity(orUnknown(finalAnalysis.commercialOpportunity()))
                .opportunityScore(score)
                .reasoningSummary(orUnknown(finalAnalysis.reasoningSummary()))
                .model(groqService.getModel())
                .build();
        leadAnalysisRepository.save(entity);

        String message = groqService.generateMessage(candidate, finalAnalysis);
        LeadMessage msg = LeadMessage.builder()
                .leadId(lead.getId())
                .messageText(message)
                .approved(false)
                .edited(false)
                .build();
        leadMessageRepository.save(msg);

        lead.setStatus("MESSAGE_READY");
        leadRepository.save(lead);

        leadEventRepository.save(LeadEvent.builder()
                .leadId(lead.getId()).campaignId(campaignId).eventType("lead_analysis_completed").build());
        leadEventRepository.save(LeadEvent.builder()
                .leadId(lead.getId()).campaignId(campaignId).eventType("message_generated").build());
        log.info("Lead {} analisado e mensagem gerada (score={}, model={})", lead.getId(), score, groqService.getModel());
    }

    private LeadCandidate toCandidate(Lead lead) {
        LeadCandidate c = new LeadCandidate(lead.getBusinessName(), lead.getSource(), lead.getSourceId());
        c.setCategory(lead.getCategory());
        c.setWebsite(lead.getWebsite());
        c.setInstagramUsername(lead.getInstagramUsername());
        c.setInstagramUrl(lead.getInstagramUrl());
        c.setPhone(lead.getPhone());
        c.setAddress(lead.getAddress());
        c.setCity(lead.getCity());
        c.setState(lead.getState());
        c.setCountry(lead.getCountry());
        return c;
    }

    private String orUnknown(String s) {
        return (s == null || s.isBlank()) ? "unknown" : s;
    }
}
