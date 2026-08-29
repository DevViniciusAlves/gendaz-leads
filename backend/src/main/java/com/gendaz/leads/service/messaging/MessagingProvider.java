package com.gendaz.leads.service.messaging;

import com.gendaz.leads.entity.Lead;

public interface MessagingProvider {

    String getName();

    SendResult send(Lead lead, String message);

    record SendResult(boolean success, String detail) {}
}
