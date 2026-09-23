package com.gendaz.leads.service.provider;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class NicheMapperTest {

    @Test
    void barberMapsToShopBarberAndHairdresserBarber() {
        NicheMapper.NicheStrategy strategy = NicheMapper.resolve("barbearia");

        assertTrue(strategy.tagFilters().contains("shop=barber"), "Should contain shop=barber");
        assertTrue(strategy.tagFilters().contains("shop=hairdresser,hairdresser=barber"), "Should contain compound filter");
        assertEquals(2, strategy.tagFilters().size(), "Should have exactly 2 filters");
    }

    @Test
    void barberFallbackRegexCoversVariants() {
        NicheMapper.NicheStrategy strategy = NicheMapper.resolve("barbearia");

        assertEquals("barbearia|barber|barbershop", strategy.fallbackNameRegex());
    }

    @Test
    void hairdresserMapsToShopHairdresser() {
        NicheMapper.NicheStrategy strategy = NicheMapper.resolve("cabeleireiro");

        assertTrue(strategy.tagFilters().contains("shop=hairdresser"));
        assertTrue(strategy.tagFilters().contains("shop=beauty"));
    }

    @Test
    void genericBeautyMapsToShopBeauty() {
        NicheMapper.NicheStrategy strategy = NicheMapper.resolve("estetica");

        assertTrue(strategy.tagFilters().contains("shop=beauty"));
        assertTrue(strategy.tagFilters().contains("shop=beauty,beauty=aesthetic"));
    }

    @Test
    void dentistMapsToAmenityDentist() {
        NicheMapper.NicheStrategy strategy = NicheMapper.resolve("dentista");

        assertTrue(strategy.tagFilters().contains("amenity=dentist"));
    }

    @Test
    void unknownNicheReturnsEmptyTags() {
        NicheMapper.NicheStrategy strategy = NicheMapper.resolve("unknownniche123");

        assertTrue(strategy.tagFilters().isEmpty());
    }

    @Test
    void normalizeKeyRemovesAccentsAndCollapsesSpaces() {
        String normalized = NicheMapper.normalizeKey("  Barbearia  João  ");
        assertEquals("barbearia joao", normalized);
    }

    @Test
    void partialMatchPrefersLongestAlias() {
        // "barbearia" should match before "bar"
        NicheMapper.NicheStrategy strategy = NicheMapper.resolve("barbearia centro");

        assertTrue(strategy.tagFilters().contains("shop=barber"));
    }

    @Test
    void barberDoesNotAcceptGenericHairdresser() {
        NicheMapper.NicheStrategy strategy = NicheMapper.resolve("barbearia");

        // Should NOT have plain "shop=hairdresser" as that would match any hairdresser
        assertFalse(strategy.tagFilters().contains("shop=hairdresser"), "Should not accept generic hairdresser");
    }

    @Test
    void compoundTagFilterUsesAnd() {
        NicheMapper.NicheStrategy strategy = NicheMapper.resolve("barbearia");

        // Compound filter "shop=hairdresser,hairdresser=barber" means AND
        String compound = "shop=hairdresser,hairdresser=barber";
        assertTrue(strategy.tagFilters().contains(compound));

        // Verify it's split by comma for AND condition
        String[] parts = compound.split(",");
        assertEquals(2, parts.length);
        assertEquals("shop=hairdresser", parts[0]);
        assertEquals("hairdresser=barber", parts[1]);
    }

    @Test
    void nailDesignerMapsToShopBeautyNailsStrict() {
        NicheMapper.NicheStrategy strategy = NicheMapper.resolve("nail designer");
        assertTrue(strategy.tagFilters().contains("shop=beauty,beauty=nails"), "Should contain shop=beauty,beauty=nails");
        assertEquals(1, strategy.tagFilters().size(), "Nail designer should have exactly 1 structured filter");
        assertFalse(strategy.tagFilters().contains("shop=beauty"), "Should NOT contain generic shop=beauty");
        assertFalse(strategy.tagFilters().contains("shop=clothes"), "Should NOT contain shop=clothes");
    }

    @Test
    void nailDesignerShopBeautyAloneNotMatch() {
        NicheMapper.NicheStrategy strategy = NicheMapper.resolve("NAIL DESIGNER");
        assertFalse(strategy.tagFilters().contains("shop=beauty"));
        assertFalse(strategy.tagFilters().contains("shop=clothes"));
    }

    @Test
    void nailDesignerFallbackIsSanitized() {
        NicheMapper.NicheStrategy strategy = NicheMapper.resolve("nail designer");
        assertNotNull(strategy.fallbackNameRegex());
        // fallback for nail designer is sanitized niche itself (no barber regex)
        assertTrue(strategy.fallbackNameRegex().toLowerCase().contains("nail"));
    }
}