package com.gendaz.leads.dto.auth;

public record AuthResponse(
        String token,
        String type,
        Long userId,
        String email,
        String fullName,
        String role
) {}
