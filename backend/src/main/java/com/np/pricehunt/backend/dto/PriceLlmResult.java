package com.np.pricehunt.backend.dto;

import java.math.BigDecimal;

// Narrow DTO for Spring AI structured output — only the 3 fields the LLM produces.
// Keeping extractionSource out of this type prevents BeanOutputConverter from injecting it into the LLM prompt schema.
public record PriceLlmResult(BigDecimal price, String currency, boolean available) {}
