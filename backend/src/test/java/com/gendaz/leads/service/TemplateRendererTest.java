package com.gendaz.leads.service;

import com.gendaz.leads.entity.Campaign;
import com.gendaz.leads.entity.Lead;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TemplateRendererTest {

    TemplateRenderer renderer = new TemplateRenderer();

    private Lead lead() {
        Lead l = new Lead();
        l.setBusinessName("Padaria Pão Dourado");
        l.setCity("Cuiabá");
        l.setInstagramUsername("paodourado");
        return l;
    }

    private Campaign campaign() {
        Campaign c = new Campaign();
        c.setNiche("padaria");
        return c;
    }

    @Test
    void rendersNome() {
        assertTrue(renderer.render("Olá {{nome}}!", lead(), campaign()).contains("Padaria Pão Dourado"));
    }

    @Test
    void rendersCidade() {
        assertTrue(renderer.render("em {{cidade}}", lead(), campaign()).contains("Cuiabá"));
    }

    @Test
    void rendersNicho() {
        assertTrue(renderer.render("de {{nicho}}", lead(), campaign()).contains("padaria"));
    }

    @Test
    void rendersInstagram() {
        assertTrue(renderer.render("@{{instagram}}", lead(), campaign()).contains("paodourado"));
    }

    @Test
    void rendersRepetition() {
        String out = renderer.render("{{nome}} - {{nome}}", lead(), campaign());
        assertEquals("Padaria Pão Dourado - Padaria Pão Dourado", out);
    }

    @Test
    void rejectsUnknownVariable() {
        // Single-brace {nome} não é variável do sistema e passa intacto (não substitui).
        String single = renderer.render("Olá {nome}", lead(), campaign());
        assertTrue(single.contains("{nome}"));
        // Unknown com double braces deve falhar:
        assertThrows(IllegalArgumentException.class,
                () -> renderer.render("Olá {{unknown}}", lead(), campaign()));
    }

    @Test
    void rejectsBlank() {
        assertThrows(IllegalArgumentException.class, () -> renderer.render("   ", lead(), campaign()));
        assertThrows(IllegalArgumentException.class, () -> renderer.render(null, lead(), campaign()));
    }

    @Test
    void rejectsOver4000() {
        assertThrows(IllegalArgumentException.class,
                () -> renderer.render("x".repeat(4001), lead(), campaign()));
    }

    @Test
    void preservesParagraphs() {
        String template = "Olá {{nome}}, tudo bem?\n\nVi a {{nome}} e queria apresentar o Gendaz.\n\nVocê atende em {{cidade}}?";
        String out = renderer.render(template, lead(), campaign());
        assertTrue(out.contains("\n\n"));
        assertTrue(out.contains("Cuiabá"));
    }
}
