package com.np.pricehunt.backend.domain;

public enum ExtractionSource {
    STRUCTURED,  // JSON-LD / Schema.org — no LLM called
    SNIPPET,     // CSS/meta selectors — LLM called on small snippet
    FULLTEXT     // Regex-filtered innerText — LLM called on pruned text
}
