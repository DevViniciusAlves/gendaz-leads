package com.gendaz.leads.service;

public record EligibilityResult(
    boolean eligible,
    String code,
    String reason,
    String normalizedRecipient
) {}
