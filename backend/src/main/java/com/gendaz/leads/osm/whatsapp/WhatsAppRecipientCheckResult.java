package com.gendaz.leads.osm.whatsapp;

public record WhatsAppRecipientCheckResult(boolean exists, boolean technicalFailure, String code) {
    public static WhatsAppRecipientCheckResult found() {
        return new WhatsAppRecipientCheckResult(true, false, "EXISTS");
    }

    public static WhatsAppRecipientCheckResult absent() {
        return new WhatsAppRecipientCheckResult(false, false, "NOT_ON_WHATSAPP");
    }

    public static WhatsAppRecipientCheckResult infra(String code) {
        return new WhatsAppRecipientCheckResult(false, true, code);
    }
}
