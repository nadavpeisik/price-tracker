package com.np.pricehunt.bff;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * The browser-facing session gateway (issue #247, epic #241): a confidential OAuth2 client that logs
 * the user in at Auth0, keeps the access and refresh tokens in a Postgres-backed session, hands the
 * browser one opaque {@code __Host-} cookie, and proxies {@code /bff/api/**} to the backend with a
 * bearer token. The backend (#245) stays a pure resource server and never sees a cookie.
 */
@SpringBootApplication
// Same convention as the backend: every @ConfigurationProperties record lives in config/.
@ConfigurationPropertiesScan("com.np.pricehunt.bff.config")
public class BffApplication {

    public static void main(String[] args) {
        SpringApplication.run(BffApplication.class, args);
    }
}
