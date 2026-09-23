package com.gendaz.leads.service.provider;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class NicheMapperTest {

    @Test
    void nailsStrategyHasCorrectCanonicalAndRules() {
        NicheMapper.NicheStrategy strategy = NicheMapper.resolve("nail designer");
        assertEquals("nails", strategy.canonicalName());
        assertEquals(3, strategy.structuredRules().size());

        boolean hasBeautyTokenRule = strategy.structuredRules().stream().anyMatch(rule ->
                rule.allOf().size() == 2
                        && rule.allOf().stream().anyMatch(c -> c.key().equals("shop") && c.mode() == NicheMapper.MatchMode.EXACT && c.acceptedValues().contains("beauty"))
                        && rule.allOf().stream().anyMatch(c -> c.key().equals("beauty") && c.mode() == NicheMapper.MatchMode.SEMICOLON_TOKEN && c.acceptedValues().containsAll(List.of("nails", "manicure", "pedicure")))
        );
        assertTrue(hasBeautyTokenRule, "Should contain shop=beauty + beauty SEMICOLON_TOKEN nails/manicure/pedicure");

        boolean hasHairdresserTokenRule = strategy.structuredRules().stream().anyMatch(rule ->
                rule.allOf().size() == 2
                        && rule.allOf().stream().anyMatch(c -> c.key().equals("shop") && c.mode() == NicheMapper.MatchMode.EXACT && c.acceptedValues().contains("hairdresser"))
                        && rule.allOf().stream().anyMatch(c -> c.key().equals("beauty") && c.mode() == NicheMapper.MatchMode.SEMICOLON_TOKEN && c.acceptedValues().containsAll(List.of("nails", "manicure", "pedicure")))
        );
        assertTrue(hasHairdresserTokenRule, "Should contain shop=hairdresser + beauty SEMICOLON_TOKEN nails/manicure/pedicure");

        boolean hasNailSalonRule = strategy.structuredRules().stream().anyMatch(rule ->
                rule.allOf().size() == 1
                        && rule.allOf().get(0).key().equals("shop")
                        && rule.allOf().get(0).mode() == NicheMapper.MatchMode.EXACT
                        && rule.allOf().get(0).acceptedValues().contains("nail_salon")
        );
        assertTrue(hasNailSalonRule, "Should contain shop=nail_salon");

        // fallback aliases
        List<String> aliases = strategy.nameFallback().aliases();
        assertTrue(aliases.contains("nail"));
        assertTrue(aliases.contains("nails"));
        assertTrue(aliases.contains("nail designer"));
        assertTrue(aliases.contains("manicure"));
        assertTrue(aliases.contains("pedicure"));
        assertTrue(aliases.contains("esmalteria"));
        assertTrue(aliases.contains("unha"));
        assertTrue(aliases.contains("unhas"));
        assertTrue(aliases.contains("alongamento de unhas"));

        // fallback context should contain beauty, hairdresser, nail_salon
        assertTrue(strategy.nameFallback().requiresContext());
        assertEquals(3, strategy.nameFallback().contextAnyOf().size());
    }

    @Test
    void barberStrategyHasThreeRulesAndNoGenericHairdresser() {
        NicheMapper.NicheStrategy strategy = NicheMapper.resolve("barbearia");
        assertEquals("barber", strategy.canonicalName());
        assertEquals(3, strategy.structuredRules().size());

        boolean hasTokenRule = strategy.structuredRules().stream().anyMatch(rule ->
                rule.allOf().size() == 2
                        && rule.allOf().stream().anyMatch(c -> c.key().equals("shop") && c.acceptedValues().contains("hairdresser"))
                        && rule.allOf().stream().anyMatch(c -> c.key().equals("hairdresser") && c.mode() == NicheMapper.MatchMode.SEMICOLON_TOKEN && c.acceptedValues().contains("barber"))
        );
        assertTrue(hasTokenRule);

        boolean hasShopBarber = strategy.structuredRules().stream().anyMatch(rule ->
                rule.allOf().size() == 1 && rule.allOf().get(0).key().equals("shop") && rule.allOf().get(0).acceptedValues().contains("barber")
        );
        assertTrue(hasShopBarber);

        boolean hasBarberYes = strategy.structuredRules().stream().anyMatch(rule ->
                rule.allOf().size() == 2
                        && rule.allOf().stream().anyMatch(c -> c.key().equals("shop") && c.acceptedValues().contains("hairdresser"))
                        && rule.allOf().stream().anyMatch(c -> c.key().equals("barber") && c.acceptedValues().contains("yes"))
        );
        assertTrue(hasBarberYes);

        // Should NOT have plain shop=hairdresser alone
        boolean hasGenericHairdresserOnly = strategy.structuredRules().stream().anyMatch(rule ->
                rule.allOf().size() == 1 && rule.allOf().get(0).key().equals("shop") && rule.allOf().get(0).acceptedValues().size() == 1 && rule.allOf().get(0).acceptedValues().contains("hairdresser")
        );
        assertFalse(hasGenericHairdresserOnly, "Should not have shop=hairdresser alone as barber match");
    }

    @Test
    void nailsAliasesResolveToNails() {
        for (String alias : List.of("NAIL DESIGNER", "manicure", "pedicure", "esmalteria", "unhas", "unha", "nail", "nails")) {
            NicheMapper.NicheStrategy s = NicheMapper.resolve(alias);
            assertEquals("nails", s.canonicalName(), "alias " + alias + " should resolve to nails");
        }
    }

    @Test
    void barberAliasesResolveToBarber() {
        for (String alias : List.of("barbearia", "barbearias", "barber", "barbershop", "barba", "barber shop")) {
            NicheMapper.NicheStrategy s = NicheMapper.resolve(alias);
            assertEquals("barber", s.canonicalName(), "alias " + alias + " should resolve to barber");
        }
    }

    @Test
    void ciliosStrategyHasCorrectStructure() {
        NicheMapper.NicheStrategy s = NicheMapper.resolve("cilios");
        boolean has = s.structuredRules().stream().anyMatch(r ->
                r.allOf().stream().anyMatch(c -> c.key().equals("shop") && c.acceptedValues().contains("beauty"))
                        && r.allOf().stream().anyMatch(c -> c.key().equals("beauty") && c.mode() == NicheMapper.MatchMode.SEMICOLON_TOKEN && c.acceptedValues().contains("eyelash"))
        );
        assertTrue(has);
    }

    @Test
    void sobrancelhasStrategyHasCorrectStructure() {
        NicheMapper.NicheStrategy s = NicheMapper.resolve("sobrancelha");
        boolean has = s.structuredRules().stream().anyMatch(r ->
                r.allOf().stream().anyMatch(c -> c.key().equals("shop") && c.acceptedValues().contains("beauty"))
                        && r.allOf().stream().anyMatch(c -> c.key().equals("beauty") && c.mode() == NicheMapper.MatchMode.SEMICOLON_TOKEN && c.acceptedValues().contains("eyebrow"))
        );
        assertTrue(has);
    }

    @Test
    void massagemStrategyHasThreeRules() {
        NicheMapper.NicheStrategy s = NicheMapper.resolve("massagem");
        assertEquals(3, s.structuredRules().size());
    }

    @Test
    void depilacaoStrategy() {
        NicheMapper.NicheStrategy s = NicheMapper.resolve("depilacao");
        boolean has = s.structuredRules().stream().anyMatch(r ->
                r.allOf().stream().anyMatch(c -> c.key().equals("beauty") && c.mode() == NicheMapper.MatchMode.SEMICOLON_TOKEN && c.acceptedValues().contains("hair_removal"))
        );
        assertTrue(has);
    }

    @Test
    void unknownNicheReturnsEmptyStructuredWithAliasFallback() {
        NicheMapper.NicheStrategy strategy = NicheMapper.resolve("unknownniche123");
        assertTrue(strategy.structuredRules().isEmpty());
        assertFalse(strategy.nameFallback().requiresContext());
        assertTrue(strategy.nameFallback().aliases().contains("unknownniche123"));
    }

    @Test
    void normalizeKeyRemovesAccentsAndCollapsesSpaces() {
        String normalized = NicheMapper.normalizeKey("  Barbearia  João  ");
        assertEquals("barbearia joao", normalized);
    }

    @Test
    void normalizeNamePhrase() {
        assertEquals("nail designer", NicheMapper.normalizeNamePhrase("Nail-Designer"));
        assertEquals("barber shop", NicheMapper.normalizeNamePhrase("Barber Shop"));
        assertEquals("espaco das unhas", NicheMapper.normalizeNamePhrase("Espaço das Unhas"));
    }

    @Test
    void partialMatchPrefersLongestAlias() {
        NicheMapper.NicheStrategy strategy = NicheMapper.resolve("barbearia centro");
        assertEquals("barber", strategy.canonicalName());
    }

    @Test
    void longestAliasDeterministic() {
        // "alongamento de unhas" longer than "unhas" should win
        NicheMapper.NicheStrategy s1 = NicheMapper.resolve("alongamento de unhas teste");
        assertEquals("nails", s1.canonicalName());
    }

    @Test
    void barberDoesNotAcceptGenericHairdresser() {
        NicheMapper.NicheStrategy strategy = NicheMapper.resolve("barbearia");
        boolean hasGeneric = strategy.structuredRules().stream().anyMatch(rule ->
                rule.allOf().size() == 1
                        && rule.allOf().get(0).key().equals("shop")
                        && rule.allOf().get(0).acceptedValues().equals(List.of("hairdresser"))
        );
        assertFalse(hasGeneric, "Should not accept generic hairdresser");
    }

    @Test
    void hairdresserMapsToHairdresserAndBeauty() {
        NicheMapper.NicheStrategy strategy = NicheMapper.resolve("cabeleireiro");
        assertEquals("hairdresser", strategy.canonicalName());
        boolean hasHairdresser = strategy.structuredRules().stream().anyMatch(r -> r.allOf().stream().anyMatch(c -> c.acceptedValues().contains("hairdresser")));
        boolean hasBeauty = strategy.structuredRules().stream().anyMatch(r -> r.allOf().stream().anyMatch(c -> c.acceptedValues().contains("beauty")));
        assertTrue(hasHairdresser);
        assertTrue(hasBeauty);
    }

    @Test
    void dentistMapsToAmenityDentist() {
        NicheMapper.NicheStrategy strategy = NicheMapper.resolve("dentista");
        assertEquals("dentist", strategy.canonicalName());
        boolean has = strategy.structuredRules().stream().anyMatch(r -> r.allOf().stream().anyMatch(c -> c.key().equals("amenity") && c.acceptedValues().contains("dentist")));
        assertTrue(has);
    }

    @Test
    void genericBeautyMapsToShopBeauty() {
        NicheMapper.NicheStrategy strategy = NicheMapper.resolve("estetica");
        assertEquals("beauty", strategy.canonicalName());
        assertTrue(strategy.structuredRules().stream().anyMatch(r -> r.allOf().stream().anyMatch(c -> c.key().equals("shop") && c.acceptedValues().contains("beauty"))));
    }
}
