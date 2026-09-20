package com.gendaz.leads.dto.template;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateMessageTemplateRequest(
        @NotBlank(message = "Texto da abordagem é obrigatório.")
        @Size(max = 4000, message = "Texto excede o limite de 4000 caracteres.")
        String templateText,
        @Size(max = 255, message = "Nome excede 255 caracteres.")
        String name
) {}