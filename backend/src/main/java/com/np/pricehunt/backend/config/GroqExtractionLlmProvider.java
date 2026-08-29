package com.np.pricehunt.backend.config;

import com.np.pricehunt.backend.service.ExtractionLlmProvider;
import org.springframework.ai.model.tool.StructuredOutputChatOptions;
import org.springframework.ai.openai.OpenAiChatOptions;

/**
 * Groq half of the extraction provider seam (issue #121), registered as a bean by
 * {@link GroqLlmConfig}. Groq is reached through Spring AI's OpenAI client, so the per-call options
 * type is {@link OpenAiChatOptions} — which implements {@link StructuredOutputChatOptions} and
 * therefore carries the strict JSON schema onto the wire.
 */
public record GroqExtractionLlmProvider(GroqChatOptionsProperties options) implements ExtractionLlmProvider {

    @Override
    public String name() {
        return "groq";
    }

    @Override
    public StructuredOutputChatOptions optionsForModel(String model) {
        // Model only — temperature and reasoning-effort come from the bean-level defaults that
        // Spring AI merges into every call (spring.ai.openai.chat.options.*).
        return OpenAiChatOptions.builder().model(model).build();
    }

    @Override
    public String optionsDescriptor() {
        return "temperature:" + options.temperature() + "|reasoningEffort:" + options.reasoningEffort();
    }
}
