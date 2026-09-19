package com.gendaz.leads.service;

import org.springframework.stereotype.Component;

@Component
public class WhatsAppRecipientNormalizer {

    public String normalizeForWhatsApp(String phone, String country) {
        if (phone == null || phone.isBlank()) return null;

        String digits = phone.replaceAll("\\D", "");
        if (digits.isBlank()) return null;

        // Remove prefixo internacional 00 (ex: 0055... -> 55...)
        while (digits.startsWith("00") && digits.length() > 2) {
            digits = digits.substring(2);
        }

        boolean isBr = (country == null || country.isBlank() || "BR".equalsIgnoreCase(country.trim()));
        if (isBr) {
            return normalizeBrazil(digits);
        }
        return normalizeForeign(digits);
    }

    private String normalizeBrazil(String digits) {
        // 10 ou 11 digitos sem DDI -> adicionar 55
        if (digits.length() == 10 || digits.length() == 11) {
            if (!hasValidDdd(digits)) return null;
            return "55" + digits;
        }
        // 12 ou 13 iniciando com 55 -> manter (sem duplicar)
        if ((digits.length() == 12 || digits.length() == 13) && digits.startsWith("55")) {
            // Nao duplicar: 5555... indica DDI duplicado
            if (digits.startsWith("5555")) return null;
            String rest = digits.substring(2);
            if (rest.length() != 10 && rest.length() != 11) return null;
            if (!hasValidDdd(rest)) return null;
            return digits;
        }
        // Sem DDD (8 ou 9 digitos) ou qualquer outro tamanho -> invalido
        return null;
    }

    private boolean hasValidDdd(String national) {
        // national tem 10 (DDD + 8) ou 11 (DDD + 9) digitos.
        // DDD: 2 digitos, 11..99 (primeiro digito != 0).
        if (national == null || (national.length() != 10 && national.length() != 11)) return false;
        char d1 = national.charAt(0);
        char d2 = national.charAt(1);
        if (!Character.isDigit(d1) || !Character.isDigit(d2)) return false;
        int ddd = (d1 - '0') * 10 + (d2 - '0');
        return ddd >= 11 && ddd <= 99;
    }

    private String normalizeForeign(String digits) {
        // Para paises != BR: nao adicionar 55. Exige DDI explicito seguro: 8 a 15 digitos.
        if (digits.length() >= 8 && digits.length() <= 15) {
            return digits;
        }
        return null;
    }
}
