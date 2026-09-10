package com.np.pricehunt.bff.testsupport;

import com.np.pricehunt.bff.config.Auth0ClientRegistrationConfig;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;

/**
 * Points the production-shaped {@code ClientRegistration} at the loopback {@link FakeAuth0} and swaps
 * the application clock for a movable one. The production issuer validation stays https-only: the
 * test profile's {@code https://test-issuer.invalid/} satisfies it and is never dialed, because this
 * registration is what every Auth0 URL is derived from.
 */
@TestConfiguration
public class FakeAuth0Config {

    @Bean
    @Primary
    ClientRegistrationRepository fakeAuth0Registrations() {
        return new InMemoryClientRegistrationRepository(Auth0ClientRegistrationConfig.registration(
                BffIntegrationTest.AUTH0.issuer(), FakeAuth0.CLIENT_ID, FakeAuth0.CLIENT_SECRET));
    }

    /** A {@code Clock} too, so it wins every {@code Clock} injection over {@code ClockConfig}'s. */
    @Bean
    @Primary
    MutableClock mutableClock() {
        return new MutableClock();
    }
}
