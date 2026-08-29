package com.np.pricehunt.backend.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.np.pricehunt.backend.config.GroqChatOptionsProperties;
import com.np.pricehunt.backend.config.GroqExtractionLlmProvider;
import com.np.pricehunt.backend.config.OllamaChatOptionsProperties;
import com.np.pricehunt.backend.config.OllamaExtractionLlmProvider;
import com.np.pricehunt.backend.domain.AvailabilityStatus;
import com.np.pricehunt.backend.dto.PriceLlmResult;
import com.np.pricehunt.backend.exception.MalformedLlmOutputException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.ResponseFormat;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;

class LlmPriceExtractionServiceTest {

    private static final String MODEL = "openai/gpt-oss-20b";

    // The production Groq provider, driven with the same option values application.properties binds.
    private static final ExtractionLlmProvider PROVIDER =
            new GroqExtractionLlmProvider(new GroqChatOptionsProperties(0.0, "low"));

    // Concrete JsonProcessingException (the type is abstract) for exercising the cause-chain helper.
    private static class TestJsonException extends JsonProcessingException {
        TestJsonException(String msg) {
            super(msg);
        }
    }

    // --- PROMPT_VERSION (auto-derived prompt fingerprint) ---

    @Test
    void promptVersion_is16LowercaseHexChars() {
        // Derived in a static initializer, so a broken derivation is a class-init failure at startup
        // rather than a bad value. The other tests only compare it to itself; this pins the shape.
        assertThat(LlmPriceExtractionService.PROMPT_VERSION).hasSize(16).matches("[0-9a-f]{16}");
    }

    // --- isStructuredOutputParseFailure (cause-chain predicate) ---

    @Test
    void parseFailure_directJsonException_detected() {
        assertThat(LlmPriceExtractionService.isStructuredOutputParseFailure(new TestJsonException("bad")))
                .isTrue();
    }

    @Test
    void parseFailure_nestedJsonExceptionCause_detected() {
        Throwable wrapped = new RuntimeException("convert failed", new TestJsonException("bad"));
        assertThat(LlmPriceExtractionService.isStructuredOutputParseFailure(wrapped))
                .isTrue();
    }

    @Test
    void parseFailure_noJsonCause_notDetected() {
        Throwable transport = new RuntimeException("io", new ResourceAccessException("timeout"));
        assertThat(LlmPriceExtractionService.isStructuredOutputParseFailure(transport))
                .isFalse();
    }

    @Test
    void parseFailure_selfReferentialCause_terminates() {
        // A cause cycle must not loop forever. Build a throwable whose cause is itself.
        Throwable cyclic = new RuntimeException("loop") {
            @Override
            public synchronized Throwable getCause() {
                return this;
            }
        };
        assertThat(LlmPriceExtractionService.isStructuredOutputParseFailure(cyclic))
                .isFalse();
    }

    @Test
    void parseFailure_standaloneRestClientException_notDetected() {
        assertThat(LlmPriceExtractionService.isStructuredOutputParseFailure(new RestClientException("boom")))
                .isFalse();
    }

    @Test
    void parseFailure_restClientWrappingJson_notDetected() {
        // Jackson failing while decoding the provider's HTTP response is a transport failure, not bad output.
        // (JsonProcessingException extends IOException, so it fits ResourceAccessException's cause slot.)
        Throwable httpDecode = new ResourceAccessException("decode failed", new TestJsonException("bad"));
        assertThat(LlmPriceExtractionService.isStructuredOutputParseFailure(httpDecode))
                .isFalse();
    }

    @Test
    void parseFailure_restClientVetoesEvenAfterParseErrorSeen_orderIndependent() {
        // JsonProcessingException appears first in the chain, RestClientException deeper — the veto
        // must still win (this is the order-independence the full scan exists for).
        TestJsonException jsonEx = new TestJsonException("bad");
        jsonEx.initCause(new ResourceAccessException("transport underneath"));
        Throwable chain = new RuntimeException("outer", jsonEx);
        assertThat(LlmPriceExtractionService.isStructuredOutputParseFailure(chain))
                .isFalse();
    }

    // --- adapter translation: malformed output -> domain exception; transport -> propagate ---

    @Test
    void extractPriceFromText_malformedJson_translatedToMalformedLlmOutputException() {
        ChatModel chatModel = mock(ChatModel.class);
        ChatResponse malformed = new ChatResponse(List.of(new Generation(new AssistantMessage("definitely not json"))));
        when(chatModel.call(any(Prompt.class))).thenReturn(malformed);

        LlmPriceExtractionService service = new LlmPriceExtractionService(ChatClient.builder(chatModel), PROVIDER);

        // The translated exception carries the failure-only context (issue #131): the actual model +
        // the auto-derived prompt version, so the recorder attributes the failure precisely.
        assertThatThrownBy(() -> service.extractPriceFromText("$10 in stock", MODEL))
                .isInstanceOfSatisfying(MalformedLlmOutputException.class, e -> {
                    assertThat(e.getContext()).isNotNull();
                    assertThat(e.getContext().modelName()).isEqualTo(MODEL);
                    assertThat(e.getContext().promptVersion()).isEqualTo(LlmPriceExtractionService.PROMPT_VERSION);
                });
    }

    @Test
    void extractPriceFromText_transportFailure_propagatesUnchanged() {
        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.call(any(Prompt.class))).thenThrow(new ResourceAccessException("Request cancelled"));

        LlmPriceExtractionService service = new LlmPriceExtractionService(ChatClient.builder(chatModel), PROVIDER);

