package com.gendaz.leads.dto.whatsapp;

import jakarta.validation.constraints.Size;

public class WhatsAppSendRequest {

    @Size(max = 32, message = "recipient excede tamanho maximo")
    private String recipient;

    @Size(max = 4000, message = "text excede 4000 caracteres")
    private String text;

    @Size(max = 120, message = "requestId excede 120 caracteres")
    private String requestId;

    public String getRecipient() {
        return recipient;
    }

    public void setRecipient(String recipient) {
        this.recipient = recipient;
    }

    public String getText() {
        return text;
    }

    public void setText(String text) {
        this.text = text;
    }

    public String getRequestId() {
        return requestId;
    }

    public void setRequestId(String requestId) {
        this.requestId = requestId;
    }
}
