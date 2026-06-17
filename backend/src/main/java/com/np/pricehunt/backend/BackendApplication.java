package com.np.pricehunt.backend;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
// Scoped to the config package: every @ConfigurationProperties record lives there, so the folder is
// the authoritative roster — a new record is auto-registered, and one placed elsewhere is not, which
// keeps the convention enforced rather than merely habitual.
@ConfigurationPropertiesScan("com.np.pricehunt.backend.config")
public class BackendApplication {

    public static void main(String[] args) {
        SpringApplication.run(BackendApplication.class, args);
    }
}
