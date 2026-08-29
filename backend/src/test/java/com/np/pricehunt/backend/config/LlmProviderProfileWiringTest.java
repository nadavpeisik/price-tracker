package com.np.pricehunt.backend.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.np.pricehunt.backend.service.ExtractionLlmProvider;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.model.chat.client.autoconfigure.ChatClientAutoConfiguration;
import org.springframework.ai.model.ollama.autoconfigure.OllamaApiAutoConfiguration;
import org.springframework.ai.model.ollama.autoconfigure.OllamaChatAutoConfiguration;
import org.springframework.ai.model.openai.autoconfigure.OpenAiChatAutoConfiguration;
import org.springframework.ai.model.tool.autoconfigure.ToolCallingAutoConfiguration;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.retry.autoconfigure.SpringAiRetryAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.boot.restclient.autoconfigure.RestClientAutoConfiguration;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.webclient.autoconfigure.WebClientAutoConfiguration;
import org.springframework.context.annotation.Configuration;

/**
 * Guards the provider bean-selection path introduced by #121 — the most delicate part of running two
 * chat providers on one classpath, and previously only verifiable by booting the app twice by hand.
 *
 * <p>Both starters' chat autoconfigurations default to ON ({@code matchIfMissing=true}), so without
 * the {@code spring.ai.model.chat} keys in the properties files the context would end up with two
 * {@link ChatModel} beans and fail. These tests load the REAL {@code application.properties} /
 * {@code application-ollama.properties} through {@link ConfigDataApplicationContextInitializer} —
 * a bare runner reads no config files at all, which would make them pass against synthetic values
 * while production stayed broken.
 */
class LlmProviderProfileWiringTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withConfiguration(AutoConfigurations.of(
                    RestClientAutoConfiguration.class,
                    WebClientAutoConfiguration.class,
                    SpringAiRetryAutoConfiguration.class,
                    ToolCallingAutoConfiguration.class,
                    ChatClientAutoConfiguration.class,
                    OpenAiChatAutoConfiguration.class,
                    OllamaApiAutoConfiguration.class,
                    OllamaChatAutoConfiguration.class))
            .withUserConfiguration(PropertiesScanConfig.class);

    @Test
    void defaultProfile_wiresGroqOnly() {
        runner.withPropertyValues("spring.ai.openai.api-key=test-key").run(context -> {
            assertThat(context).hasNotFailed();
            // Exactly one ChatModel — proves spring.ai.model.chat=openai suppressed the Ollama side.
            assertThat(context).hasSingleBean(ChatModel.class);
            assertThat(context).hasSingleBean(OpenAiChatModel.class);
            assertThat(context).doesNotHaveBean(OllamaChatModel.class);

            assertThat(context).hasSingleBean(ExtractionLlmProvider.class);
            assertThat(context.getBean(ExtractionLlmProvider.class).name()).isEqualTo("groq");
            // Groq's typed option mirrors bind; the Ollama ones are profile-gated out.
            assertThat(context).hasSingleBean(GroqChatOptionsProperties.class);
            assertThat(context).doesNotHaveBean(OllamaChatOptionsProperties.class);
            assertThat(context).doesNotHaveBean(OllamaClientProperties.class);
        });
    }

    @Test
    void defaultProfile_withUnresolvedKeyPlaceholder_failsFastNamingTheEnvironmentVariable() {
        // The real-world shape of "GROQ_API_KEY isn't exported", and the reason requireApiKey exists:
        // an unset variable raises NO binding error at all. Spring's relaxed binding passes the raw
        // text through, so spring.ai.openai.api-key binds to the literal "${...}" string and that would
        // be sent as the bearer token, producing a 401 on the first extraction instead of a boot error.
        //
        // The placeholder is a test-only name rather than GROQ_API_KEY itself: reading the real
        // variable would make this test pass or fail depending on whether the developer running it
        // happens to have a key exported — which is the normal state for anyone who runs the app.
        runner.withPropertyValues("spring.ai.openai.api-key=${PRICEHUNT_TEST_DELIBERATELY_UNSET_KEY}")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).hasStackTraceContaining("GROQ_API_KEY");
                });
    }

    @Test
    void defaultProfile_withBlankApiKey_failsFast() {
        // A blank key never reaches requireApiKey — Spring AI's own connection resolver rejects it
        // first, with its own wording — so this asserts the outcome (boot stops) rather than our
        // message. It's the unresolved-placeholder case above that Spring AI lets through and that
        // our guard exists for; both are pinned so neither line of defence can silently disappear.
        runner.withPropertyValues("spring.ai.openai.api-key=")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void ollamaProfile_wiresOllamaOnly_andNeedsNoGroqKey() {
        // No api-key property anywhere: the ollama profile must boot key-free, which only holds because
        // spring.ai.model.chat=ollama (plus the spring.ai.model.*=none keys) disables every OpenAI
        // autoconfiguration, so OpenAiConnectionProperties never binds the ${GROQ_API_KEY} placeholder.
        // pull-model-strategy=never: the profile's default (when_missing) makes OllamaChatModel call
        // /api/tags at bean init, which passes wherever `ollama serve` happens to be running and
        // fails everywhere else — CI, or a machine using the Groq default. Wiring, not networking,
        // is what this test pins.
        runner.withPropertyValues("spring.profiles.active=ollama", "spring.ai.ollama.init.pull-model-strategy=never")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(ChatModel.class);
                    assertThat(context).hasSingleBean(OllamaChatModel.class);
                    assertThat(context).doesNotHaveBean(OpenAiChatModel.class);

                    assertThat(context).hasSingleBean(ExtractionLlmProvider.class);
                    assertThat(context.getBean(ExtractionLlmProvider.class).name())
                            .isEqualTo("ollama");
                    assertThat(context).hasSingleBean(OllamaChatOptionsProperties.class);
                    assertThat(context).doesNotHaveBean(GroqChatOptionsProperties.class);
                    assertThat(context).doesNotHaveBean(GroqClientProperties.class);
                });
    }

    /**
     * Mirrors the application's own {@code @ConfigurationPropertiesScan} so the profile-gated
     * properties records are registered through the same code path as production — the path that
     * evaluates {@code @Profile} on scanned records.
     */
    @Configuration(proxyBeanMethods = false)
    @ConfigurationPropertiesScan("com.np.pricehunt.backend.config")
    @org.springframework.context.annotation.ComponentScan(
            basePackageClasses = GroqLlmConfig.class,
            useDefaultFilters = false,
            includeFilters =
                    @org.springframework.context.annotation.ComponentScan.Filter(
                            type = org.springframework.context.annotation.FilterType.ASSIGNABLE_TYPE,
                            classes = {GroqLlmConfig.class, OllamaClientConfig.class}))
    static class PropertiesScanConfig {}
}
