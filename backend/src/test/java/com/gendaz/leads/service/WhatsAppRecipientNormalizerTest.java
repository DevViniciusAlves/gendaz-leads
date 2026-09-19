package com.gendaz.leads.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class WhatsAppRecipientNormalizerTest {

    private final WhatsAppRecipientNormalizer normalizer = new WhatsAppRecipientNormalizer();

    @Test
    void parenthesizedMobileAdds55() {
        assertEquals("5511999999999", normalizer.normalizeForWhatsApp("(11) 99999-9999", "BR"));
    }

    @Test
    void plainElevenDigitsAdds55() {
        assertEquals("5511999999999", normalizer.normalizeForWhatsApp("11999999999", "BR"));
    }

    @Test
    void plusFormKeepsSame() {
        assertEquals("5511999999999", normalizer.normalizeForWhatsApp("+5511999999999", "BR"));
    }

    @Test
    void alreadyWithDdiKeepsSame() {
        assertEquals("5511999999999", normalizer.normalizeForWhatsApp("5511999999999", "BR"));
    }

    @Test
    void doubleZeroPrefixStripped() {
        assertEquals("5511999999999", normalizer.normalizeForWhatsApp("005511999999999", "BR"));
    }

    @Test
    void tenDigitsLandlineAdds55() {
        assertEquals("551134563456", normalizer.normalizeForWhatsApp("(11) 3456-3456", "BR"));
    }

    @Test
    void withoutDddIsInvalid() {
        assertNull(normalizer.normalizeForWhatsApp("99999-9999", "BR"));
        assertNull(normalizer.normalizeForWhatsApp("999999999", "BR"));
    }

    @Test
    void duplicatedDdiIsInvalid() {
        assertNull(normalizer.normalizeForWhatsApp("555511999999999", "BR"));
    }

    @Test
    void nullCountryDefaultsToBr() {
        assertEquals("5511999999999", normalizer.normalizeForWhatsApp("(11) 99999-9999", null));
    }

    @Test
    void foreignDoesNotAdd55() {
        String out = normalizer.normalizeForWhatsApp("+1 415 555 0132", "US");
        assertNotNull(out);
        assertFalse(out.startsWith("55"));
        assertEquals("14155550132", out);
    }

    @Test
    void foreignTooShortIsInvalid() {
        assertNull(normalizer.normalizeForWhatsApp("123", "US"));
    }

    @Test
    void blankIsInvalid() {
        assertNull(normalizer.normalizeForWhatsApp("   ", "BR"));
        assertNull(normalizer.normalizeForWhatsApp(null, "BR"));
    }
}
