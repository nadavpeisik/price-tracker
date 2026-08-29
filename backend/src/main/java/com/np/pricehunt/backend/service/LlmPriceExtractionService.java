package com.np.pricehunt.backend.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.np.pricehunt.backend.dto.PriceLlmResult;
import com.np.pricehunt.backend.exception.MalformedLlmOutputException;
import com.np.pricehunt.backend.util.Hashing;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.AdvisorParams;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;

/**
 * Extracts a price from page text with an LLM, for the SNIPPET and FULLTEXT tiers of the extraction
 * waterfall. Provider-neutral: the prompt, the structured-output schema and the failure translation
 * live here, while the concrete chat-options type comes from the injected
 * {@link ExtractionLlmProvider} (Groq by default, Ollama under the {@code ollama} profile — #121).
 */
@Slf4j
@Service
public class LlmPriceExtractionService {

    // Upper bound on the exception cause-chain walk in isStructuredOutputParseFailure — guarantees
    // termination even on a (pathological) cyclic chain. Real chains are only a few links deep.
    private static final int MAX_CAUSE_DEPTH = 50;

    // Whether each call sends the generated JSON schema to the provider as a native grammar constraint
    // (Ollama: format=json_schema; Groq/OpenAI: response_format=json_schema with strict=true),
    // constraining the enum to its members at decode time. SINGLE source of truth: both the advisor
    // below and ExtractionConfigFingerprint read this, so extraction_config_hash (#131) can never drift
    // from what extractPriceFromText actually sends.
    public static final boolean NATIVE_STRUCTURED_OUTPUT = true;

    // The extraction prompt, split into the system preamble and the per-call user template. Extracted
    // to constants (issue #131) so PROMPT_VERSION can be derived from them — moving the text does NOT
    // change it (text blocks strip to the same content). EDITING either string is a prompt change: run
    // scripts/run-prompt-regression.sh (CLAUDE.md hard rule); PROMPT_VERSION then changes
    // automatically, so a replay corpus never conflates two different prompts.
    static final String SYSTEM_PROMPT =
            """
            You are a specialized e-commerce data extraction engine.
            Your output is deterministic and follows the provided schema exactly.
            availability is exactly one of: AVAILABLE, UNAVAILABLE, UNKNOWN.

            Availability examples:
            - "In Stock. Buy Now. $50" → {"price": 50.00, "currency": "USD", "availability": "AVAILABLE"}
            - "Only 1 left in stock - order soon. $50" → {"price": 50.00, "currency": "USD", "availability": "AVAILABLE"}
            - "Pre-order now. Releases next month. $60" → {"price": 60.00, "currency": "USD", "availability": "AVAILABLE"}
            - "Out of stock. $99" → {"price": 99.00, "currency": "USD", "availability": "UNAVAILABLE"}
            - "This product has been discontinued. $80" → {"price": 80.00, "currency": "USD", "availability": "UNAVAILABLE"}
            - "In stock within 1 week. £100" → {"price": 100.00, "currency": "GBP", "availability": "UNAVAILABLE"}
            - "חסר במלאי. ₪200" → {"price": 200.00, "currency": "ILS", "availability": "UNAVAILABLE"}
            - "$50" (price only, no stock wording) → {"price": 50.00, "currency": "USD", "availability": "UNKNOWN"}
            - "Usually ships within 2 to 3 weeks. $30" → {"price": 30.00, "currency": "USD", "availability": "UNKNOWN"}
            """;

