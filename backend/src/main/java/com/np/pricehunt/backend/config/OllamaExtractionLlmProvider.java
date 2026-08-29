package com.np.pricehunt.backend.config;

import com.np.pricehunt.backend.service.ExtractionLlmProvider;
import org.springframework.ai.model.tool.StructuredOutputChatOptions;
import org.springframework.ai.ollama.api.OllamaChatOptions;

/**
 * Ollama half of the extraction provider seam (issue #121), registered as a bean by
 * {@link OllamaClientConfig} under the {@code ollama} profile. {@link OllamaChatOptions} implements
 * {@link StructuredOutputChatOptions}, so the generated schema is sent as a native grammar
 * constraint ({@code format=json_schema}) rather than a prompt instruction.
 */
public record OllamaExtractionLlmProvider(OllamaChatOptionsProperties options) implements ExtractionLlmProvider {

    @Override
    public String name() {
        return "ollama";
    }

    @Override
    public StructuredOutputChatOptions optionsForModel(String model) {
        // Model only — temperature, format and num-ctx come from the bean-level defaults that
        // Spring AI merges into every call (spring.ai.ollama.chat.options.*).
        return OllamaChatOptions.builder().model(model).build();
    }

    @Override
    public String optionsDescriptor() {
        return "temperature:" + options.temperature() + "|numCtx:" + options.numCtx() + "|format:" + options.format();
    }
}
