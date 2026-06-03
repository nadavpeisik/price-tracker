package com.np.pricehunt.backend.service;

import com.np.pricehunt.backend.dto.PriceLlmResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class OllamaPriceExtractionService {

    private final ChatClient chatClient;

    public OllamaPriceExtractionService(ChatClient.Builder builder) {
        this.chatClient = builder.defaultSystem(
                        """
                        You are a specialized e-commerce data extraction engine.
                        Your output is deterministic and follows the provided schema exactly.

                        Examples:
                        - "In stock within 1 week. Add to cart. £100" → {"price": 100.00, "currency": "GBP", "available": false}
                        - "In stock within about 1 week. Add to basket. €299" → {"price": 299.00, "currency": "EUR", "available": false}
                        - "In Stock. Buy Now. $50" → {"price": 50.00, "currency": "USD", "available": true}
                        - "חסר במלאי. ₪200" → {"price": 200.00, "currency": "ILS", "available": false}
                        """)
                .build();
    }

    public PriceLlmResult extractPriceFromText(String text, String model) {
        log.debug("LLM input text ({} chars): {}", text.length(), text);
        long start = System.currentTimeMillis();

        // Per-call options only set `model`; Spring AI merges with bean-level defaults
        // (temperature, format=json, num-ctx) from spring.ai.ollama.chat.options.* in application.properties.
        PriceLlmResult result = chatClient
                .prompt()
                .options(OllamaChatOptions.builder().model(model).build())
                .user(u -> u.text(
                                """
                        /no_think

                        # TASK
                        Extract the PRIMARY current price from the product text below.

                        # RULES
                        1. Currency symbols and codes: $ → USD, € → EUR, £ → GBP, ₪ → ILS.
                        2. Ignore original/MSRP/crossed-out prices when a sale price is present.
                        3. Set available = false if ANY of these appears anywhere in the text:
                           - English: "within X days/weeks", "within about X week(s)", "ships in X days", "expected back in stock", "backordered", "pre-order", "out of stock".
                           - Hebrew: "חסר במלאי", "אזל מהמלאי", "הזמנה מראש".
                           - Any waiting/delay phrase before dispatch.
                           A buy/cart button (e.g. "Add to basket", "Add to cart") does NOT imply available = true if a delay phrase appears anywhere in the same text.
                           Set available = true only if the item ships immediately with no qualifier.

                        # DATA
                        {text}
                        """)
                        .param("text", text))
                .call()
                .entity(PriceLlmResult.class);

        long durationMs = System.currentTimeMillis() - start;
        log.info(
                "LLM extraction model={} inputChars={} durationMs={} result={}",
                model,
                text.length(),
                durationMs,
                result);
        return result;
    }
}
