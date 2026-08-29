package com.gendaz.leads.controller;

import com.gendaz.leads.dto.auth.AuthResponse;
import com.gendaz.leads.dto.auth.LoginRequest;
import com.gendaz.leads.dto.auth.RegisterRequest;
import com.gendaz.leads.security.SecurityService;
import com.gendaz.leads.service.AuthService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;
    private final SecurityService securityService;

    public AuthController(AuthService authService, SecurityService securityService) {
        this.authService = authService;
        this.securityService = securityService;
    }

    @PostMapping("/register")
    public ResponseEntity<AuthResponse> register(@Valid @RequestBody RegisterRequest request) {
        return ResponseEntity.ok(authService.register(request));
    }

    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(@Valid @RequestBody LoginRequest request) {
        return ResponseEntity.ok(authService.login(request));
    }

    @GetMapping("/me")
    public ResponseEntity<?> me() {
        UserDetails principal = securityService.currentPrincipal();
        return ResponseEntity.ok(new AuthResponse(
                null, "Bearer", null, principal.getUsername(), null,
                principal.getAuthorities().iterator().next().getAuthority().replace("ROLE_", "")));
    }
}
