package com.np.pricehunt.backend.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.np.pricehunt.backend.config.GroqChatOptionsProperties;
import com.np.pricehunt.backend.config.GroqExtractionLlmProvider;
import com.np.pricehunt.backend.config.OllamaChatOptionsProperties;
import com.np.pricehunt.backend.config.OllamaExtractionLlmProvider;
import com.np.pricehunt.backend.config.RestClientFactories;
import com.np.pricehunt.backend.domain.AvailabilityStatus;
import com.np.pricehunt.backend.dto.PriceLlmResult;
import java.io.FileNotFoundException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
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
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.ai.retry.autoconfigure.SpringAiRetryAutoConfiguration;
import org.springframework.ai.retry.autoconfigure.SpringAiRetryProperties;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.boot.convert.DurationStyle;
import org.springframework.web.client.RestClient;

/**
 * Manual prompt-regression sanity suite for {@link LlmPriceExtractionService} (issue #102).
 *
 * <p>This drives the <em>real</em> service against a <em>live provider</em> over a set of labeled
 * snippets ({@code price-extraction/availability-cases.json}), asserting the extracted
 * {@code availability} status. Its purpose is to catch availability regressions when the extraction
 * prompt is edited — the prompt lives only in {@link LlmPriceExtractionService}, so calling the
 * real service keeps it the single source of truth (no prompt duplication).
 *
 * <p><b>Provider-parameterized (issue #121).</b> {@code PROMPT_REGRESSION_PROVIDER} selects
 * {@code groq} (default, matching production) or {@code ollama} (the local fallback);
 * {@code PROMPT_REGRESSION_MODEL} overrides the model. A prompt edit should be checked against both.
 *
 * <p><b>Not a CI gate.</b> It needs a live provider — a {@code GROQ_API_KEY} or {@code ollama serve}
 * with the model pulled — and models are not perfectly deterministic, so it must never run in the
 * normal build. Three independent guards enforce that:
 * <ul>
 *   <li>the {@code *IT} name is not matched by Surefire's default {@code *Test} pattern (no Failsafe
 *       plugin is configured), so {@code ./mvnw test} skips it;</li>
 *   <li>the class-level {@link EnabledIfEnvironmentVariable} disables the whole class (before any
 *       setup runs) unless {@code RUN_PROMPT_REGRESSION=true};</li>
 *   <li>{@code @Tag("llm")} categorizes it for explicit {@code -Dgroups=llm} selection.</li>
 * </ul>
 * Run it via {@code scripts/run-prompt-regression.sh}.
 *
 * <p>The client is built directly (no Spring context) to avoid both the brittle Spring AI autoconfig
 * chain and, on the Ollama side, the {@code init.pull-model-strategy=when_missing} startup model
 * pulls a full context would trigger. Every option that affects reproducibility (temperature,
 * reasoning-effort / format + num-ctx, base-url, timeouts) is read from the production properties
 * files, and transport is built through the same {@link RestClientFactories} helper production uses,
 * so the harness cannot drift from what the app actually sends.
 */
@Tag("llm")
@EnabledIfEnvironmentVariable(named = "RUN_PROMPT_REGRESSION", matches = "true")
class PromptRegressionIT {

    private static final Logger log = LoggerFactory.getLogger(PromptRegressionIT.class);

    /** Default models = the SNIPPET-path model per provider, where the original bug was observed. */
    private static final String DEFAULT_GROQ_MODEL = "openai/gpt-oss-20b";

    private static final String DEFAULT_OLLAMA_MODEL = "qwen3:1.7b";

    private static final String CASES_RESOURCE = "/price-extraction/availability-cases.json";
    private static final String PROPS_RESOURCE = "/application.properties";
    private static final String OLLAMA_PROPS_RESOURCE = "/application-ollama.properties";

    private static LlmPriceExtractionService service;

    /** Resolved once: env override wins, else the default snippet model. Per-case override applied below. */
    private static String defaultModel;

