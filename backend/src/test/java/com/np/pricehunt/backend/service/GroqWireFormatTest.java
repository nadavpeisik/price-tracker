package com.np.pricehunt.backend.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.np.pricehunt.backend.config.GroqChatOptionsProperties;
import com.np.pricehunt.backend.config.GroqExtractionLlmProvider;
import com.np.pricehunt.backend.domain.AvailabilityStatus;
import com.np.pricehunt.backend.dto.PriceLlmResult;
import com.np.pricehunt.backend.exception.MalformedLlmOutputException;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * Pins the actual HTTP request the Groq path puts on the wire (issue #121), with no network.
 *
 * <p><b>Why this exists on top of the options assertions in {@link LlmPriceExtractionServiceTest}.</b>
 * Those prove the advisor half — that the schema reaches the per-call {@code ChatOptions}. They stop
 * short of proving that {@code OpenAiChatModel} then merges the bean-level defaults correctly and
 * serializes everything into the request body. Between those two halves sit exactly the failures
 * this migration is most exposed to: a doubled {@code /v1} in the URL, a dropped
 * {@code reasoning_effort}, or a {@code response_format} that arrives without {@code strict}. A live
 * smoke test catches those once, by hand; this catches them on every CI run.
 */
class GroqWireFormatTest {

    private static final String BASE_URL = "https://api.groq.com/openai/v1";
    private static final String MODEL = "openai/gpt-oss-20b";

    private static final String COMPLETION_RESPONSE =
            """
            {
              "id": "chatcmpl-test",
              "object": "chat.completion",
              "created": 1,
              "model": "openai/gpt-oss-20b",
              "choices": [{
                "index": 0,
                "message": {
                  "role": "assistant",
                  "content": "{\\"price\\": 24.99, \\"currency\\": \\"USD\\", \\"availability\\": \\"AVAILABLE\\"}"
                },
                "finish_reason": "stop"
              }],
              "usage": {"prompt_tokens": 100, "completion_tokens": 20, "total_tokens": 120}
            }
            """;

    private static final String REFUSAL_RESPONSE =
            """
            {
              "id": "chatcmpl-refusal",
              "object": "chat.completion",
              "created": 1,
              "model": "openai/gpt-oss-20b",
              "choices": [{
                "index": 0,
                "message": {"role": "assistant", "content": null, "refusal": "I can't help with that."},
                "finish_reason": "stop"
              }],
              "usage": {"prompt_tokens": 100, "completion_tokens": 5, "total_tokens": 105}
            }
            """;

    private record Harness(LlmPriceExtractionService service, MockRestServiceServer server) {}

    /** Builds the service over a real OpenAiApi/OpenAiChatModel whose transport is a mock server. */
    private static Harness harness() {
        RestClient.Builder restClientBuilder = RestClient.builder();
        MockRestServiceServer server =
                MockRestServiceServer.bindTo(restClientBuilder).build();
        OpenAiApi api = OpenAiApi.builder()
                .baseUrl(BASE_URL)
                .apiKey("test-key")
                .completionsPath("/chat/completions")
                .restClientBuilder(restClientBuilder)
                .build();
        // Production-shaped bean-level defaults — the half a mocked ChatModel can never exercise.
        OpenAiChatModel chatModel = OpenAiChatModel.builder()
                .openAiApi(api)
                .defaultOptions(OpenAiChatOptions.builder()
                        .temperature(0.0)
                        .reasoningEffort("low")
                        .build())
                .build();
        LlmPriceExtractionService service = new LlmPriceExtractionService(
                ChatClient.builder(chatModel),
                new GroqExtractionLlmProvider(new GroqChatOptionsProperties(0.0, "low")));
        return new Harness(service, server);
    }

    @Test
    void request_hitsGroqCompletionsPathWithStrictSchemaAndMergedDefaults() {
        Harness h = harness();
        h.server()
                .expect(requestTo(BASE_URL + "/chat/completions")) // NOT /openai/v1/v1/... — the doubled-path trap
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                // Per-call option (the model) survives the merge...
                .andExpect(jsonPath("$.model").value(MODEL))
                // ...and so do the bean-level defaults, which only the real ChatModel applies.
                .andExpect(jsonPath("$.temperature").value(0.0))
                .andExpect(jsonPath("$.reasoning_effort").value("low"))
                // Native structured output, serialized the way Groq's strict mode requires.
                .andExpect(jsonPath("$.response_format.type").value("json_schema"))
                .andExpect(jsonPath("$.response_format.json_schema.strict").value(true))
                .andExpect(jsonPath("$.response_format.json_schema.schema.additionalProperties")
                        .value(false))
                .andExpect(jsonPath("$.response_format.json_schema.schema.properties.availability")
                        .exists())
                // The prompt still reaches the model as system + user messages.
                .andExpect(jsonPath("$.messages[0].role").value("system"))
                .andExpect(jsonPath("$.messages[1].role").value("user"))
                .andRespond(withSuccess(COMPLETION_RESPONSE, MediaType.APPLICATION_JSON));

        PriceLlmResult result = h.service().extractPriceFromText("Sale price $24.99. In stock.", MODEL);

        assertThat(result.price()).isEqualByComparingTo(new BigDecimal("24.99"));
        assertThat(result.currency()).isEqualTo("USD");
        assertThat(result.availability()).isEqualTo(AvailabilityStatus.AVAILABLE);
        h.server().verify();
    }

    @Test
    void refusalResponse_translatedToMalformedOutput_notANullResult() {
        // A refusal is the one realistic way this provider returns an assistant message with
        // content=null: the reason sits in a separate `refusal` field, so the structured-output
        // converter has nothing to bind and hands back null rather than throwing. Verified against the
        // real OpenAiChatModel — before the null guard in LlmPriceExtractionService this returned null
        // and the orchestrator then dereferenced it (bare NullPointerException, no model attribution).
        // Ollama's format=json always returns some content, so this path only opened up with #121.
        Harness h = harness();
        h.server()
                .expect(requestTo(BASE_URL + "/chat/completions"))
                .andRespond(withSuccess(REFUSAL_RESPONSE, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> h.service().extractPriceFromText("Sale price $24.99. In stock.", MODEL))
                .isInstanceOfSatisfying(MalformedLlmOutputException.class, e -> {
                    // Attribution still lands, so the failure audit (#131) names the right model.
                    assertThat(e.getContext()).isNotNull();
                    assertThat(e.getContext().modelName()).isEqualTo(MODEL);
                    assertThat(e.getContext().promptVersion()).isEqualTo(LlmPriceExtractionService.PROMPT_VERSION);
                });
        h.server().verify();
    }
}
