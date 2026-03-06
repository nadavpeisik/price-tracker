package com.np.pricehunt.backend.service;

import com.np.pricehunt.backend.dto.PriceInfo;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;

@Service
public class OllamaPriceExtractionService implements PriceExtractionService {

    private final ChatClient chatClient;

    public OllamaPriceExtractionService(ChatClient.Builder builder) {
        this.chatClient = builder.build();
    }

    @Override
    public PriceInfo extractPrice(String htmlContent) {
        // We use a Structured Output prompt so the AI returns valid JSON
        return chatClient.prompt()
                .user(u -> u.text("""
                Extract the price details from this HTML snippet. 
                Return ONLY a JSON object with fields: price (number), currency (string), available (boolean).
                HTML: {html}
                """).param("html", htmlContent))
                .call()
                .entity(PriceInfo.class); // Spring AI automatically maps JSON to our Record!
    }
}
