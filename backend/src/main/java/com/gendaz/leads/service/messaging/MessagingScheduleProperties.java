package com.gendaz.leads.service.messaging;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component("messagingScheduleProperties")
@ConfigurationProperties(prefix = "app.messaging")
public class MessagingScheduleProperties {

    private long sendIntervalSeconds = 60;
    private int maxConcurrentSends = 1;

    public long getSendIntervalSeconds() {
        return sendIntervalSeconds;
    }

    public void setSendIntervalSeconds(long sendIntervalSeconds) {
        this.sendIntervalSeconds = sendIntervalSeconds;
    }

    public int getMaxConcurrentSends() {
        return maxConcurrentSends;
    }

    public void setMaxConcurrentSends(int maxConcurrentSends) {
        this.maxConcurrentSends = maxConcurrentSends;
    }

    public long getDelayMillis() {
        return Duration.ofSeconds(Math.max(1, sendIntervalSeconds)).toMillis();
    }
}