        assertThatThrownBy(() -> service.extractPriceFromText("$10 in stock", MODEL))
                .isInstanceOf(ResourceAccessException.class);
    }

    // --- availability deserialization defense (the LLM path's dedicated Jackson-2 mapper) ---

    @Test
    void extractPriceFromText_unknownAvailabilityToken_defaultsToUnknown() {
        // An availability value outside the enum must degrade to UNKNOWN (via @JsonEnumDefaultValue +
        // the mapper's READ_UNKNOWN_ENUM_VALUES_USING_DEFAULT_VALUE), not throw — defense for when
        // native structured output doesn't fully constrain a model.
        ChatModel chatModel = mock(ChatModel.class);
        ChatResponse response = new ChatResponse(List.of(new Generation(
                new AssistantMessage("{\"price\": 10.00, \"currency\": \"USD\", \"availability\": \"BOGUS\"}"))));
        when(chatModel.call(any(Prompt.class))).thenReturn(response);
        LlmPriceExtractionService service = new LlmPriceExtractionService(ChatClient.builder(chatModel), PROVIDER);

        PriceLlmResult result = service.extractPriceFromText("$10 widget", MODEL);

        assertThat(result.availability()).isEqualTo(AvailabilityStatus.UNKNOWN);
    }

    // --- native structured output: the schema must actually reach the provider's options ---

    @Test
    void extractPriceFromText_groqProvider_sendsStrictJsonSchemaInOptions() {
        // The load-bearing test for the ExtractionLlmProvider seam. Spring AI's ChatModelCallAdvisor
        // applies the generated schema ONLY when the per-call options are a StructuredOutputChatOptions;
        // with the portable ChatOptions builder it would silently fall back to prompt-appended format
        // instructions and nothing would fail. Asserting the options the ChatModel actually receives is
        // what keeps that regression out — and it pins the strict:true + closed-schema shape Groq
        // requires for constrained decoding.
        ChatModel chatModel = mock(ChatModel.class);
        ChatResponse ok = new ChatResponse(List.of(new Generation(
                new AssistantMessage("{\"price\": 10.00, \"currency\": \"USD\", \"availability\": \"AVAILABLE\"}"))));
        when(chatModel.call(any(Prompt.class))).thenReturn(ok);
        LlmPriceExtractionService service = new LlmPriceExtractionService(ChatClient.builder(chatModel), PROVIDER);

        service.extractPriceFromText("$10 in stock", MODEL);

        ArgumentCaptor<Prompt> captor = ArgumentCaptor.forClass(Prompt.class);
        verify(chatModel).call(captor.capture());
        assertThat(captor.getValue().getOptions()).isInstanceOfSatisfying(OpenAiChatOptions.class, options -> {
            assertThat(options.getModel()).isEqualTo(MODEL);
            ResponseFormat format = options.getResponseFormat();
            assertThat(format).isNotNull();
            assertThat(format.getType()).isEqualTo(ResponseFormat.Type.JSON_SCHEMA);
            assertThat(format.getJsonSchema()).isNotNull();
            // strict=true is what makes Groq constrain decoding token-by-token to the schema.
            assertThat(format.getJsonSchema().getStrict()).isTrue();
            // Groq's strict mode additionally requires a closed object with every field required.
            assertThat(format.getJsonSchema().getSchema())
                    .containsEntry("additionalProperties", false)
                    .hasEntrySatisfying("required", required -> {
                        List<String> names = ((List<?>) required)
                                .stream().map(String::valueOf).toList();
                        assertThat(names).containsExactlyInAnyOrder("price", "currency", "availability");
                    });
        });
    }

    @Test
    void extractPriceFromText_ollamaProvider_sendsSchemaInNativeFormat() {
        // Fallback-profile mirror: the Ollama options type must carry the schema too (format=json_schema),
        // so switching providers can't quietly drop native structured output on one side.
        ChatModel chatModel = mock(ChatModel.class);
        ChatResponse ok = new ChatResponse(List.of(new Generation(
                new AssistantMessage("{\"price\": 10.00, \"currency\": \"USD\", \"availability\": \"AVAILABLE\"}"))));
        when(chatModel.call(any(Prompt.class))).thenReturn(ok);
        ExtractionLlmProvider ollama =
                new OllamaExtractionLlmProvider(new OllamaChatOptionsProperties(0.0, "json", 4096));
        LlmPriceExtractionService service = new LlmPriceExtractionService(ChatClient.builder(chatModel), ollama);

        service.extractPriceFromText("$10 in stock", "qwen3:1.7b");

        ArgumentCaptor<Prompt> captor = ArgumentCaptor.forClass(Prompt.class);
        verify(chatModel).call(captor.capture());
        assertThat(captor.getValue().getOptions()).isInstanceOfSatisfying(OllamaChatOptions.class, options -> {
            assertThat(options.getModel()).isEqualTo("qwen3:1.7b");
            assertThat(options.getOutputSchema()).contains("availability");
        });
    }

    @Test
    void extractPriceFromText_synonymAvailabilityToken_mappedViaAlias() {
        // A common synonym ("IN_STOCK") resolves to AVAILABLE via @JsonAlias rather than UNKNOWN.
        ChatModel chatModel = mock(ChatModel.class);
        ChatResponse response = new ChatResponse(List.of(new Generation(
                new AssistantMessage("{\"price\": 10.00, \"currency\": \"USD\", \"availability\": \"IN_STOCK\"}"))));
        when(chatModel.call(any(Prompt.class))).thenReturn(response);
        LlmPriceExtractionService service = new LlmPriceExtractionService(ChatClient.builder(chatModel), PROVIDER);

        PriceLlmResult result = service.extractPriceFromText("$10 widget", MODEL);

        assertThat(result.availability()).isEqualTo(AvailabilityStatus.AVAILABLE);
    }
}
