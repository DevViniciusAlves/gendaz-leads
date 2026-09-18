package com.gendaz.leads.whatsapp;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "app.whatsapp")
public class WhatsAppProperties {

    /**
     * URL base do whatsapp-service (Node/Baileys). Ex.: http://localhost:3001.
     * Vazio = integracao desabilitada (endpoints retornam 503).
     */
    private String serviceUrl = "";

    /** Token compartilhado enviado como Authorization: Bearer ao Node. Nunca expor em resposta/log. */
    private String internalToken = "";

    /** Identificador estavel da sessao unica (uso pessoal). */
    private String sessionId = "gendaz-leads";

    private int timeoutMs = 15000;

    public String getServiceUrl() {
        return serviceUrl;
    }

    public void setServiceUrl(String serviceUrl) {
        this.serviceUrl = serviceUrl;
    }

    public String getInternalToken() {
        return internalToken;
    }

    public void setInternalToken(String internalToken) {
        this.internalToken = internalToken;
    }

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public int getTimeoutMs() {
        return timeoutMs;
    }

    public void setTimeoutMs(int timeoutMs) {
        this.timeoutMs = timeoutMs;
    }

    public boolean isConfigured() {
        return serviceUrl != null && !serviceUrl.isBlank()
                && internalToken != null && !internalToken.isBlank();
    }
}
