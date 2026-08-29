package com.np.pricehunt.backend.config;

import com.np.pricehunt.backend.service.ExtractionLlmProvider;
import java.net.http.HttpClient;
import org.springframework.ai.model.ollama.autoconfigure.OllamaConnectionDetails;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.ResponseErrorHandler;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Overrides Spring AI's auto-configured {@link OllamaApi} bean so the Ollama client's blocking
 * {@code RestClient} carries explicit connect/read timeouts and an HTTP/1.1 pin (issue #90).
 *
 * <p><b>Why override the bean instead of a global {@code RestClientCustomizer}?</b> A customizer
 * mutates the <em>shared</em> auto-configured {@link RestClient.Builder}, swapping Reactor Netty
 * for a JDK factory for every present/future consumer of the default builder. Constructing our own
 * {@code OllamaApi} keeps the change surgical: we clone the builder and apply the factory only to
 * the Ollama client, touching nothing else. Spring AI's autoconfig declares its {@code OllamaApi}
 * with {@code @ConditionalOnMissingBean}, so this user bean simply wins.
 *
 * <p><b>HTTP/1.1 is deliberate.</b> Ollama is cleartext {@code http://localhost:11434}. The JDK
 * {@link HttpClient}'s default HTTP/2 would attempt an h2c upgrade some servers reject — the same
 * failure mode the scraper hit. Today Boot auto-detects Reactor Netty (HTTP/1.1 on cleartext) so
 * Ollama works, but pinning HTTP/1.1 removes the latent footgun if Reactor Netty ever leaves the
 * classpath and detection falls through to the JDK client.
 *
 * <p><b>Scope: blocking path only.</b> Streaming ({@code .stream()}) and model-pull init
 * ({@code spring.ai.ollama.init.pull-model-strategy=when_missing}) use the {@link WebClient}
 * (reactor-netty) path, which we pass through unchanged — intentional per #90, since extraction
 * uses {@code .call()}. The seam to bound the reactive path later lives in this same method (we
 * already hold the {@code WebClient.Builder}).
 *
 * <p><b>Profile-gated since #121:</b> Groq is the default provider and Ollama is the key-free local
 * fallback, so this configuration — and the properties it binds, whose values now live in
 * {@code application-ollama.properties} — only applies under the {@code ollama} profile.
 */
@Configuration
@Profile("ollama")
public class OllamaClientConfig {

    @Bean
    OllamaApi ollamaApi(
            OllamaConnectionDetails connectionDetails,
            ObjectProvider<RestClient.Builder> restClientBuilderProvider,
            ObjectProvider<WebClient.Builder> webClientBuilderProvider,
            ObjectProvider<ResponseErrorHandler> errorHandlerProvider,
            OllamaClientProperties props) {

        // clone() so the per-Ollama factory never leaks onto the shared RestClient.Builder bean.
        RestClient.Builder restClientBuilder = restClientBuilderProvider
                .getIfAvailable(RestClient::builder)
                .clone()
                .requestFactory(applyTimeouts(props));

        OllamaApi.Builder builder = OllamaApi.builder()
                .baseUrl(connectionDetails.getBaseUrl())
                .restClientBuilder(restClientBuilder)
                .webClientBuilder(webClientBuilderProvider.getIfAvailable(WebClient::builder));
        // Preserve the autoconfig's error handler if one is registered; otherwise let the
        // OllamaApi.Builder default stand.
        errorHandlerProvider.ifAvailable(builder::responseErrorHandler);
        return builder.build();
    }

    @Bean
    ExtractionLlmProvider ollamaExtractionLlmProvider(OllamaChatOptionsProperties options) {
        return new OllamaExtractionLlmProvider(options);
    }

    // Package-private so the unit test can assert the factory without going through OllamaApi.
    static JdkClientHttpRequestFactory applyTimeouts(OllamaClientProperties props) {
        return RestClientFactories.timed(props.connectTimeout(), props.readTimeout(), HttpClient.Version.HTTP_1_1);
    }
}