    static final String USER_PROMPT_TEMPLATE =
            """
            /no_think

            # TASK
            Extract the PRIMARY current price from the product text below.

            # RULES
            1. Currency symbols and codes: $ → USD, € → EUR, £ → GBP, ₪ → ILS.
            2. Ignore original/MSRP/crossed-out prices when a sale price is present.
            3. AVAILABILITY — output exactly one of AVAILABLE, UNAVAILABLE, UNKNOWN. Decide in this order:
               STEP 1 (UNAVAILABLE). Set UNAVAILABLE if the item cannot be obtained NOW:
                 - explicit out-of-stock wording: "out of stock", "sold out", "currently unavailable",
                   "temporarily unavailable", "not available", "no longer available", "discontinued";
                 - an out-of-stock waitlist CTA: "notify me when available", "email me when available"
                   (UNLESS paired with a pre-order/back-order signal — see STEP 2);
                 - a FUTURE-stock / restock ETA — the in-stock state is in the future, so it is NOT in
                   stock now: "in stock within N days/weeks", "back in stock in N", "available in N weeks".
                 Hebrew: "חסר במלאי", "אזל מהמלאי", "לא במלאי".
               STEP 2 (AVAILABLE). Otherwise set AVAILABLE on a CLEAR purchasable-now or orderable signal:
                 - in stock now: "in stock", "add to cart", "buy now";
                 - low quantity / urgency: "only N left in stock", "order soon", "selling fast",
                   "low stock", "limited stock", "while supplies last";
                 - orderable but not yet shipped: "pre-order", "preorder", "backordered",
                   "available for pre-order" (Hebrew: "הזמנה מראש") — you CAN place an order now → AVAILABLE.
                 A positive orderability signal WINS over a generic out-of-stock phrase
                 ("Out of stock — pre-orders accepted" → AVAILABLE). A present-tense "In stock." is
                 AVAILABLE even when a separate SHIPPING window follows ("In stock. Ships in 2-3 weeks")
                 — that differs from "in stock WITHIN N" (STEP 1), where the stock itself is in the future.
               STEP 3 (UNKNOWN). Set UNKNOWN when stock status is absent or only a fulfillment time is given:
                 - a price but NO availability wording at all;
                 - only a shipping/dispatch window with NO stock statement: "ships in 1-2 business days",
                   "usually ships within 2-3 weeks" — fulfillment timing is not a stock claim; do not guess.

            # DATA
            {text}
            """;

    // Auto-derived prompt fingerprint: changes iff the prompt text changes — exactly what replay
    // grouping needs, with no "remember to bump it" step. Length-framed so text can't silently shift
    // across the system/user boundary without changing the hash; 16 hex chars (64 bits) removes any
    // collision doubt. It fingerprints the PROMPT TEXT only — model + options are tracked separately
    // (model_name); a schema/options change is not reflected here.
    public static final String PROMPT_VERSION = Hashing.sha256HexRequired("system:" + SYSTEM_PROMPT.length()
                    + ":" + SYSTEM_PROMPT + "\nuser:" + USER_PROMPT_TEMPLATE.length() + ":"
                    + USER_PROMPT_TEMPLATE)
            .substring(0, 16);

    private final ChatClient chatClient;

    // Built with a dedicated Jackson 2 ObjectMapper configured specifically for LLM output parsing:
    // unrecognized availability tokens default to UNKNOWN, and matching is case-insensitive.
    // Replaces injecting the global ObjectMapper to avoid ambiguity in the hybrid Jackson 2 + Jackson 3 environment.
    private final BeanOutputConverter<PriceLlmResult> outputConverter;

    // Supplies the provider-specific chat-options type. See ExtractionLlmProvider for why this can't
    // be the portable ChatOptions builder.
    private final ExtractionLlmProvider provider;

    public LlmPriceExtractionService(ChatClient.Builder builder, ExtractionLlmProvider provider) {
        ObjectMapper mapper = com.fasterxml.jackson.databind.json.JsonMapper.builder()
                .enable(com.fasterxml.jackson.databind.MapperFeature.ACCEPT_CASE_INSENSITIVE_ENUMS)
                .enable(
                        com.fasterxml.jackson.databind.DeserializationFeature
                                .READ_UNKNOWN_ENUM_VALUES_USING_DEFAULT_VALUE)
                .build();
        this.outputConverter = new BeanOutputConverter<>(PriceLlmResult.class, mapper);
        this.chatClient = builder.defaultSystem(SYSTEM_PROMPT).build();
        this.provider = provider;
        // Log the provider + prompt fingerprint at startup so any stored prompt_version (issue #131)
        // can be tied back to a build/commit via the deploy logs.
        log.info("LLM price-extraction provider={} prompt_version={}", provider.name(), PROMPT_VERSION);
    }

