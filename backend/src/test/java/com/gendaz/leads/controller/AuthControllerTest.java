package com.gendaz.leads.controller;

import com.gendaz.leads.dto.auth.AuthResponse;
import com.gendaz.leads.entity.User;
import com.gendaz.leads.repository.UserRepository;
import com.gendaz.leads.security.SecurityService;
import com.gendaz.leads.service.AuthService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AuthControllerTest {

    @Mock
    AuthService authService;
    @Mock
    SecurityService securityService;
    @Mock
    UserRepository userRepository;

    @InjectMocks
    AuthController authController;

    @Test
    void meReturnsRealUserFields() {
        when(securityService.currentEmail()).thenReturn("user@test.com");
        User user = User.builder().id(42L).email("user@test.com")
                .fullName("Maria Silva").passwordHash("h").role("USER").enabled(true).build();
        when(userRepository.findByEmail("user@test.com")).thenReturn(Optional.of(user));

        ResponseEntity<AuthResponse> res = authController.me();

        assertEquals(HttpStatus.OK, res.getStatusCode());
        assertNotNull(res.getBody());
        assertEquals(42L, res.getBody().userId());
        assertEquals("user@test.com", res.getBody().email());
        assertEquals("Maria Silva", res.getBody().fullName());
        assertEquals("USER", res.getBody().role());
    }

    @Test
    void registerReturns201() {
        AuthResponse body = new AuthResponse("jwt", "Bearer", 1L, "a@b.com", "Nome", "USER");
        when(authService.register(any())).thenReturn(body);
        ResponseEntity<AuthResponse> res = authController.register(
                new com.gendaz.leads.dto.auth.RegisterRequest("Nome", "a@b.com", "password123"));
        assertEquals(HttpStatus.CREATED, res.getStatusCode());
        assertEquals(body, res.getBody());
    }
}