    /** The provider under test ({@code groq} / {@code ollama}); decides which quarantines apply. */
    private static String provider;

    /**
     * One labeled snippet, run against {@link #defaultModel}. {@code quarantinedOn} is optional: list
     * the providers ({@code "ollama"} / {@code "groq"}) whose model sits over its reliability ceiling on
     * this case (a deterministic miss or a flaky one). On those providers the case still runs for
     * visibility but is reported as skipped instead of failing the commit gate (issue #102); on every
     * other provider it is a hard assertion. {@code note} records why.
     *
     * <p>There is deliberately no per-case model override: a bare model id is ambiguous now that the
     * suite runs against two providers (#121), so a fixture pinning e.g. {@code qwen3.5:9b} would be
     * sent verbatim to Groq and fail as an unknown model. Use {@code PROMPT_REGRESSION_MODEL} to
     * re-run the whole suite against a different model instead.
     */
    record Case(
            String name,
            String text,
            AvailabilityStatus expectedAvailability,
            List<String> quarantinedOn,
            String note) {

        boolean isGatedOn(String provider) {
            return quarantinedOn == null || !quarantinedOn.contains(provider);
        }
    }

    @BeforeAll
    static void setUp() throws Exception {
        provider = System.getenv()
                .getOrDefault("PROMPT_REGRESSION_PROVIDER", "groq")
                .toLowerCase();
        if ("ollama".equalsIgnoreCase(provider)) {
            setUpOllama(loadProps(PROPS_RESOURCE, OLLAMA_PROPS_RESOURCE));
        } else if ("groq".equalsIgnoreCase(provider)) {
            setUpGroq(loadProps(PROPS_RESOURCE));
        } else {
            throw new IllegalArgumentException(
                    "PROMPT_REGRESSION_PROVIDER must be 'groq' or 'ollama', but was: " + provider);
        }
    }

    /** Later resources override earlier ones, mirroring how Spring layers a profile over the base file. */
    private static Properties loadProps(String... resources) throws Exception {
        Properties props = new Properties();
        for (String resource : resources) {
            try (InputStream in = PromptRegressionIT.class.getResourceAsStream(resource)) {
                if (in != null) {
                    props.load(in);
                }
            }
        }
        return props;
    }

