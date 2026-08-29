package com.gendaz.leads.util;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NormalizerTest {

    private final Normalizer normalizer = new Normalizer();

    @Test
    void normalizesNameStrippingAccentsAndPunctuation() {
        assertEquals("barbearia alpha", normalizer.normalizeName("Barbearia Alpha!!"));
        assertEquals("clinica odontologica sorriso", normalizer.normalizeName("Clínica Odontológica Sorriso"));
    }

    @Test
    void normalizesPhoneToDigits() {
        assertEquals("65999999999", normalizer.normalizePhone("(65) 99999-9999"));
        assertNull(normalizer.normalizePhone("123"));
    }

    @Test
    void normalizesWebsiteToHost() {
        assertEquals("instagram.com", normalizer.normalizeWebsite("https://www.instagram.com/foo"));
        assertEquals("exemplo.com.br", normalizer.normalizeWebsite("http://exemplo.com.br/path?x=1"));
    }

    @Test
    void normalizesInstagramHandle() {
        assertEquals("barbeariaalpha", normalizer.normalizeInstagram("@BarbeariaAlpha"));
        assertEquals("barbeariaalpha", normalizer.normalizeInstagram("https://instagram.com/BarbeariaAlpha/"));
    }

    @Test
    void normalizesSourceIdWithSourcePrefix() {
        assertEquals("google_chij123", normalizer.normalizeSourceId("google", "ChIJ123"));
    }
}
