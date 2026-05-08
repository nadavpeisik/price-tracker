package com.np.pricehunt.backend.service;

import com.np.pricehunt.backend.dto.PriceLlmResult;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

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
        return chatClient.prompt()
                .user(u -> u.text("""
                        # TASK
                        Extract the PRIMARY current price from the product text below.

                        # RULES
                        1. Currency symbols and codes: $ → USD, € → EUR, £ → GBP, ₪ → ILS.
                        2. Ignore original/MSRP/crossed-out prices when a sale price is present.
                        3. Set available = true ONLY if the text indicates "in stock", "add to cart", or equivalent.

                        # DATA
                        {text}
                        """).param("text", text))
                .call()
                .entity(PriceLlmResult.class);
    }
}
