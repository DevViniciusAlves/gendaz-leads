package com.gendaz.leads.dto.auth;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RegisterRequest(
        @NotBlank @Size(max = 255) String fullName,
        @NotBlank @Email String email,
        @NotBlank @Size(min = 8, max = 128) String password
) {}
