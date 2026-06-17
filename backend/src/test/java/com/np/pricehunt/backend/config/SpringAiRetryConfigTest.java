package com.np.pricehunt.backend.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.ai.retry.TransientAiException;
import org.springframework.ai.retry.autoconfigure.SpringAiRetryAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.retry.RetryTemplate;
import org.springframework.core.retry.Retryable;

// Guards the spring.ai.retry.max-attempts=0 setting in application.properties. Spring AI's
// max-attempts maps to RetryPolicy.maxRetries() (a RETRY count on Spring Framework 7's
// core.retry.RetryTemplate, which runs the call once before consulting the policy), so 0 must mean
// "execute exactly once, no retries" — not "skip execution". If a future Spring AI upgrade changes
// the property name or semantics, this fails loudly.
class SpringAiRetryConfigTest {

    @Test
    void maxAttemptsZero_invokesOperationExactlyOnce() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(SpringAiRetryAutoConfiguration.class))
                .withPropertyValues("spring.ai.retry.max-attempts=0")
                .run(context -> {
                    RetryTemplate retryTemplate = context.getBean(RetryTemplate.class);
                    AtomicInteger calls = new AtomicInteger();

                    Retryable<Object> alwaysFails = () -> {
                        calls.incrementAndGet();
                        throw new TransientAiException("boom");
                    };

                    assertThatThrownBy(() -> retryTemplate.execute(alwaysFails)).isNotNull();
                    assertThat(calls.get()).isEqualTo(1);
                });
    }
}
