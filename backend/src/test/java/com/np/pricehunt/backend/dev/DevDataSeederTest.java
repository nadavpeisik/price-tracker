package com.np.pricehunt.backend.dev;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.np.pricehunt.backend.repository.ExchangeRateRepository;
import com.np.pricehunt.backend.repository.ProductRepository;
import java.time.Clock;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * The seeder's data shapes are covered by {@link DevDataSeederIdempotencyTest}. This class pins the
 * gate: the seeder is inert unless exactly one of its two profiles is active, because it deletes and
 * rewrites rows on startup.
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

    @Test
    void presentUnderTheSeedCleanProfile() {
        runner.withInitializer(c -> c.getEnvironment().setActiveProfiles("seed-clean"))
                .run(ctx -> assertThat(ctx).hasSingleBean(DevDataSeeder.class));
    }

    /**
     * The two profiles request opposite outcomes, so the run is refused rather than resolved by
     * precedence. Asserting no repository interaction pins the guard above the purge: without it the
     * test still passes if the check ever moves below the delete it is meant to prevent.
     */
    @Test
    void refusesBothProfilesBeforeTouchingAnyData() {
        ProductRepository productRepository = mock(ProductRepository.class);
        ExchangeRateRepository exchangeRateRepository = mock(ExchangeRateRepository.class);

        new ApplicationContextRunner()
                .withBean(ProductRepository.class, () -> productRepository)
                .withBean(ExchangeRateRepository.class, () -> exchangeRateRepository)
                .withBean(Clock.class, Clock::systemUTC)
                .withUserConfiguration(DevDataSeeder.class)
                .withInitializer(c -> c.getEnvironment().setActiveProfiles("seed", "seed-clean"))
                .run(ctx -> {
                    assertThatThrownBy(() -> ctx.getBean(DevDataSeeder.class).run())
                            .isInstanceOf(IllegalStateException.class)
                            .hasMessageContaining("mutually exclusive");
                    verifyNoInteractions(productRepository, exchangeRateRepository);
                });
    }
}
