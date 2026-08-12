package com.np.pricehunt.backend.dev;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.np.pricehunt.backend.repository.ExchangeRateRepository;
import com.np.pricehunt.backend.repository.ProductRepository;
import java.time.Clock;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * The seeder's data shapes are covered by {@link DevDataSeederIdempotencyTest}. This class pins the
 * one thing that must never regress: it is inert unless the {@code seed} profile is explicitly
 * active, because it deletes and rewrites rows on startup.
 */
class DevDataSeederTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withBean(ProductRepository.class, () -> mock(ProductRepository.class))
            .withBean(ExchangeRateRepository.class, () -> mock(ExchangeRateRepository.class))
            .withBean(Clock.class, Clock::systemUTC)
            .withUserConfiguration(DevDataSeeder.class);

    @Test
    void absentByDefault() {
        runner.run(ctx -> assertThat(ctx).doesNotHaveBean(DevDataSeeder.class));
    }

    @Test
    void absentUnderAnUnrelatedProfile() {
        runner.withInitializer(c -> c.getEnvironment().setActiveProfiles("dev"))
                .run(ctx -> assertThat(ctx).doesNotHaveBean(DevDataSeeder.class));
    }

    @Test
    void presentUnderTheSeedProfile() {
        runner.withInitializer(c -> c.getEnvironment().setActiveProfiles("seed"))
                .run(ctx -> assertThat(ctx).hasSingleBean(DevDataSeeder.class));
    }
}
