package com.gendaz.leads.whatsapp;

/**
 * Resultado do recipient check (sem enviar mensagem).
 * exists=true aprovado; exists=false NOT_ON_WHATSAPP (nao e falha tecnica).
 */
public record WhatsAppRecipientCheckResult(boolean exists, String checkedRecipient, boolean checked) {
    public WhatsAppRecipientCheckResult(boolean exists, String checkedRecipient, boolean checked) {
        this.exists = exists;
        this.checkedRecipient = checkedRecipient;
        this.checked = checked;
    }

    public boolean exists() {
        return exists;
    }
}
