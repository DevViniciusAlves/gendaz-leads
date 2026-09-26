package com.gendaz.leads.service.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class NicheTwoStageTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static ObjectNode tags(String... kv) {
        ObjectNode n = JSON.createObjectNode();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            n.put(kv[i], kv[i + 1]);
        }
        return n;
    }

    @Test
    void nailsBeautyAloneIsPotentialButNotConfirmed() {
        NicheMapper.NicheStrategy nails = NicheMapper.resolve("nail designer");
        assertEquals("nails", nails.canonicalName());
        ObjectNode t = tags("shop", "beauty", "name", "Studio Bella");
        assertTrue(NicheMapper.isPotential(nails, t, "Studio Bella"));
        NicheMapper.NicheConfirmation c = NicheMapper.confirm(nails, t, "Studio Bella beleza");
        assertFalse(c.confirmed(), "shop=beauty sozinho nao confirma Nails");
    }

    @Test
    void nailsBeautyWithOfficialManicureConfirms() {
        NicheMapper.NicheStrategy nails = NicheMapper.resolve("nails");
        ObjectNode t = tags("shop", "beauty", "name", "Studio Bella");
        NicheMapper.NicheConfirmation c = NicheMapper.confirm(
                nails, t, "Manicure Pedicure Alongamento Nail Design");
        assertTrue(c.confirmed());
        assertEquals("OFFICIAL_TEXT", c.evidenceType());
    }

    @Test
    void nailSalonTagConfirms() {
        NicheMapper.NicheStrategy nails = NicheMapper.resolve("nails");
        ObjectNode t = tags("shop", "nail_salon", "name", "Studio X");
        assertTrue(NicheMapper.isPotential(nails, t, "Studio X"));
        assertTrue(NicheMapper.confirm(nails, t, "").confirmed());
    }

    @Test
    void beautyTokenManicureConfirms() {
        NicheMapper.NicheStrategy nails = NicheMapper.resolve("nails");
        ObjectNode t = tags("shop", "beauty", "beauty", "nails;manicure", "name", "Studio");
        assertTrue(NicheMapper.confirm(nails, t, "").confirmed());
    }

    @Test
    void accentAndWordBoundary() {
        NicheMapper.NicheStrategy nails = NicheMapper.resolve("nails");
        ObjectNode t = tags("shop", "beauty", "name", "Escola");
        // "unha" dentro de "manusia"? nao deve confirmar por substring acidental.
        NicheMapper.NicheConfirmation c = NicheMapper.confirm(nails, t, "Unha em gel e fibra de vidro");
        assertTrue(c.confirmed());
        NicheMapper.NicheConfirmation c2 = NicheMapper.confirm(nails, t, "Restaurante comum");
        assertFalse(c2.confirmed());
    }

    @Test
    void allCanonicalNichesResolve() {
        String[] niches = {"beauty", "eyelash", "eyebrow", "nails", "hairdresser", "barber",
                "dentist", "clinic", "massage", "hair_removal", "beauty_generic", "fitness",
                "restaurant", "cafe", "bakery", "hotel", "pet", "veterinary", "pharmacy", "lawyer"};
        for (String n : niches) {
            NicheMapper.NicheStrategy s = NicheMapper.resolve(n);
            assertNotNull(s);
            assertNotNull(s.canonicalName());
            assertFalse(s.canonicalName().isBlank(), "niche " + n);
        }
    }
}
