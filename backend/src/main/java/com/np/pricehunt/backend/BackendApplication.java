package com.np.pricehunt.backend;

import com.np.pricehunt.backend.config.CurrencyProperties;
import com.np.pricehunt.backend.config.OllamaClientProperties;
import com.np.pricehunt.backend.config.ScraperClientProperties;
import com.np.pricehunt.backend.config.UrlValidationProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties({
    UrlValidationProperties.class,
    CurrencyProperties.class,
    ScraperClientProperties.class,
    OllamaClientProperties.class
})
public class BackendApplication {

    public static void main(String[] args) {
        SpringApplication.run(BackendApplication.class, args);
    }
}
