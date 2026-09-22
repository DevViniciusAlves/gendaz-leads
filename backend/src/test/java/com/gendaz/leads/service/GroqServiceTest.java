package com.gendaz.leads.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import static org.junit.jupiter.api.Assertions.*;

class GroqServiceTest {

    @Test
    void usesFixedSupportedGroqModel() {
        GroqService service =
                new GroqService(
                        RestClient.builder(),
                        new ObjectMapper(),
                        "test-api-key",
                        true,
                        30000,
                        2
                );

        assertEquals(
                "openai/gpt-oss-120b",
                service.getModel()
        );
    }
}