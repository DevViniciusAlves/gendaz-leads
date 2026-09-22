package com.gendaz.leads.service;

import com.gendaz.leads.util.Normalizer;
import com.gendaz.leads.util.SsrfGuard;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class WebsiteContactEnricherTest {

    @Mock Normalizer normalizer;
    @Mock SsrfGuard ssrfGuard;

    private WebsiteContactEnricher enricher;

    @BeforeEach
    void setUp() {
        enricher = new WebsiteContactEnricher(normalizer, ssrfGuard, 2000, 3000, 500000);
    }

    @Test
    void emptyWebsiteReturnsEmpty() {
        var result = enricher.enrich("");
        assertNotNull(result);
        assertNull(result.phone());
        assertNull(result.email());
        assertNull(result.instagramUsername());
    }

    @Test
    void nullWebsiteReturnsEmpty() {
        var result = enricher.enrich(null);
        assertNotNull(result);
        assertNull(result.phone());
        assertNull(result.email());
        assertNull(result.instagramUsername());
    }

    @Test
    void unsafeWebsiteReturnsEmpty() {
        when(ssrfGuard.isSafe("https://example.com")).thenReturn(false);

        var result = enricher.enrich("https://example.com");

        assertNotNull(result);
        assertNull(result.phone());
        assertNull(result.email());
        assertNull(result.instagramUsername());
    }

    @Test
    void normalizeTargetUrlAddsHttps() {
        lenient().when(ssrfGuard.isSafe(anyString())).thenReturn(true);
        lenient().when(normalizer.normalizePhone(anyString())).thenReturn("+556599998888");
        lenient().when(normalizer.normalizeEmail(anyString())).thenReturn("test@example.com");
        lenient().when(normalizer.normalizeInstagram(anyString())).thenReturn("test");

        enricher.enrich("example.com");

        verify(ssrfGuard, atLeastOnce()).isSafe("https://example.com");
    }

    @Test
    void normalizeTargetUrlKeepsHttps() {
        lenient().when(ssrfGuard.isSafe(anyString())).thenReturn(true);
        lenient().when(normalizer.normalizePhone(anyString())).thenReturn("+556599998888");
        lenient().when(normalizer.normalizeEmail(anyString())).thenReturn("test@example.com");
        lenient().when(normalizer.normalizeInstagram(anyString())).thenReturn("test");

        enricher.enrich("https://example.com");

        verify(ssrfGuard, atLeastOnce()).isSafe("https://example.com");
    }

    @Test
    void normalizeTargetUrlHandlesHttpPrefix() {
        lenient().when(ssrfGuard.isSafe(anyString())).thenReturn(true);
        lenient().when(normalizer.normalizePhone(anyString())).thenReturn("+556599998888");
        lenient().when(normalizer.normalizeEmail(anyString())).thenReturn("test@example.com");
        lenient().when(normalizer.normalizeInstagram(anyString())).thenReturn("test");

        enricher.enrich("http://example.com");

        verify(ssrfGuard, atLeastOnce()).isSafe("http://example.com");
    }

    @Test
    void websiteContactDataRecord() {
        var data = new WebsiteContactEnricher.WebsiteContactData("phone", "email", "instagram");

        assertEquals("phone", data.phone());
        assertEquals("email", data.email());
        assertEquals("instagram", data.instagramUsername());
    }

    @Test
    void enrichmentWithAllFields() {
        lenient().when(ssrfGuard.isSafe(anyString())).thenReturn(true);
        lenient().when(normalizer.normalizePhone(anyString())).thenReturn("+556599998888");
        lenient().when(normalizer.normalizeEmail(anyString())).thenReturn("test@example.com");
        lenient().when(normalizer.normalizeInstagram(anyString())).thenReturn("test");

        var result = enricher.enrich("https://example.com");

        assertNotNull(result);
    }
}