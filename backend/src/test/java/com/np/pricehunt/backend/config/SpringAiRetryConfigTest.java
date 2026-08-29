package com.np.pricehunt.backend.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.ai.retry.NonTransientAiException;
import org.springframework.ai.retry.TransientAiException;
import org.springframework.ai.retry.autoconfigure.SpringAiRetryAutoConfiguration;
import org.springframework.ai.retry.autoconfigure.SpringAiRetryProperties;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.retry.RetryTemplate;
import org.springframework.core.retry.Retryable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.web.client.ResponseErrorHandler;

// Guards the spring.ai.retry.* posture. Spring AI's max-attempts maps to RetryPolicy.maxRetries()
// (a RETRY count on Spring Framework 7's core.retry.RetryTemplate, which runs the call once before
// consulting the policy), so 0 must mean "execute exactly once, no retries" — not "skip execution".
// If a future Spring AI upgrade changes the property name or semantics, this fails loudly.
class SpringAiRetryConfigTest {

    // The ollama profile's setting (application-ollama.properties): local inference is RAM-bound,
    // so a failure is retried zero times.
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

    // The default (Groq) profile's posture, read from the REAL application.properties: a 429 (the
    // free tier's tokens-per-minute limit, observed 2026-08-29) and a 5xx are retried; a 400 (strict
    // schema violation) fails fast. Asserted on the classifier rather than by executing the template,
    // so the test never sleeps through the 4s/8s/16s backoff it pins.
    @Test
    void groqPosture_retries429And5xx_failsFastOn400() {
        new ApplicationContextRunner()
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withConfiguration(AutoConfigurations.of(SpringAiRetryAutoConfiguration.class))
                .run(context -> {
                    SpringAiRetryProperties props = context.getBean(SpringAiRetryProperties.class);
                    assertThat(props.getMaxAttempts()).isEqualTo(3);
                    assertThat(props.getOnHttpCodes()).containsExactly(429);
                    assertThat(props.getBackoff().getInitialInterval().getSeconds())
                            .isEqualTo(4);

                    ResponseErrorHandler handler = context.getBean(ResponseErrorHandler.class);
                    assertThatThrownBy(() -> handle(handler, HttpStatus.TOO_MANY_REQUESTS))
                            .isInstanceOf(TransientAiException.class);
                    assertThatThrownBy(() -> handle(handler, HttpStatus.BAD_GATEWAY))
                            .isInstanceOf(TransientAiException.class);
                    assertThatThrownBy(() -> handle(handler, HttpStatus.BAD_REQUEST))
                            .isInstanceOf(NonTransientAiException.class);
                });
    }

    private static void handle(ResponseErrorHandler handler, HttpStatus status) throws IOException {
        ClientHttpResponse response = new StubResponse(status);
        assertThat(handler.hasError(response)).isTrue();
        handler.handleError(URI.create("https://api.groq.com/openai/v1/chat/completions"), HttpMethod.POST, response);
    }

    private record StubResponse(HttpStatus status) implements ClientHttpResponse {
        @Override
        public HttpStatusCode getStatusCode() {
            return status;
        }

        @Override
        public String getStatusText() {
            return status.getReasonPhrase();
        }

        @Override
        public void close() {
            // Nothing to release: the body is an in-memory byte array.
        }

        @Override
        public InputStream getBody() {
            return new ByteArrayInputStream("{\"error\":\"stub\"}".getBytes(StandardCharsets.UTF_8));
        }

        @Override
        public HttpHeaders getHeaders() {
            return new HttpHeaders();
        }
    }
}
