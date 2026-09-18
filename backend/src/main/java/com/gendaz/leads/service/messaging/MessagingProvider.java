package com.gendaz.leads.service.messaging;

public interface MessagingProvider {

    String getName();

    MessagingSendResult send(MessagingCommand command);
}
