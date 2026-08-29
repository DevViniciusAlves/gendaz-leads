package com.gendaz.leads.service;

import com.gendaz.leads.domain.AnalysisResult;
import com.gendaz.leads.domain.LeadCandidate;
import com.gendaz.leads.service.BookingSystemDetector.Detection;
import org.springframework.stereotype.Service;

@Service
public class ScoringService {

    public int compute(LeadCandidate candidate, AnalysisResult analysis, Detection booking) {
        int signal = 0;

        if ("IDENTIFIED".equals(booking.status()) || "PROBABLE".equals(booking.status())) {
            signal += 0;
        } else if ("NOT_IDENTIFIED".equals(booking.status())) {
            signal += 30;
        } else {
            signal += 15;
        }

        String presence = analysis.digitalPresence();
        if ("none".equals(presence)) signal += 20;
        else if ("basic".equals(presence)) signal += 14;
        else if ("moderate".equals(presence)) signal += 8;

        if (candidate.getWebsite() != null) signal += 5;
        if (candidate.getInstagramUsername() != null) signal += 5;

        String cat = candidate.getCategory() != null ? candidate.getCategory().toLowerCase() : "";
        if (cat.contains("shop") || cat.contains("amenity") || cat.contains("clinic") || cat.contains("beauty")
                || cat.contains("barber") || cat.contains("hair") || cat.contains("restaurant") || cat.contains("fitness")) {
            signal += 10;
        }

        signal = Math.max(5, Math.min(100, signal));

        if (analysis.opportunityScore() != null) {
            int combined = (int) Math.round(0.65 * analysis.opportunityScore() + 0.35 * signal);
            return Math.max(0, Math.min(100, combined));
        }
        return signal;
    }
}
