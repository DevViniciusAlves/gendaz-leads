package com.gendaz.leads.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Service;

@Service
public class SecurityService {

    public String currentEmail() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            throw new com.gendaz.leads.exception.ApiException(
                    org.springframework.http.HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "Não autenticado");
        }
        Object principal = auth.getPrincipal();
        if (principal instanceof UserDetails ud) {
            return ud.getUsername();
        }
        return auth.getName();
    }

    public org.springframework.security.core.userdetails.UserDetails currentPrincipal() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof UserDetails ud)) {
            throw new com.gendaz.leads.exception.ApiException(
                    org.springframework.http.HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "Não autenticado");
        }
        return ud;
    }
}