    public PriceLlmResult extractPriceFromText(String text, String model) {
        log.debug("LLM input text ({} chars): {}", text.length(), text);
        long start = System.currentTimeMillis();

        // Per-call options only set `model`; Spring AI merges with the bean-level defaults for the
        // active provider (Groq: temperature + reasoning-effort; Ollama: temperature, format=json,
        // num-ctx) from spring.ai.{openai,ollama}.chat.options.* in the profile's properties.
        PriceLlmResult result;
        try {
            var spec = chatClient.prompt();
            if (NATIVE_STRUCTURED_OUTPUT) {
                // Send the generated JSON schema to the provider as a native grammar constraint rather
                // than only a prompt instruction — the enum is constrained to its 3 members at decode time.
                spec = spec.advisors(AdvisorParams.ENABLE_NATIVE_STRUCTURED_OUTPUT);
            }
            result = spec.options(provider.optionsForModel(model))
                    .user(u -> u.text(USER_PROMPT_TEMPLATE).param("text", text))
                    .call()
                    .entity(outputConverter);
        } catch (RuntimeException e) {
            // The structured-output converter throws a plain RuntimeException wrapping a Jackson
            // JsonProcessingException when the model's output can't be parsed — the only signal, as
            // no dedicated type exists. Translate that; transport/HTTP/bug exceptions propagate.
            if (isStructuredOutputParseFailure(e)) {
                log.warn("LLM model={} returned unparseable output: {}", model, e.getMessage());
                throw new MalformedLlmOutputException(model, PROMPT_VERSION, e);
            }
            throw e;
        }

        // Null = the provider returned no content to bind. On Groq/OpenAI that is a REFUSAL (content=null,
        // reason in a separate `refusal` field); the converter returns null rather than throwing, so
        // without this the orchestrator would NPE with no model attribution. (Ollama's format=json
        // always returns some content — unreachable before #121.) Treat it as malformed output so
        // 502, audit attribution and SNIPPET→heavy-model escalation all behave as for bad JSON.
        if (result == null) {
            log.warn("LLM model={} returned no parseable content (refusal or empty message)", model);
            throw new MalformedLlmOutputException(model, PROMPT_VERSION, null);
        }

        long durationMs = System.currentTimeMillis() - start;
        log.info(
                "LLM extraction model={} inputChars={} durationMs={} result={}",
                model,
                text.length(),
                durationMs,
                result);
        return result;
    }

    // A genuine structured-output parse failure carries a Jackson JsonProcessingException in its cause
    // chain AND no RestClientException. The full, order-independent scan matters: Jackson can also fail
    // while decoding the provider's HTTP response, surfacing as a RestClientException wrapping a
    // JsonProcessingException — a transport/protocol failure that must propagate, not escalate to the
    // heavy model. A RestClientException anywhere therefore vetoes, even after a parse error is seen.
    // Provider-agnostic: both the Ollama and OpenAI/Groq clients are RestClient-based, so raw transport
    // failures surface the same way. Spring AI's Transient/NonTransientAiException (thrown by the retry
    // error handler for HTTP 4xx/5xx) carries no JsonProcessingException, so it returns false here and
    // propagates untouched — exactly the intent.
    // MAX_CAUSE_DEPTH bounds the walk so a cyclic chain can't loop. Package-private for direct testing.
    static boolean isStructuredOutputParseFailure(Throwable t) {
        boolean sawParseError = false;
        Throwable cause = t;
        for (int depth = 0; cause != null && depth < MAX_CAUSE_DEPTH; depth++) {
            if (cause instanceof RestClientException) {
                return false;
            }
            if (cause instanceof JsonProcessingException) {
                sawParseError = true;
            }
            cause = cause.getCause();
        }
        return sawParseError;
    }
}
