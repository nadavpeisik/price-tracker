package com.np.pricehunt.backend.service;

import com.np.pricehunt.backend.dto.PriceLlmResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class OllamaPriceExtractionService {

    private final ChatClient chatClient;

    public OllamaPriceExtractionService(ChatClient.Builder builder) {
        this.chatClient = builder
                .defaultSystem("""
                        You are a specialized e-commerce data extraction engine.
                        Your output is deterministic and follows the provided schema exactly.
                        """)
                .build();
    }

    public PriceLlmResult extractPriceFromText(String text) {
        log.debug("LLM input text ({} chars): {}", text.length(), text);
        long start = System.currentTimeMillis();

        PriceLlmResult result = chatClient.prompt()
                .user(u -> u.text("""
                        # TASK
                        Extract the PRIMARY current price from the product text below.

                        # RULES
                        1. Currency symbols and codes: $ → USD, € → EUR, £ → GBP, ₪ → ILS.
                        2. Ignore original/MSRP/crossed-out prices when a sale price is present.
                        3. Set available = true ONLY if immediately available (e.g. "add to cart", "buy now", "in stock" with no time qualifier). Phrases like "in stock within X days/weeks" or "ships in X days" mean available = false.

                        # DATA
                        {text}
                        """).param("text", text))
                .call()
                .entity(PriceLlmResult.class);

        long durationMs = System.currentTimeMillis() - start;
        log.info("LLM extraction inputChars={} durationMs={} result={}",
                text.length(), durationMs, result);
        return result;
    }
}
