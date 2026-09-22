package com.gendaz.leads.service;

import com.gendaz.leads.util.Normalizer;
import com.gendaz.leads.util.SsrfGuard;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

class WebsiteContactEnricherTest {

    private final WebsiteContactEnricher enricher =
            new WebsiteContactEnricher(
                    new Normalizer(),
                    mock(SsrfGuard.class),
                    2000,
                    3000,
                    500000
            );

    @Test
    void extractsWaMeBeforeGenericPhone() {
        String html = "<a href=\"https://wa.me/5565999999999\">WhatsApp</a>";

        assertEquals(
                "5565999999999",
                enricher.extractPhone(html)
        );
    }

    @Test
    void extractsWhatsappApiPhone() {
        String html = "<a href=\"https://api.whatsapp.com/send?phone=5565999999999\">WhatsApp</a>";

        assertEquals(
                "5565999999999",
                enricher.extractPhone(html)
        );
    }

    @Test
    void extractsTelLink() {
        String html = "<a href=\"tel:+55 (65) 99999-9999\">Ligue</a>";

        assertEquals(
                "5565999999999",
                enricher.extractPhone(html)
        );
    }

    @Test
    void decodesUrlEncodedWhatsappPhone() {
        String html = "<a href=\"https://api.whatsapp.com/send?phone=%2B5565999999999\">WhatsApp</a>";

        assertEquals(
                "5565999999999",
                enricher.extractPhone(html)
        );
    }
}