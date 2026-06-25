package com.np.pricehunt.backend.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.np.pricehunt.backend.domain.AvailabilityStatus;
import com.np.pricehunt.backend.dto.PriceLlmResult;
import com.np.pricehunt.backend.exception.MalformedLlmOutputException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;

class OllamaPriceExtractionServiceTest {

    private static final String MODEL = "qwen3:1.7b";

    // Concrete JsonProcessingException (the type is abstract) for exercising the cause-chain helper.
    private static class TestJsonException extends JsonProcessingException {
        TestJsonException(String msg) {
            super(msg);
        }
    }

    // --- isStructuredOutputParseFailure (cause-chain predicate) ---

    @Test
    void parseFailure_directJsonException_detected() {
        assertThat(OllamaPriceExtractionService.isStructuredOutputParseFailure(new TestJsonException("bad")))
                .isTrue();
    }

    @Test
    void parseFailure_nestedJsonExceptionCause_detected() {
        Throwable wrapped = new RuntimeException("convert failed", new TestJsonException("bad"));
        assertThat(OllamaPriceExtractionService.isStructuredOutputParseFailure(wrapped))
                .isTrue();
    }

    @Test
    void parseFailure_noJsonCause_notDetected() {
        Throwable transport = new RuntimeException("io", new ResourceAccessException("timeout"));
        assertThat(OllamaPriceExtractionService.isStructuredOutputParseFailure(transport))
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
        assertThat(OllamaPriceExtractionService.isStructuredOutputParseFailure(cyclic))
                .isFalse();
    }

    @Test
    void parseFailure_standaloneRestClientException_notDetected() {
        assertThat(OllamaPriceExtractionService.isStructuredOutputParseFailure(new RestClientException("boom")))
                .isFalse();
    }

    @Test
    void parseFailure_restClientWrappingJson_notDetected() {
        // Jackson failing while decoding Ollama's HTTP response is a transport failure, not bad output.
        // (JsonProcessingException extends IOException, so it fits ResourceAccessException's cause slot.)
        Throwable httpDecode = new ResourceAccessException("decode failed", new TestJsonException("bad"));
        assertThat(OllamaPriceExtractionService.isStructuredOutputParseFailure(httpDecode))
                .isFalse();
    }

    @Test
    void parseFailure_restClientVetoesEvenAfterParseErrorSeen_orderIndependent() {
        // JsonProcessingException appears first in the chain, RestClientException deeper — the veto
        // must still win (this is the order-independence the full scan exists for).
        TestJsonException jsonEx = new TestJsonException("bad");
        jsonEx.initCause(new ResourceAccessException("transport underneath"));
        Throwable chain = new RuntimeException("outer", jsonEx);
        assertThat(OllamaPriceExtractionService.isStructuredOutputParseFailure(chain))
                .isFalse();
    }

    // --- adapter translation: malformed output -> domain exception; transport -> propagate ---

    @Test
    void extractPriceFromText_malformedJson_translatedToMalformedLlmOutputException() {
        ChatModel chatModel = mock(ChatModel.class);
        ChatResponse malformed = new ChatResponse(List.of(new Generation(new AssistantMessage("definitely not json"))));
        when(chatModel.call(any(Prompt.class))).thenReturn(malformed);

        OllamaPriceExtractionService service = new OllamaPriceExtractionService(ChatClient.builder(chatModel));

        assertThatThrownBy(() -> service.extractPriceFromText("$10 in stock", MODEL))
                .isInstanceOf(MalformedLlmOutputException.class);
    }

    @Test
    void extractPriceFromText_transportFailure_propagatesUnchanged() {
        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.call(any(Prompt.class))).thenThrow(new ResourceAccessException("Request cancelled"));

        OllamaPriceExtractionService service = new OllamaPriceExtractionService(ChatClient.builder(chatModel));

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
        OllamaPriceExtractionService service = new OllamaPriceExtractionService(ChatClient.builder(chatModel));

        PriceLlmResult result = service.extractPriceFromText("$10 widget", MODEL);

        assertThat(result.availability()).isEqualTo(AvailabilityStatus.UNKNOWN);
    }

    @Test
    void extractPriceFromText_synonymAvailabilityToken_mappedViaAlias() {
        // A common synonym ("IN_STOCK") resolves to AVAILABLE via @JsonAlias rather than UNKNOWN.
        ChatModel chatModel = mock(ChatModel.class);
        ChatResponse response = new ChatResponse(List.of(new Generation(
                new AssistantMessage("{\"price\": 10.00, \"currency\": \"USD\", \"availability\": \"IN_STOCK\"}"))));
        when(chatModel.call(any(Prompt.class))).thenReturn(response);
        OllamaPriceExtractionService service = new OllamaPriceExtractionService(ChatClient.builder(chatModel));

        PriceLlmResult result = service.extractPriceFromText("$10 widget", MODEL);

        assertThat(result.availability()).isEqualTo(AvailabilityStatus.AVAILABLE);
    }
}
