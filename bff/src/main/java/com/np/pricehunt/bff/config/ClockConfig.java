package com.np.pricehunt.bff.config;

import java.time.Clock;
import java.time.ZoneOffset;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * One clock for every "is this token / session expired" decision, so a test can move time instead of
 * sleeping, and so the refresh coordinator and Spring Security's refresh provider can never disagree
 * about what "now" is.
 */
@Configuration
public class ClockConfig {

    @Bean
    public Clock systemClock() {
        return Clock.system(ZoneOffset.UTC);
    }
}
