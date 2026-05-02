package com.np.pricehunt.backend.service;

import com.np.pricehunt.backend.dto.PriceInfo;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

@Service
public class OllamaPriceExtractionService {

    private final ChatClient chatClient;

    public OllamaPriceExtractionService(ChatClient.Builder builder) {
        this.chatClient = builder.build();
    }

    public PriceInfo extractPriceFromText(String text) {
        return chatClient.prompt()
                .user(u -> u.text("""
                        Role: Expert e-commerce data extractor.
                        Task: Identify the PRIMARY price for the product. Ignore original/crossed-out prices if a sale price is present.

                        Text:
                        {text}

                        Instructions:
                        1. Look for currency symbols ($, €, £) and ISO currency codes.
                        2. Identify if the item is in stock.
                        3. Return ONLY valid JSON, no explanation.

                        Response format:
                        {"price": number, "currency": "ISO 4217 code", "available": boolean}
                        """).param("text", text))
                .call()
                .entity(PriceInfo.class);
    }
}
