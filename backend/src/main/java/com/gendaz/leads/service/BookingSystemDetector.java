package com.gendaz.leads.service;

import com.gendaz.leads.domain.LeadCandidate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;

@Component
public class BookingSystemDetector {

    private static final Logger log = LoggerFactory.getLogger(BookingSystemDetector.class);

    private static final List<String> SIGNALS = List.of(
            "booksy", "agendor", "genda", "calendly", "youcanbook", "hubspot", "pipedrive",
            "tiny.com.br", "vindi", "mindbody", "wellnessliving", "vagasoft", "salonmanager",
            "shedul", "fresha", "gettimely", "squareup", "appointmentplus", "simplybook",
            "omnify", "zenplanner", "glossgenius", "mangomint", "boulevard", "phorest",
            "vizzen", "agend", "agenda", "sistema.agenda", "agendei", "iwu", "leadlovers",
            "rdstation", "contaazul", "mercadoshopt", "loja.booking", "bookingkit", "reserva"
    );

    public record Detection(String status, String system) {}

    public Detection detect(LeadCandidate candidate) {
        String haystack = String.join(" ",
                safe(candidate.getWebsite()),
                safe(candidate.getInstagramUrl()),
                safe(candidate.getInstagramUsername()));
        haystack = haystack.toLowerCase(Locale.ROOT);
        for (String signal : SIGNALS) {
            if (haystack.contains(signal)) {
                return new Detection("IDENTIFIED", pretty(signal));
            }
        }
        return new Detection("UNKNOWN", null);
    }

    private String safe(String s) {
        return s == null ? "" : s;
    }

    private String pretty(String signal) {
        return switch (signal) {
            case "booksy" -> "Booksy";
            case "agendor" -> "Agendor";
            case "genda", "agend", "sistema.agenda", "agenda", "agendei" -> "Gendaz/Sistema de agendamento";
            case "calendly" -> "Calendly";
            case "fresha" -> "Fresha";
            case "mindbody" -> "Mindbody";
            case "gettimely" -> "Timely";
            case "simplybook" -> "SimplyBook";
            case "hubspot" -> "HubSpot";
            case "tiny.com.br" -> "Tiny";
            case "vindi" -> "Vindi";
            case "phorest" -> "Phorest";
            case "mangomint" -> "Mangomint";
            default -> signal;
        };
    }
}
