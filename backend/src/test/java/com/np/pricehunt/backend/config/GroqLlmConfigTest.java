package com.np.pricehunt.backend.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;
import java.net.http.HttpClient;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.ai.model.openai.autoconfigure.OpenAiChatProperties;
import org.springframework.ai.model.openai.autoconfigure.OpenAiConnectionProperties;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.restclient.autoconfigure.RestClientAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.webclient.autoconfigure.WebClientAutoConfiguration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

class GroqLlmConfigTest {

    private static final GroqClientProperties PROPS =
            new GroqClientProperties(Duration.ofSeconds(3), Duration.ofSeconds(30));

    // --- Layer 1: the factory the bean applies carries the configured timeouts + HTTP/2. ---

    @Test
    void applyTimeouts_usesHttp2AndConnectTimeout() throws Exception {
        // HTTP/2 (not the Ollama client's HTTP/1.1 pin): Groq is HTTPS, so ALPN negotiates cleanly.
        JdkClientHttpRequestFactory factory = GroqLlmConfig.applyTimeouts(PROPS);

        HttpClient client = extractHttpClient(factory);
        assertThat(client.version()).isEqualTo(HttpClient.Version.HTTP_2);
        assertThat(client.connectTimeout()).hasValue(PROPS.connectTimeout());
    }

    @Test
    void applyTimeouts_setsReadTimeout() throws Exception {
        JdkClientHttpRequestFactory factory = GroqLlmConfig.applyTimeouts(PROPS);

        assertThat(extractReadTimeout(factory)).isEqualTo(PROPS.readTimeout());
    }

    // --- Layer 2: our bean overrides Spring AI's autoconfig, and the Groq base-url/completions-path
    // reach the real client end-to-end. ---

    @Test
    void openAiApiBean_overridesAutoconfig_andAppliesGroqEndpointAndTimeouts() {
        new ApplicationContextRunner()
                .withConfiguration(
                        AutoConfigurations.of(RestClientAutoConfiguration.class, WebClientAutoConfiguration.class))
                .withUserConfiguration(GroqLlmConfig.class)
                .withBean(GroqClientProperties.class, () -> PROPS)
                .withBean(GroqChatOptionsProperties.class, () -> new GroqChatOptionsProperties(0.0, "low"))
                .withPropertyValues(
                        "spring.ai.openai.base-url=https://api.groq.com/openai/v1",
                        "spring.ai.openai.api-key=test-key",
                        "spring.ai.openai.chat.completions-path=/chat/completions")
                .withConfiguration(AutoConfigurations.of(GroqConnectionPropertiesConfig.class))
                .run(context -> {
                    assertThat(context).hasSingleBean(OpenAiApi.class);

                    JdkClientHttpRequestFactory factory = extractOpenAiFactory(context.getBean(OpenAiApi.class));
                    assertThat(extractHttpClient(factory).version()).isEqualTo(HttpClient.Version.HTTP_2);
                    assertThat(extractReadTimeout(factory)).isEqualTo(PROPS.readTimeout());

                    // The doubled-/v1 trap: base-url already ends in /openai/v1, so the completions path
                    // must be /chat/completions, never Spring AI's /v1/chat/completions default.
                    assertThat(readField(context.getBean(OpenAiApi.class), "baseUrl"))
                            .isEqualTo("https://api.groq.com/openai/v1");
                    assertThat(readField(context.getBean(OpenAiApi.class), "completionsPath"))
                            .isEqualTo("/chat/completions");
                });
    }

    // Spring AI binds these two records itself in production (they live in its autoconfigure module);
    // the runner needs them registered explicitly.
    @org.springframework.boot.context.properties.EnableConfigurationProperties({
        OpenAiConnectionProperties.class,
        OpenAiChatProperties.class
    })
    static class GroqConnectionPropertiesConfig {}

    // Reflection guards: Spring/Spring AI expose no getters for these internals, so we read the
    // private fields. If any is renamed in an upgrade, these fail loudly rather than silently.

    private static JdkClientHttpRequestFactory extractOpenAiFactory(OpenAiApi api) throws Exception {
        RestClient restClient = (RestClient) readField(api, "restClient");
        Object factory = readField(restClient, "clientRequestFactory");
        assertThat(factory).isInstanceOf(JdkClientHttpRequestFactory.class);
        return (JdkClientHttpRequestFactory) factory;
    }

    private static HttpClient extractHttpClient(JdkClientHttpRequestFactory factory) throws Exception {
        return (HttpClient) readField(factory, "httpClient");
    }

    private static Duration extractReadTimeout(JdkClientHttpRequestFactory factory) throws Exception {
        return (Duration) readField(factory, "readTimeout");
    }

    private static Object readField(Object target, String name) throws Exception {
        for (Class<?> clazz = target.getClass(); clazz != null; clazz = clazz.getSuperclass()) {
            try {
                Field field = clazz.getDeclaredField(name);
                field.setAccessible(true);
                return field.get(target);
            } catch (NoSuchFieldException keepWalking) {
                // try the superclass
            }
        }
        throw new NoSuchFieldException(name + " not found on " + target.getClass() + " or its superclasses");
    }
}
