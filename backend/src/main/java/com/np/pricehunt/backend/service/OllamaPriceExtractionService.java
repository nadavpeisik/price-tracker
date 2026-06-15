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

                        Availability examples (note: a time window like "within N weeks" means false even if "in stock" appears):
                        - "In Stock. Buy Now. $50" → {"price": 50.00, "currency": "USD", "available": true}
                        - "Only 1 left in stock - order soon. $50" → {"price": 50.00, "currency": "USD", "available": true}
                        - "Only 3 left in stock. ₪200" → {"price": 200.00, "currency": "ILS", "available": true}
                        - "In stock. Selling fast. $75" → {"price": 75.00, "currency": "USD", "available": true}
                        - "In stock within 1 week. Add to cart. £100" → {"price": 100.00, "currency": "GBP", "available": false}
                        - "Usually ships within 2 to 3 weeks. $30" → {"price": 30.00, "currency": "USD", "available": false}
                        - "Out of stock. $99" → {"price": 99.00, "currency": "USD", "available": false}
                        - "Pre-order now. Releases next month. $60" → {"price": 60.00, "currency": "USD", "available": false}
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
                        3. AVAILABILITY — decide in this exact order:
                           STEP 1. Look for a DISQUALIFIER. Set available = false and STOP if the text contains ANY of:
                             - Unavailable or not-yet-released: "out of stock", "sold out", "currently unavailable",
                               "temporarily unavailable", "not available", "no longer available", "discontinued",
                               "expected back in stock", "back soon", "notify me when available", "backordered",
                               "pre-order", "preorder", "coming soon", or a future release/availability date
                               (e.g. "releases next month", "available from <date>"). A pre-order or future
                               release is NOT available now.
                             - A future-delivery TIME WINDOW: "within N day(s)/week(s)", "ships in N days/weeks",
                               "available in N weeks", "usually ships within N weeks". This makes it false EVEN IF "in stock" also appears.
                             - Hebrew: "חסר במלאי", "אזל מהמלאי", "לא במלאי", "הזמנה מראש".
                           STEP 2. Otherwise set available = true. Low quantity and urgency do NOT make it false — phrases like
                             "only N left in stock", "order soon", "selling fast", "low stock", "limited stock", "while supplies last"
                             all confirm the item is in stock and purchasable now (available = true).

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
