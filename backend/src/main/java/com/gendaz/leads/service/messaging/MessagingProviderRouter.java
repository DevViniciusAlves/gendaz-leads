package com.gendaz.leads.service.messaging;

import org.springframework.stereotype.Component;
import java.util.Map;

@Component
public class MessagingProviderRouter {
    private final Map<String, MessagingProvider> providers;

    public MessagingProviderRouter(Map<String, MessagingProvider> providers) {
        this.providers = providers;
    }

    public MessagingProvider getProvider(String name) {
        return providers.get(name);
    }
}
