package com.np.pricehunt.backend.config;

import com.np.pricehunt.backend.service.ExtractionLlmProvider;
import java.net.http.HttpClient;
import org.springframework.ai.model.openai.autoconfigure.OpenAIAutoConfigurationUtil;
import org.springframework.ai.model.openai.autoconfigure.OpenAiChatProperties;
import org.springframework.ai.model.openai.autoconfigure.OpenAiConnectionProperties;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.ResponseErrorHandler;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Wires price extraction to Groq Cloud through Spring AI's OpenAI-compatible client (issue #121).
 * Active whenever the {@code ollama} fallback profile is not.
 *
 * <p><b>Why override the {@code OpenAiApi} bean instead of a global {@code RestClientCustomizer}?</b>
 * Same reasoning as {@link OllamaClientConfig}: a customizer mutates the <em>shared</em>
 * auto-configured {@link RestClient.Builder} for every present and future consumer. Cloning the
 * builder here keeps the timeouts scoped to the Groq client. Spring AI declares its own
 * {@code OpenAiApi} with {@code @ConditionalOnMissingBean}, so this user bean simply wins.
 *
 * <p><b>HTTP/2 is deliberate</b>, and the opposite of the Ollama client's HTTP/1.1 pin: Groq is
 * HTTPS, so the JDK client negotiates via ALPN with a clean HTTP/1.1 fallback — none of the h2c
 * upgrade trouble that cleartext localhost has.
 *
 * <p>Connection settings are resolved through the same {@link OpenAIAutoConfigurationUtil} helper
 * the stock autoconfiguration uses, so chat-scoped overrides
 * ({@code spring.ai.openai.chat.base-url} / {@code .api-key}) and resolved headers behave exactly as
 * they would without this override.
 */
@Configuration
@Profile("!ollama")
public class GroqLlmConfig {

    @Bean
    OpenAiApi openAiApi(
            OpenAiConnectionProperties connectionProperties,
            OpenAiChatProperties chatProperties,
            ObjectProvider<RestClient.Builder> restClientBuilderProvider,
            ObjectProvider<WebClient.Builder> webClientBuilderProvider,
            ObjectProvider<ResponseErrorHandler> errorHandlerProvider,
            GroqClientProperties props) {

        var resolved =
                OpenAIAutoConfigurationUtil.resolveConnectionProperties(connectionProperties, chatProperties, "chat");
        requireApiKey(resolved.apiKey());

        // clone() so the per-Groq factory never leaks onto the shared RestClient.Builder bean.
        RestClient.Builder restClientBuilder = restClientBuilderProvider
                .getIfAvailable(RestClient::builder)
                .clone()
                .requestFactory(applyTimeouts(props));

        OpenAiApi.Builder builder = OpenAiApi.builder()
                .baseUrl(resolved.baseUrl())
                .apiKey(resolved.apiKey())
                .headers(resolved.headers())
                .completionsPath(chatProperties.getCompletionsPath())
                .restClientBuilder(restClientBuilder)
                .webClientBuilder(webClientBuilderProvider.getIfAvailable(WebClient::builder));
        // Preserve the autoconfigured error handler: it is what classifies 4xx/5xx into
        // Transient/NonTransientAiException and therefore drives retry (spring.ai.retry.*).
        errorHandlerProvider.ifAvailable(builder::responseErrorHandler);
        return builder.build();
    }

    @Bean
    ExtractionLlmProvider groqExtractionLlmProvider(GroqChatOptionsProperties options) {
        return new GroqExtractionLlmProvider(options);
    }

    /**
     * Fails the boot when no Groq credential is available, rather than letting the app start and 401
     * on the first extraction hours later.
     *
     * <p>The unresolved-placeholder case is the one that actually bites and is easy to miss:
     * {@code spring.ai.openai.api-key=${GROQ_API_KEY}} with the variable unset does NOT raise a
     * binding error — Spring's relaxed binding passes the raw text through, so the property binds to
     * the literal string {@code "${GROQ_API_KEY}"} and that gets sent as the bearer token. Verified
     * against a real context, not assumed. Blank and null are covered for completeness.
     */
    private static void requireApiKey(String apiKey) {
        if (apiKey == null || apiKey.isBlank() || apiKey.startsWith("${")) {
            throw new IllegalStateException(
                    "GROQ_API_KEY is not set, so price extraction cannot authenticate against Groq. "
                            + "Export GROQ_API_KEY, or start with --spring.profiles.active=ollama to use the "
                            + "key-free local model fallback.");
        }
    }

    // Package-private so the unit test can assert the factory without going through OpenAiApi.
    static JdkClientHttpRequestFactory applyTimeouts(GroqClientProperties props) {
        return RestClientFactories.timed(props.connectTimeout(), props.readTimeout(), HttpClient.Version.HTTP_2);
    }
}
