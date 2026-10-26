package com.company.meetingroom.common.time;

import java.time.Clock;
import java.time.ZoneId;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Time is injected, never read with {@code Instant.now()} directly, so rules such as
 * "no booking in the past" or "within working hours" can be tested deterministically
 * by swapping in {@link Clock#fixed}.
 */
@Configuration
public class TimeConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }

    /** The company's business time zone (working hours, "today", daily schedule). */
    @Bean
    public ZoneId businessZone(@Value("${app.zone-id}") String zoneId) {
        return ZoneId.of(zoneId);
    }
}
