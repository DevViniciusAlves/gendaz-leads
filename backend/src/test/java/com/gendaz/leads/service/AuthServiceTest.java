package com.gendaz.leads.service;

import com.gendaz.leads.dto.auth.AuthResponse;
import com.gendaz.leads.dto.auth.LoginRequest;
import com.gendaz.leads.dto.auth.RegisterRequest;
import com.gendaz.leads.entity.User;
import com.gendaz.leads.exception.ApiException;
import com.gendaz.leads.repository.UserRepository;
import com.gendaz.leads.security.JwtService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.InternalAuthenticationServiceException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock
    UserRepository userRepository;
    @Mock
    org.springframework.security.crypto.password.PasswordEncoder passwordEncoder;
    @Mock
    AuthenticationManager authenticationManager;
    @Mock
    JwtService jwtService;

    AuthService authService;

    @BeforeEach
    void setup() {
        authService = new AuthService(userRepository, passwordEncoder, authenticationManager, jwtService);
    }

    @Test
    void registerSuccessNormalizesEmailAndHashesWithBCrypt() {
        when(userRepository.existsByEmail("user@test.com")).thenReturn(false);
        when(passwordEncoder.encode("password123")).thenReturn("$2a$12$hashed");
        when(userRepository.save(any(User.class))).thenAnswer(inv -> {
            User u = inv.getArgument(0);
            u.setId(7L);
            return u;
        });
        when(jwtService.generateToken("user@test.com")).thenReturn("jwt-1");

        AuthResponse res = authService.register(
                new RegisterRequest("  Maria Silva  ", "  USER@Test.COM ", "password123"));

        assertEquals(7L, res.userId());
        assertEquals("user@test.com", res.email());
        assertEquals("Maria Silva", res.fullName());
        assertEquals("USER", res.role());
        assertEquals("jwt-1", res.token());

        ArgumentCaptor<User> cap = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(cap.capture());
        User saved = cap.getValue();
        assertEquals("user@test.com", saved.getEmail());
        assertEquals("$2a$12$hashed", saved.getPasswordHash());
        assertEquals("USER", saved.getRole());
        assertTrue(saved.isEnabled());
        // BCrypt strength 12 configurado no SecurityConfig (verificado em teste de config).
        assertTrue(new BCryptPasswordEncoder(12).matches("password123",
                new BCryptPasswordEncoder(12).encode("password123")));
    }

    @Test
    void registerDuplicateReturns409() {
        when(userRepository.existsByEmail("dup@test.com")).thenReturn(true);
        ApiException ex = assertThrows(ApiException.class, () ->
                authService.register(new RegisterRequest("Nome", "dup@test.com", "password123")));
        assertEquals(HttpStatus.CONFLICT, ex.getStatus());
        assertEquals("EMAIL_IN_USE", ex.getCode());
        verify(userRepository, never()).save(any());
    }

    @Test
    void registerRaceConstraintReturns409Not500() {
        when(userRepository.existsByEmail("race@test.com")).thenReturn(false);
        when(passwordEncoder.encode(anyString())).thenReturn("h");
        when(userRepository.save(any(User.class)))
                .thenThrow(new DataIntegrityViolationException("unique email"));
        ApiException ex = assertThrows(ApiException.class, () ->
                authService.register(new RegisterRequest("Nome", " RACE@test.com ", "password123")));
        assertEquals(HttpStatus.CONFLICT, ex.getStatus());
        assertEquals("EMAIL_IN_USE", ex.getCode());
    }

    @Test
    void loginSuccessNormalizesEmailAndReturnsJwt() {
        when(jwtService.generateToken("user@test.com")).thenReturn("jwt-ok");
        User user = User.builder().id(3L).email("user@test.com")
                .fullName("User").passwordHash("h").role("USER").enabled(true).build();
        when(userRepository.findByEmail("user@test.com")).thenReturn(Optional.of(user));

        AuthResponse res = authService.login(new LoginRequest("  USER@test.com ", "password123"));

        assertEquals("jwt-ok", res.token());
        assertEquals(3L, res.userId());
        assertEquals("user@test.com", res.email());
        verify(authenticationManager).authenticate(
                argThat(a -> ((UsernamePasswordAuthenticationToken) a).getPrincipal().equals("user@test.com")));
    }

    @Test
    void loginBadPasswordReturns401InvalidCredentials() {
        when(authenticationManager.authenticate(any()))
                .thenThrow(new BadCredentialsException("bad"));
        ApiException ex = assertThrows(ApiException.class, () ->
                authService.login(new LoginRequest("user@test.com", "wrong")));
        assertEquals(HttpStatus.UNAUTHORIZED, ex.getStatus());
        assertEquals("INVALID_CREDENTIALS", ex.getCode());
    }

    @Test
    void loginUnknownUserReturnsSame401() {
        when(authenticationManager.authenticate(any()))
                .thenThrow(new BadCredentialsException("bad"));
        ApiException ex = assertThrows(ApiException.class, () ->
                authService.login(new LoginRequest("unknown@test.com", "any")));
        assertEquals(HttpStatus.UNAUTHORIZED, ex.getStatus());
        assertEquals("INVALID_CREDENTIALS", ex.getCode());
        assertEquals("E-mail ou senha inválidos.", ex.getMessage());
    }

    @Test
    void loginDisabledReturns403() {
        when(authenticationManager.authenticate(any()))
                .thenThrow(new DisabledException("disabled"));
        ApiException ex = assertThrows(ApiException.class, () ->
                authService.login(new LoginRequest("user@test.com", "password123")));
        assertEquals(HttpStatus.FORBIDDEN, ex.getStatus());
        assertEquals("ACCOUNT_DISABLED", ex.getCode());
    }

    @Test
    void loginDbErrorReturns503NotInvalidCredentials() {
        when(authenticationManager.authenticate(any())).thenThrow(
                new InternalAuthenticationServiceException("db down",
                        new DataAccessResourceFailureException("hikari/neon failure")));
        ApiException ex = assertThrows(ApiException.class, () ->
                authService.login(new LoginRequest("user@test.com", "password123")));
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, ex.getStatus());
        assertEquals("AUTH_SERVICE_UNAVAILABLE", ex.getCode());
    }
}
