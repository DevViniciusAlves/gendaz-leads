package com.gendaz.leads.util;

import java.util.Locale;

/**
 * Normalização central de e-mail para autenticação.
 * Regra: trim() + toLowerCase(Locale.ROOT).
 * Nunca logar senha, hash, JWT ou segredos aqui.
 */
public final class EmailNormalizer {

    private EmailNormalizer() {
    }

    public static String normalizeEmail(String email) {
        if (email == null) {
            return null;
        }
        return email.trim().toLowerCase(Locale.ROOT);
    }
}
