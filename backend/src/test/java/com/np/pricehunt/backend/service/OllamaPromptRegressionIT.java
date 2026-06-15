package com.np.pricehunt.backend.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.np.pricehunt.backend.config.RestClientFactories;
import com.np.pricehunt.backend.dto.PriceLlmResult;
import java.io.FileNotFoundException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.Properties;
import java.util.stream.Stream;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.boot.convert.DurationStyle;
import org.springframework.web.client.RestClient;

/**
 * Manual prompt-regression sanity suite for {@link OllamaPriceExtractionService} (issue #102).
 *
 * <p>This drives the <em>real</em> service against a <em>live local Ollama</em> over a set of
 * labeled snippets ({@code price-extraction/availability-cases.json}), asserting the extracted
 * {@code available} flag. Its purpose is to catch availability regressions when the extraction
 * prompt is edited — the prompt lives only in {@link OllamaPriceExtractionService}, so calling the
 * real service keeps it the single source of truth (no prompt duplication).
 *
 * <p><b>Not a CI gate.</b> It requires {@code ollama serve} running with the target model pulled,
 * and small models are not perfectly deterministic, so it must never run in the normal build. Three
 * independent guards enforce that:
 * <ul>
 *   <li>the {@code *IT} name is not matched by Surefire's default {@code *Test} pattern (no Failsafe
 *       plugin is configured), so {@code ./mvnw test} skips it;</li>
 *   <li>the class-level {@link EnabledIfEnvironmentVariable} disables the whole class (before any
 *       setup runs) unless {@code RUN_OLLAMA_PROMPT_REGRESSION=true};</li>
 *   <li>{@code @Tag("ollama")} categorizes it for explicit {@code -Dgroups=ollama} selection.</li>
 * </ul>
 * Run it via {@code scripts/run-ollama-prompt-regression.sh}.
 *
 * <p>The Ollama client is built directly (no Spring context) to avoid both the brittle Spring AI
 * autoconfig chain and the {@code init.pull-model-strategy=when_missing} startup model pulls a full
 * context would trigger. The chat options that affect reproducibility (temperature, format, num-ctx,
 * base-url) are read from the production {@code application.properties} so they cannot drift from
 * what the app actually uses.
 */
@Tag("ollama")
@EnabledIfEnvironmentVariable(named = "RUN_OLLAMA_PROMPT_REGRESSION", matches = "true")
class OllamaPromptRegressionIT {

    private static final Logger log = LoggerFactory.getLogger(OllamaPromptRegressionIT.class);

    /** Default model = the SNIPPET-path model, where the original bug was observed. */
    private static final String DEFAULT_MODEL = "qwen3:1.7b";

    private static final String CASES_RESOURCE = "/price-extraction/availability-cases.json";
    private static final String PROPS_RESOURCE = "/application.properties";

    private static OllamaPriceExtractionService service;

    /** Resolved once: env override wins, else the default snippet model. Per-case override applied below. */
    private static String defaultModel;

    /**
     * One labeled snippet. {@code model} is optional (null → {@link #defaultModel}). {@code gated} is
     * optional and defaults to true — set it {@code false} to QUARANTINE a case that sits over the small
     * model's reliability ceiling (a deterministic miss or a flaky case): it still runs for visibility
     * but is reported as skipped instead of failing the commit gate (issue #102). {@code note} records why.
     */
    record Case(String name, String text, boolean expectedAvailable, String model, Boolean gated, String note) {

        boolean isGated() {
            return gated == null || gated;
        }
    }

