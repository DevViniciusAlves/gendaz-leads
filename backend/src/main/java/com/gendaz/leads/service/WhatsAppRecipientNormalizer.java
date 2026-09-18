package com.gendaz.leads.service;

import org.springframework.stereotype.Component;

@Component
public class WhatsAppRecipientNormalizer {

    public String normalizeForWhatsApp(String phone, String country) {
        if (phone == null || phone.isBlank()) return null;

        String digits = phone.replaceAll("\\D", "");

        if ("BR".equals(country) || country == null) {
            return normalizeBrazil(digits);
        } else {
            return normalizeForeign(digits);
        }
    }

    private String normalizeBrazil(String digits) {
        if (digits.length() == 11 && digits.startsWith("55")) {
            // Already has DDI 55, maintain
            if (digits.length() == 13) {
                return digits; // 55 + 2 digits area + 8 digits number
            }
            if (digits.length() == 12) {
                // 55 + 1 digit + 8 digits or 55 + 2 digit + 7 digits - check pattern
                // Brazil DDD is 2 digits: 55XX9XXXXXXX = 13 chars total including 55
                // So 12 chars means something is off, but let's be permissive
                return digits;
            }
            return "55" + digits; // Add 55 if not present
        }

        if (digits.length() == 10 || digits.length() == 11) {
            // Brazilian number without DDI: add 55
            return "55" + digits;
        }

        if (digits.length() >= 12 && digits.length() <= 13 && digits.startsWith("55")) {
            // Already has 55, return as is (validated later by WhatsAppService)
            return digits;
        }

        // If 12+ digits starting with other codes, don't add 55
        return digits;
    }

    private String normalizeForeign(String digits) {
        // For non-BR countries, accept only number with explicit DDI, 8 to 15 digits
        if (digits.length() >= 8 && digits.length() <= 15) {
            return digits;
        }
        return null;
    }
}