package com.gendaz.leads.config;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class YamlSyntaxTest {

    @Test
    void applicationYamlIsValid() {
        assertYaml("/application.yml");
    }

    @Test
    void applicationProdYamlIsValid() {
        assertYaml("/application-prod.yml");
    }

    private void assertYaml(String resource) {
        InputStream input =
                getClass().getResourceAsStream(resource);

        assertNotNull(
                input,
                "Recurso não encontrado: " + resource
        );

        assertDoesNotThrow(() -> {
            Yaml yaml = new Yaml();
            yaml.loadAll(input).forEach(document -> {
                // força parse de todos os documentos
            });
        });
    }
}