    /**
     * Resolves the provider base URL, letting the same {@code GROQ_URL} / {@code OLLAMA_URL} environment
     * variables that {@code scripts/run-prompt-regression.sh} prechecks also steer the run. Without this
     * the script would verify one endpoint while the suite quietly called another — a reachability
     * "pass" against a host the models never actually see.
     */
    private static String baseUrlOverride(String envVar, Properties props, String propertyKey, String fallback) {
        String env = System.getenv(envVar);
        String value = (env != null && !env.isBlank()) ? env : props.getProperty(propertyKey, fallback);
        // Trailing slash would double up against Spring AI's leading-slash paths.
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    private static void setUpGroq(Properties props) {
        String apiKey = System.getenv("GROQ_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("GROQ_API_KEY must be set to run the regression against groq");
        }
        String baseUrl =
                baseUrlOverride("GROQ_URL", props, "spring.ai.openai.base-url", "https://api.groq.com/openai/v1");
        String completionsPath = props.getProperty("spring.ai.openai.chat.completions-path", "/chat/completions");
        double temperature = Double.parseDouble(props.getProperty("spring.ai.openai.chat.options.temperature", "0"));
        String reasoningEffort = props.getProperty("spring.ai.openai.chat.options.reasoning-effort", "low");

        defaultModel = System.getenv().getOrDefault("PROMPT_REGRESSION_MODEL", DEFAULT_GROQ_MODEL);

        // Mirror production transport (GroqLlmConfig): HTTP/2 + explicit timeouts from the same props.
        Duration connectTimeout =
                DurationStyle.detectAndParse(props.getProperty("pricehunt.groq.connect-timeout", "5s"));
        Duration readTimeout = DurationStyle.detectAndParse(props.getProperty("pricehunt.groq.read-timeout", "30s"));
        RestClient.Builder restClientBuilder = RestClient.builder()
                .requestFactory(RestClientFactories.timed(connectTimeout, readTimeout, HttpClient.Version.HTTP_2));
        // Mirror production's retry posture too (spring.ai.retry.* -> the same RetryTemplate and
        // 4xx/5xx classifier the autoconfig builds). Without this the harness silently ran with Spring
        // AI's defaults, where a 429 is a plain 4xx and fails fast — the first unpaced Groq run lost 13
        // of 20 cases to free-tier throttling that the app itself would have retried through.
        SpringAiRetryProperties retryProps = retryProperties(props);
        var retryAutoConfig = new SpringAiRetryAutoConfiguration();
        OpenAiApi api = OpenAiApi.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .completionsPath(completionsPath)
                .restClientBuilder(restClientBuilder)
                .responseErrorHandler(retryAutoConfig.responseErrorHandler(retryProps))
                .build();
        OpenAiChatModel chatModel = OpenAiChatModel.builder()
                .openAiApi(api)
                .defaultOptions(OpenAiChatOptions.builder()
                        .temperature(temperature)
                        .reasoningEffort(reasoningEffort)
                        .build())
                .retryTemplate(retryAutoConfig.retryTemplate(retryProps))
                .build();
        // The service sets the model per call; these defaults supply temperature/reasoning-effort,
        // mirroring how Spring AI merges runtime options over bean-level defaults in production.
        service = new LlmPriceExtractionService(
                ChatClient.builder(chatModel),
                new GroqExtractionLlmProvider(new GroqChatOptionsProperties(temperature, reasoningEffort)));

        log.info(
                "Prompt regression setup: provider=groq baseUrl={} defaultModel={} temperature={} reasoningEffort={}",
                baseUrl,
                defaultModel,
                temperature,
                reasoningEffort);
    }

    /** Binds spring.ai.retry.* from the loaded properties files the way Spring Boot would. */
    private static SpringAiRetryProperties retryProperties(Properties props) {
        return new Binder(new MapConfigurationPropertySource(props))
                .bind(SpringAiRetryProperties.CONFIG_PREFIX, SpringAiRetryProperties.class)
                .orElseGet(SpringAiRetryProperties::new);
    }

    private static void setUpOllama(Properties props) {
        String baseUrl = baseUrlOverride("OLLAMA_URL", props, "spring.ai.ollama.base-url", "http://localhost:11434");
        double temperature = Double.parseDouble(props.getProperty("spring.ai.ollama.chat.options.temperature", "0"));
        String format = props.getProperty("spring.ai.ollama.chat.options.format", "json");
        int numCtx = Integer.parseInt(props.getProperty("spring.ai.ollama.chat.options.num-ctx", "4096"));

        defaultModel = System.getenv().getOrDefault("PROMPT_REGRESSION_MODEL", DEFAULT_OLLAMA_MODEL);

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
        service = new LlmPriceExtractionService(
                ChatClient.builder(chatModel),
                new OllamaExtractionLlmProvider(new OllamaChatOptionsProperties(temperature, format, numCtx)));

        log.info(
                "Prompt regression setup: provider=ollama baseUrl={} defaultModel={} temperature={} format={} numCtx={}",
                baseUrl,
                defaultModel,
                temperature,
                format,
                numCtx);
    }

    /** Pure provider: only deserializes the fixtures (no Spring injection — it runs before {@code @BeforeAll}). */
    static Stream<Arguments> cases() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        try (InputStream in = PromptRegressionIT.class.getResourceAsStream(CASES_RESOURCE)) {
            if (in == null) {
                throw new FileNotFoundException("Fixtures not found on classpath: " + CASES_RESOURCE);
            }
            List<Case> cases = mapper.readValue(in, new TypeReference<List<Case>>() {});
            // PROMPT_REGRESSION_ONLY=name1,name2 narrows the run; PROMPT_REGRESSION_REPEAT=N runs each
            // selected case N times. Together they answer the question quarantine decisions hinge on —
            // is a case CONSISTENTLY right on this model, or just right once — without spending the
            // provider's daily token budget on the cases that were never in doubt.
            Set<String> only = Arrays.stream(System.getenv()
                            .getOrDefault("PROMPT_REGRESSION_ONLY", "")
                            .split(","))
                    .map(String::trim)
                    .filter(n -> !n.isEmpty())
                    .collect(Collectors.toSet());
            int repeat = Integer.parseInt(System.getenv().getOrDefault("PROMPT_REGRESSION_REPEAT", "1"));
            return cases.stream()
                    .filter(c -> only.isEmpty() || only.contains(c.name()))
                    .flatMap(c -> IntStream.rangeClosed(1, repeat)
                            .mapToObj(i -> Arguments.of(Named.of(repeat == 1 ? c.name() : c.name() + " #" + i, c))));
        }
    }

