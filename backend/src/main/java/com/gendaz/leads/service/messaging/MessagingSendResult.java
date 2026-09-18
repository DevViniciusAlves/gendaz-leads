package com.gendaz.leads.service.messaging;

public record MessagingSendResult(
    boolean success,
    FailureCategory failureCategory,
    String providerMessageId,
    String errorCode,
    String detail
) {}
