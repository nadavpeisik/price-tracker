package com.np.pricehunt.backend.config;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.LoggingEvent;
import com.np.pricehunt.backend.scheduler.PriceCheckScheduler;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.boot.logging.LogFile;
import org.springframework.boot.logging.logback.StructuredLogEncoder;
import org.springframework.core.env.Environment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.DefaultResourceLoader;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Pins the contract epic #274 is built on: the ECS log file carries {@code correlationId} as a
 * top-level JSON field, so Loki can parse it without a custom pipeline.
 *
 * <p>Deliberately not a {@code @SpringBootTest}: Boot's {@code LogbackLoggingSystem.initialize()}
 * returns early once the JVM's {@code LoggerContext} is marked initialized, so the first Spring
 * context in a surefire fork owns logging for the whole run and any assertion made through a context
 * would depend on test ordering.
 */
class EcsStructuredLoggingTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private LoggerContext loggerContext;
    private StructuredLogEncoder encoder;

    @BeforeEach
    void startEncoder() {
        Environment environment = classpathEnvironment();
        loggerContext = new LoggerContext();
        loggerContext.putObject(Environment.class.getName(), environment);
        loggerContext.start();
        encoder = new StructuredLogEncoder();
        // From the committed property, not a hardcoded "ecs".
        encoder.setFormat(environment.getProperty("logging.structured.format.file"));
        encoder.setContext(loggerContext);
        encoder.start();
    }

    @AfterEach
    void stopEncoder() {
        encoder.stop();
        loggerContext.stop();
    }

    @Test
    void requestCorrelationIdIsATopLevelField() {
        String correlationId = UUID.randomUUID().toString();

        JsonNode json = encode(correlationId);

        assertThat(json.path("correlationId").asString()).isEqualTo(correlationId);
    }

    /** {@link PriceCheckScheduler} mints this shape into the same MDC key, with no request behind it. */
    @Test
    void schedulerCorrelationIdIsATopLevelField() {
        String correlationId = "sched-" + UUID.randomUUID();

        JsonNode json = encode(correlationId);

        assertThat(json.path("correlationId").asString()).isEqualTo(correlationId);
    }

    @Test
    void ecsCarriesTheServiceNameLevelAndMessage() {
        JsonNode json = encode(UUID.randomUUID().toString());

        // ECS nests its own namespaces even though it leaves MDC flat.
        assertThat(json.at("/service/name").asString()).isEqualTo("pricehunt-backend");
        assertThat(json.at("/log/level").asString()).isEqualTo("INFO");
        assertThat(json.path("message").asString()).isEqualTo("structured logging probe");
    }

    @Test
    void theContainerProfileLogsEcsToTheConsoleAndDisablesTheFile() {
        Environment environment = classpathEnvironment("container");

        assertThat(environment.getProperty("logging.structured.format.console")).isEqualTo("ecs");
        // Empty logging.file.name => no file appender at all: Docker stdout is the source there.
        assertThat(LogFile.get(environment)).isNull();
    }

    private JsonNode encode(String correlationId) {
        LoggingEvent event = new LoggingEvent();
        event.setLoggerName(getClass().getName());
        event.setLevel(Level.INFO);
        event.setMessage("structured logging probe");
        event.setThreadName(Thread.currentThread().getName());
        event.setTimeStamp(System.currentTimeMillis());
        event.setMDCPropertyMap(Map.of("correlationId", correlationId));
        return JSON.readTree(new String(encoder.encode(event), StandardCharsets.UTF_8));
    }

    /**
     * The committed property files and nothing else: the ambient system-property and environment
     * sources are dropped, and the config-data search is pinned to the classpath, so an exported
     * {@code LOGGING_FILE_NAME} or a stray {@code ./config/application.properties} cannot redden an
     * assertion about what is committed.
     */
    private static StandardEnvironment classpathEnvironment(String... profiles) {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
        environment.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        environment
                .getPropertySources()
                .addFirst(new MapPropertySource("pinned", Map.of("spring.config.location", "classpath:/")));
        ConfigDataEnvironmentPostProcessor.applyTo(environment, new DefaultResourceLoader(), null, profiles);
        return environment;
    }
}
