package com.np.pricehunt.backend.service;

import org.springframework.ai.model.tool.StructuredOutputChatOptions;

/**
 * The per-provider seam for LLM price extraction (issue #121). {@link LlmPriceExtractionService}
 * owns the prompt, the schema and the failure translation — all provider-neutral — and delegates
 * only the two things that genuinely differ between Groq and Ollama: the concrete chat-options type
 * and how those options describe themselves for the config fingerprint.
 *
 * <p><b>Why this exists at all — the return type is load-bearing.</b> The obvious "portable" refactor
 * (per-call {@code ChatOptions.builder().model(m).build()}) compiles and runs, but
 * <em>silently disables native structured output</em>: Spring AI's {@code ChatModelCallAdvisor}
 * applies the generated JSON schema only when the runtime options are an
 * {@code instanceof StructuredOutputChatOptions}, and the portable {@code DefaultChatOptions} is not
 * one. It would fall back to prompt-appended format instructions with no schema enforcement, and
 * nothing would fail loudly. Declaring {@link StructuredOutputChatOptions} as the return type here
 * makes that requirement a compile-time contract instead of a comment an implementer can miss.
 */
public interface ExtractionLlmProvider {

    /**
     * Short provider id ({@code "groq"} / {@code "ollama"}), used in the startup log and as part of
     * the extraction-config fingerprint so audit rows from different providers never collide.
     */
    String name();

    /**
     * Per-call options carrying just the model; every other generation option (temperature,
     * reasoning-effort, num-ctx, format) comes from the bean-level defaults Spring AI merges in.
     */
    StructuredOutputChatOptions optionsForModel(String model);

    /**
     * The provider's generation options rendered as a stable string for
     * {@link ExtractionConfigFingerprint}. Must include every option that can change model output,
     * and must not include the model (tracked per-row as {@code model_name}).
     */
    String optionsDescriptor();
}
