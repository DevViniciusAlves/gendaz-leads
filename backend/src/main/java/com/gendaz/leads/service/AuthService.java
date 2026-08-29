package com.gendaz.leads.service;

import com.gendaz.leads.dto.auth.AuthResponse;
import com.gendaz.leads.dto.auth.LoginRequest;
import com.gendaz.leads.dto.auth.RegisterRequest;
import com.gendaz.leads.entity.User;
import com.gendaz.leads.exception.ApiException;
import com.gendaz.leads.repository.UserRepository;
import com.gendaz.leads.security.JwtService;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuthenticationManager authenticationManager;
    private final JwtService jwtService;

    public AuthService(UserRepository userRepository, PasswordEncoder passwordEncoder,
                       AuthenticationManager authenticationManager, JwtService jwtService) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.authenticationManager = authenticationManager;
        this.jwtService = jwtService;
    }

    @Transactional
    public AuthResponse register(RegisterRequest request) {
        if (userRepository.existsByEmail(request.email().toLowerCase())) {
            throw new ApiException(HttpStatus.CONFLICT, "EMAIL_IN_USE", "Este e-mail já está cadastrado.");
        }
        User user = User.builder()
                .fullName(request.fullName())
                .email(request.email().toLowerCase())
                .passwordHash(passwordEncoder.encode(request.password()))
                .role("USER")
                .enabled(true)
                .build();
        user = userRepository.save(user);
        return toResponse(user, jwtService.generateToken(user.getEmail()));
    }

    @Transactional(readOnly = true)
    public AuthResponse login(LoginRequest request) {
        try {
            authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(request.email().toLowerCase(), request.password()));
        } catch (RuntimeException e) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", "E-mail ou senha inválidos.");
        }
        User user = userRepository.findByEmail(request.email().toLowerCase())
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", "E-mail ou senha inválidos."));
        return toResponse(user, jwtService.generateToken(user.getEmail()));
    }

    public AuthResponse toResponse(User user, String token) {
        return new AuthResponse(token, "Bearer", user.getId(), user.getEmail(), user.getFullName(), user.getRole());
    }
}
