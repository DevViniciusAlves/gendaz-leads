package com.gendaz.leads.service.messaging;

public record MessagingCommand(
    Long messageSendId,
    Long leadId,
    String recipient,
    String message,
    String requestId
) {}
