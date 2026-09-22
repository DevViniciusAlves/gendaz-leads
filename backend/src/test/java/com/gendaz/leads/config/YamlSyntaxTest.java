package com.gendaz.leads.config;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.Constructor;
import org.yaml.snakeyaml.LoaderOptions;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class YamlSyntaxTest {

    @Test
    void applicationYamlIsValid() {
        assertYaml("/application.yml");
    }

    @Test
    void applicationProdYamlIsValid() {
        assertYaml("/application-prod.yml");
    }

    @Test
    void duplicateKeyShouldFail() {
        String duplicateYaml = """
                app:
                  discovery:
                    osm:
                      enabled: true
                  discovery:
                    catalog:
                      enabled: true
                """;

        assertThrows(Exception.class, () -> {
            LoaderOptions options = new LoaderOptions();
            options.setAllowDuplicateKeys(false);
            Yaml yaml = new Yaml(new Constructor(options));
            yaml.load(new ByteArrayInputStream(duplicateYaml.getBytes(StandardCharsets.UTF_8)));
        }, "Duplicate key should cause parsing to fail");
    }

    private void assertYaml(String resource) {
        InputStream input =
                getClass().getResourceAsStream(resource);

        assertNotNull(
                input,
                "Recurso não encontrado: " + resource
        );

        assertDoesNotThrow(() -> {
            LoaderOptions options = new LoaderOptions();
            options.setAllowDuplicateKeys(false);
            Yaml yaml = new Yaml(new Constructor(options));
            yaml.loadAll(input).forEach(document -> {
                // força parse de todos os documentos
            });
        });
    }
}