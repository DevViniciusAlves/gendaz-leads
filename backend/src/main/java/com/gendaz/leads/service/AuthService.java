package com.gendaz.leads.service;

import com.gendaz.leads.dto.auth.AuthResponse;
import com.gendaz.leads.dto.auth.LoginRequest;
import com.gendaz.leads.dto.auth.RegisterRequest;
import com.gendaz.leads.entity.User;
import com.gendaz.leads.exception.ApiException;
import com.gendaz.leads.repository.UserRepository;
import com.gendaz.leads.security.JwtService;
import com.gendaz.leads.util.EmailNormalizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.InternalAuthenticationServiceException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

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
        String email = EmailNormalizer.normalizeEmail(request.email());
        String fullName = request.fullName() == null ? null : request.fullName().trim();
        if (userRepository.existsByEmail(email)) {
            throw new ApiException(HttpStatus.CONFLICT, "EMAIL_IN_USE", "Este e-mail já está cadastrado.");
        }
        User user = User.builder()
                .fullName(fullName)
                .email(email)
                .passwordHash(passwordEncoder.encode(request.password()))
                .role("USER")
                .enabled(true)
                .build();
        try {
            user = userRepository.save(user);
        } catch (org.springframework.dao.DataIntegrityViolationException e) {
            // Race: unique constraint de e-mail atingida entre existsByEmail e save.
            log.warn("register email conflict errorType={}", e.getClass().getSimpleName());
            throw new ApiException(HttpStatus.CONFLICT, "EMAIL_IN_USE", "Este e-mail já está cadastrado.");
        }
        return toResponse(user, jwtService.generateToken(user.getEmail()));
    }

    @Transactional(readOnly = true)
    public AuthResponse login(LoginRequest request) {
        String email = EmailNormalizer.normalizeEmail(request.email());
        try {
            authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(email, request.password()));
        } catch (BadCredentialsException e) {
            // Inclui senha incorreta e usuário inexistente (Dao provider oculta a diferença).
            throw new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", "E-mail ou senha inválidos.");
        } catch (DisabledException e) {
            throw new ApiException(HttpStatus.FORBIDDEN, "ACCOUNT_DISABLED", "Conta desativada. Fale com o suporte.");
        } catch (InternalAuthenticationServiceException e) {
            // Falha técnica (JPA/JDBC/Hikari/Neon) — nunca fingir senha errada.
            log.warn("login technical failure errorType={}", rootType(e));
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "AUTH_SERVICE_UNAVAILABLE",
                    "Serviço de autenticação indisponível. Tente novamente em instantes.");
        } catch (org.springframework.security.core.AuthenticationException e) {
            // Outras falhas de autenticação sem causa técnica identificada.
            if (isTechnicalCause(e)) {
                log.warn("login technical failure errorType={}", rootType(e));
                throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "AUTH_SERVICE_UNAVAILABLE",
                        "Serviço de autenticação indisponível. Tente novamente em instantes.");
            }
            throw new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", "E-mail ou senha inválidos.");
        }
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", "E-mail ou senha inválidos."));
        return toResponse(user, jwtService.generateToken(user.getEmail()));
    }

    private boolean isTechnicalCause(Throwable e) {
        Throwable t = e;
        while (t != null) {
            if (t instanceof DataAccessException
                    || t instanceof java.sql.SQLException
                    || t instanceof jakarta.persistence.PersistenceException) {
                return true;
            }
            String name = t.getClass().getName();
            if (name.startsWith("com.zaxxer.hikari.") || name.contains("Hikari")) {
                return true;
            }
            t = t.getCause();
        }
        return false;
    }

    private String rootType(Throwable e) {
        Throwable t = e;
        while (t.getCause() != null && t.getCause() != t) {
            t = t.getCause();
        }
        return t.getClass().getSimpleName();
    }

    public AuthResponse toResponse(User user, String token) {
        return new AuthResponse(token, "Bearer", user.getId(), user.getEmail(), user.getFullName(), user.getRole());
    }
}