    // Optional gap before each case (e.g. PROMPT_REGRESSION_PACE=9s). Groq's free tier is 8,000
    // tokens/min and one call is ~1,100 tokens, so an unpaced run hits 429 after ~7 cases and then
    // leans on retries; pacing keeps the run deterministic and off the rate limiter entirely.
    private static final Duration PACE =
            DurationStyle.detectAndParse(System.getenv().getOrDefault("PROMPT_REGRESSION_PACE", "0s"));

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    void availabilityMatchesExpectation(Case c) throws InterruptedException {
        if (!PACE.isZero()) {
            Thread.sleep(PACE.toMillis());
        }
        String model = defaultModel;
        PriceLlmResult result = service.extractPriceFromText(c.text(), model);
        log.info("case={} model={} expectedAvailability={} got={}", c.name(), model, c.expectedAvailability(), result);

        assertThat(result)
                .as("case '%s' returned a result (model=%s)", c.name(), model)
                .isNotNull();

        // Quarantined cases sit over the small model's reliability ceiling (issue #102). Treat the
        // availability check as an assumption, not an assertion: the case PASSES if the model gets it
        // right (so a more capable model — see #103 — auto-un-quarantines it) and is reported as skipped
        // (not failed) when it misses, so it never blocks the commit gate. A null result above still
        // fails even when quarantined — that's an infra failure, not a model limit.
        //
        // Quarantine is PER PROVIDER (quarantinedOn). Measured 2026-08-29: gpt-oss-20b answered all six
        // originally-quarantined cases correctly 10/10, while qwen3:1.7b still misses four of them
        // deterministically — so those four are quarantined on ollama only, and every case is a hard
        // assertion on groq (production). A global quarantine would either hide a real Groq regression
        // or make the Ollama half of the CLAUDE.md "check both providers" rule permanently red.
        // Availability is the focus, but a prompt edit must not silently break price/currency either.
        // These run BEFORE the quarantine assumption on purpose: quarantine tolerates a wrong
        // AVAILABILITY on hard cases, and nothing else. Asserting after the assumption would let a
        // quarantined case hide a null/zero price too, since an aborted assumption skips the rest.
        assertThat(result.price())
                .as("case '%s' price (model=%s)", c.name(), model)
                .isNotNull()
                .isGreaterThan(BigDecimal.ZERO);
        assertThat(result.currency())
                .as("case '%s' currency (model=%s)", c.name(), model)
                .isNotBlank();

        if (!c.isGatedOn(provider)) {
            Assumptions.assumeTrue(
                    result.availability() == c.expectedAvailability(),
                    () -> String.format(
                            "non-gating known limitation [%s]: expected availability=%s, got %s — %s",
                            c.name(), c.expectedAvailability(), result.availability(), c.note()));
        }

        assertThat(result.availability())
                .as("case '%s' availability (model=%s)", c.name(), model)
                .isEqualTo(c.expectedAvailability());
    }
}
