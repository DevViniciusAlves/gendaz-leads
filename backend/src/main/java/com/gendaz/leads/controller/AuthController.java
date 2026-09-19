package com.gendaz.leads.controller;

import com.gendaz.leads.dto.auth.AuthResponse;
import com.gendaz.leads.dto.auth.LoginRequest;
import com.gendaz.leads.dto.auth.RegisterRequest;
import com.gendaz.leads.entity.User;
import com.gendaz.leads.exception.ApiException;
import com.gendaz.leads.repository.UserRepository;
import com.gendaz.leads.security.SecurityService;
import com.gendaz.leads.service.AuthService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;
    private final SecurityService securityService;
    private final UserRepository userRepository;

    public AuthController(AuthService authService, SecurityService securityService, UserRepository userRepository) {
        this.authService = authService;
        this.securityService = securityService;
        this.userRepository = userRepository;
    }

    @PostMapping("/register")
    public ResponseEntity<AuthResponse> register(@Valid @RequestBody RegisterRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(authService.register(request));
    }

    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(@Valid @RequestBody LoginRequest request) {
        return ResponseEntity.ok(authService.login(request));
    }

    @GetMapping("/me")
    public ResponseEntity<AuthResponse> me() {
        String email = securityService.currentEmail();
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "Não autenticado"));
        return ResponseEntity.ok(new AuthResponse(
                null, "Bearer", user.getId(), user.getEmail(), user.getFullName(), user.getRole()));
    }
}
