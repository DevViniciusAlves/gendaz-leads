package com.gendaz.leads.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(JwtAuthenticationFilter.class);

    private final JwtService jwtService;
    private final UserDetailsServiceImpl userDetailsService;
    private final ObjectMapper objectMapper;

    public JwtAuthenticationFilter(JwtService jwtService, UserDetailsServiceImpl userDetailsService, ObjectMapper objectMapper) {
        this.jwtService = jwtService;
        this.userDetailsService = userDetailsService;
        this.objectMapper = objectMapper;
    }

    private boolean isTechnicalFailure(Throwable e) {
        Throwable t = e;
        while (t != null) {
            if (t instanceof org.springframework.dao.DataAccessException
                    || t instanceof java.sql.SQLException
                    || t instanceof jakarta.persistence.PersistenceException
                    || t.getClass().getName().contains("Hikari")) {
                return true;
            }
            t = t.getCause();
        }
        return false;
    }

    private void writeJsonError(HttpServletResponse response, int status, String code, String message) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        objectMapper.writeValue(response.getWriter(), new TechnicalErrorResponse(code, message));
    }

    private record TechnicalErrorResponse(String code, String message) {}

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain filterChain) throws ServletException, IOException {
        final String authHeader = request.getHeader("Authorization");
        final String token;
        final String email;

        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            filterChain.doFilter(request, response);
            return;
        }
        token = authHeader.substring(7);
        try {
            email = jwtService.extractEmail(token);
        } catch (RuntimeException e) {
            filterChain.doFilter(request, response);
            return;
        }

        if (email != null && SecurityContextHolder.getContext().getAuthentication() == null) {
            UserDetails userDetails;
            try {
                userDetails = userDetailsService.loadUserByUsername(email);
            } catch (org.springframework.security.core.userdetails.UsernameNotFoundException e) {
                // Usuário não encontrado no DB: segue sem autenticação (401 via EntryPoint).
                filterChain.doFilter(request, response);
                return;
            } catch (Exception e) {
                // Falha técnica (JPA/JDBC/Hikari): responde 503 para que o frontend NÃO limpe o token.
                if (isTechnicalFailure(e)) {
                    log.warn("JWT filter technical failure: {} ({})", e.getMessage(), e.getClass().getSimpleName());
                    writeJsonError(response, HttpServletResponse.SC_SERVICE_UNAVAILABLE,
                            "AUTH_SERVICE_UNAVAILABLE", "Serviço temporariamente indisponível.");
                    return;
                }
                filterChain.doFilter(request, response);
                return;
            }
            if (jwtService.isTokenValid(token, userDetails)) {
                UsernamePasswordAuthenticationToken authToken = new UsernamePasswordAuthenticationToken(
                        userDetails, null, userDetails.getAuthorities());
                authToken.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                SecurityContextHolder.getContext().setAuthentication(authToken);
            }
        }
        filterChain.doFilter(request, response);
    }

}