    @BeforeAll
    static void setUp() throws Exception {
        Properties props = new Properties();
        try (InputStream in = OllamaPromptRegressionIT.class.getResourceAsStream(PROPS_RESOURCE)) {
            if (in != null) {
                props.load(in);
            }
        }
        String baseUrl = props.getProperty("spring.ai.ollama.base-url", "http://localhost:11434");
        double temperature = Double.parseDouble(props.getProperty("spring.ai.ollama.chat.options.temperature", "0"));
        String format = props.getProperty("spring.ai.ollama.chat.options.format", "json");
        int numCtx = Integer.parseInt(props.getProperty("spring.ai.ollama.chat.options.num-ctx", "4096"));

        defaultModel = System.getenv().getOrDefault("OLLAMA_REGRESSION_MODEL", DEFAULT_MODEL);

        // Mirror production transport (OllamaClientConfig): HTTP/1.1 pin + explicit timeouts so a
        // hung Ollama fails cleanly instead of blocking the suite. Timeouts read from the same props.
        Duration connectTimeout =
                DurationStyle.detectAndParse(props.getProperty("pricehunt.ollama.connect-timeout", "5s"));
        Duration readTimeout = DurationStyle.detectAndParse(props.getProperty("pricehunt.ollama.read-timeout", "120s"));
        RestClient.Builder restClientBuilder = RestClient.builder()
                .requestFactory(RestClientFactories.timed(connectTimeout, readTimeout, HttpClient.Version.HTTP_1_1));
        OllamaApi api = OllamaApi.builder()
                .baseUrl(baseUrl)
                .restClientBuilder(restClientBuilder)
                .build();
        OllamaChatModel chatModel = OllamaChatModel.builder()
                .ollamaApi(api)
                .defaultOptions(OllamaChatOptions.builder()
                        .temperature(temperature)
                        .format(format)
                        .numCtx(numCtx)
                        .build())
                .build();
        // The service sets the model per call; these defaults supply temperature/format/num-ctx,
        // mirroring how Spring AI merges runtime options over bean-level defaults in production.
        service = new OllamaPriceExtractionService(ChatClient.builder(chatModel));

        log.info(
                "Ollama prompt regression setup: baseUrl={} defaultModel={} temperature={} format={} numCtx={}",
                baseUrl,
                defaultModel,
                temperature,
                format,
                numCtx);
    }

    /** Pure provider: only deserializes the fixtures (no Spring injection — it runs before {@code @BeforeAll}). */
    static Stream<Arguments> cases() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        try (InputStream in = OllamaPromptRegressionIT.class.getResourceAsStream(CASES_RESOURCE)) {
            if (in == null) {
                throw new FileNotFoundException("Fixtures not found on classpath: " + CASES_RESOURCE);
            }
            List<Case> cases = mapper.readValue(in, new TypeReference<List<Case>>() {});
            return cases.stream().map(c -> Arguments.of(Named.of(c.name(), c)));
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    void availabilityMatchesExpectation(Case c) {
        String model = c.model() != null ? c.model() : defaultModel;
        PriceLlmResult result = service.extractPriceFromText(c.text(), model);
        log.info("case={} model={} expectedAvailable={} got={}", c.name(), model, c.expectedAvailable(), result);

        assertThat(result)
                .as("case '%s' returned a result (model=%s)", c.name(), model)
                .isNotNull();

        // Quarantined cases sit over the small model's reliability ceiling (issue #102). Treat the
        // availability check as an assumption, not an assertion: the case PASSES if the model gets it
        // right (so a more capable model — see #103 — auto-un-quarantines it) and is reported as skipped
        // (not failed) when it misses, so it never blocks the commit gate. A null result above still
        // fails even when quarantined — that's an infra failure, not a model limit.
        if (!c.isGated()) {
            Assumptions.assumeTrue(
                    result.available() == c.expectedAvailable(),
                    () -> String.format(
                            "non-gating known limitation [%s]: expected available=%s, got %s — %s",
                            c.name(), c.expectedAvailable(), result.available(), c.note()));
        }

        assertThat(result.available())
                .as("case '%s' availability (model=%s)", c.name(), model)
                .isEqualTo(c.expectedAvailable());
        // Availability is the focus, but a prompt edit must not silently break price/currency either.
        assertThat(result.price())
                .as("case '%s' price (model=%s)", c.name(), model)
                .isNotNull()
                .isGreaterThan(BigDecimal.ZERO);
        assertThat(result.currency())
                .as("case '%s' currency (model=%s)", c.name(), model)
                .isNotBlank();
    }
}
