package com.gendaz.leads.osm.whatsapp;

public class WhatsAppInfrastructureException extends RuntimeException {
    private final String code;

    public WhatsAppInfrastructureException(String code, String message) {
        super(message);
        this.code = code;
    }

    public WhatsAppInfrastructureException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
