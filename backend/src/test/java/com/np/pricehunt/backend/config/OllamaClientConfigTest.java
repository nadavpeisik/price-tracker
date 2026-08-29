package com.np.pricehunt.backend.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;
import java.net.http.HttpClient;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.ai.model.ollama.autoconfigure.OllamaApiAutoConfiguration;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.restclient.autoconfigure.RestClientAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.webclient.autoconfigure.WebClientAutoConfiguration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

class OllamaClientConfigTest {

    private static final OllamaClientProperties PROPS =
            new OllamaClientProperties(Duration.ofSeconds(3), Duration.ofSeconds(120));

    // --- Layer 1: the factory the bean applies carries the configured timeouts + HTTP/1.1 pin. ---

    @Test
    void applyTimeouts_pinsHttp1_1AndConnectTimeout() throws Exception {
        JdkClientHttpRequestFactory factory = OllamaClientConfig.applyTimeouts(PROPS);

        HttpClient client = extractHttpClient(factory);
        assertThat(client.version()).isEqualTo(HttpClient.Version.HTTP_1_1);
        assertThat(client.connectTimeout()).hasValue(PROPS.connectTimeout());
    }

    @Test
    void applyTimeouts_setsReadTimeout() throws Exception {
        JdkClientHttpRequestFactory factory = OllamaClientConfig.applyTimeouts(PROPS);

        assertThat(extractReadTimeout(factory)).isEqualTo(PROPS.readTimeout());
    }

    // --- Layer 2: prove our bean overrides Spring AI's autoconfig and the timeouts reach the
    // real Ollama RestClient end-to-end (not just that a factory was built). ---

    @Test
    void ollamaApiBean_overridesAutoconfig_andReceivesTimedFactory() {
        new ApplicationContextRunner()
                // OllamaClientConfig is @Profile("ollama") since #121 (Groq is the default provider),
                // so the profile must be active or the configuration under test never applies and the
                // autoconfig's Reactor Netty client silently wins. The profile also has to be set
                // before bean registration — the runner evaluates @Profile on registered beans too,
                // including the OllamaClientProperties supplied below.
                .withPropertyValues("spring.profiles.active=ollama")
                .withConfiguration(AutoConfigurations.of(
                        RestClientAutoConfiguration.class,
                        WebClientAutoConfiguration.class,
                        OllamaApiAutoConfiguration.class))
                .withUserConfiguration(OllamaClientConfig.class)
                .withBean(OllamaClientProperties.class, () -> PROPS)
                // Also declared by this configuration since #121; unrelated to the transport under test.
                .withBean(OllamaChatOptionsProperties.class, () -> new OllamaChatOptionsProperties(0.0, "json", 4096))
                .run(context -> {
                    // Exactly one OllamaApi => the autoconfig's @ConditionalOnMissingBean backed off.
                    assertThat(context).hasSingleBean(OllamaApi.class);

                    JdkClientHttpRequestFactory factory = extractOllamaFactory(context.getBean(OllamaApi.class));
                    // A JDK factory (not Reactor Netty, the autoconfig default) proves ours won.
                    assertThat(extractHttpClient(factory).version()).isEqualTo(HttpClient.Version.HTTP_1_1);
                    assertThat(extractReadTimeout(factory)).isEqualTo(PROPS.readTimeout());
                });
    }

    // Reflection guards: Spring/Spring AI expose no getters for these internals, so we read the
    // private fields. If any is renamed in an upgrade, these fail loudly rather than silently.

    private static JdkClientHttpRequestFactory extractOllamaFactory(OllamaApi api) throws Exception {
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

    // Walk up the class hierarchy: the fields we read are declared on the concrete classes today,
    // but traversing superclasses keeps this robust if a future Spring/Spring AI release moves one
    // onto a base class. A genuine rename still fails loudly (NoSuchFieldException at the end).
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
