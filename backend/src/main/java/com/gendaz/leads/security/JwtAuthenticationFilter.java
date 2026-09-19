package com.gendaz.leads.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
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

    private final JwtService jwtService;
    private final UserDetailsServiceImpl userDetailsService;

    public JwtAuthenticationFilter(JwtService jwtService, UserDetailsServiceImpl userDetailsService) {
        this.jwtService = jwtService;
        this.userDetailsService = userDetailsService;
    }

    private boolean isTechnicalFailure(Throwable e) {
        Throwable t = e;
        while (t != null) {
            if (t instanceof org.springframework.dao.DataAccessException
                    || t instanceof java.sql.SQLException
                    || t.getClass().getName().contains("Hikari")) {
                return true;
            }
            t = t.getCause();
        }
        return false;
    }

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
                    response.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
                    response.setContentType("application/json");
                    response.getWriter().write("{\"code\":\"AUTH_SERVICE_UNAVAILABLE\",\"message\":\"Serviço indisponível.\"}");
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
