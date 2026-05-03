package com.np.pricehunt.backend.service;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;

@Service
public class OllamaPriceExtractionService {

    // Narrow DTO used only for LLM structured output — no extractionSource field so Spring AI schema stays clean
    record PriceLlmResult(BigDecimal price, String currency, boolean available) {}

    private final ChatClient chatClient;

    public OllamaPriceExtractionService(ChatClient.Builder builder) {
        this.chatClient = builder.build();
    }

    public PriceLlmResult extractPriceFromText(String text) {
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
                .entity(PriceLlmResult.class);
    }
}
