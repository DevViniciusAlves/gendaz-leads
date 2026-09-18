package com.gendaz.leads.service.provider;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class NicheMapperTest {

    @Test
    void ciliosVariantsShareSameStrategy() {
        var a = NicheMapper.resolve("cílios");
        var b = NicheMapper.resolve("cilios");
        var c = NicheMapper.resolve("lash designer");
        assertEquals(a.tagFilters(), b.tagFilters());
        assertEquals(a.tagFilters(), c.tagFilters());
        assertTrue(a.tagFilters().contains("beauty=eyelash"));
    }

    @Test
    void unhaSobracelhaEsteticaAliases() {
        assertTrue(NicheMapper.resolve("unhas").tagFilters().contains("beauty=nails"));
        assertTrue(NicheMapper.resolve("manicure").tagFilters().contains("beauty=nails"));
        assertTrue(NicheMapper.resolve("sobrancelhas").tagFilters().contains("beauty=eyebrow"));
        assertTrue(NicheMapper.resolve("designer de sobrancelha").tagFilters().contains("beauty=eyebrow"));
        assertTrue(NicheMapper.resolve("clínica de estética").tagFilters().contains("shop=beauty"));
        assertTrue(NicheMapper.resolve("barbearia").tagFilters().contains("shop=barber"));
        assertTrue(NicheMapper.resolve("salão de beleza").tagFilters().contains("shop=hairdresser"));
        assertTrue(NicheMapper.resolve("dentista").tagFilters().contains("amenity=dentist"));
        assertTrue(NicheMapper.resolve("depilação").tagFilters().contains("beauty=hair_removal"));
        assertTrue(NicheMapper.resolve("massagem").tagFilters().contains("beauty=massage"));
    }

    @Test
    void unknownNicheStillHasNameFallback() {
        var s = NicheMapper.resolve("cafeteria artesanal vegana xyz");
        assertNotNull(s.fallbackNameRegex());
        assertFalse(s.fallbackNameRegex().isBlank());
    }

    @Test
    void regexIsSanitizedAgainstInjection() {
        var s = NicheMapper.resolve("cilios\");out body;/*");
        String q = s.fallbackNameRegex();
        // Pattern.quote envolve com \Q...\E, sem quebrar a query
        assertTrue(q.startsWith("\\Q"));
    }
}
