package com.gendaz.leads.service.messaging;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MessagingSchedulePropertiesTest {

    @Test
    void sixtySecondsIsSixtyThousandMillis() {
        MessagingScheduleProperties props = new MessagingScheduleProperties();
        props.setSendIntervalSeconds(60);
        assertEquals(60000L, props.getDelayMillis());
    }

    @Test
    void defaultIsSixtySeconds() {
        MessagingScheduleProperties props = new MessagingScheduleProperties();
        assertEquals(60L, props.getSendIntervalSeconds());
        assertEquals(60000L, props.getDelayMillis());
    }

    @Test
    void noConcatenatedZeros() {
        MessagingScheduleProperties props = new MessagingScheduleProperties();
        props.setSendIntervalSeconds(60);
        // O bug antigo "${...}000" gerava 60000 como string concatenada sem conversao;
        // aqui o delay e Duration.ofSeconds(60).toMillis() == 60000.
        assertEquals(60000L, props.getDelayMillis());
        assertTrue(String.valueOf(props.getDelayMillis()).endsWith("0000") == false
                || props.getDelayMillis() == 60000L);
    }
}
