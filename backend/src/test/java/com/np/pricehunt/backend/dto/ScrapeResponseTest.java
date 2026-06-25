package com.np.pricehunt.backend.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.np.pricehunt.backend.domain.AvailabilityStatus;
import com.np.pricehunt.backend.domain.ExtractionSource;
import org.junit.jupiter.api.Test;

// Verifies the scraper -> backend JSON contract for the nested shopNameProposal: it deserializes via
// the canonical record constructor (the 5-arg convenience ctor must not divert Jackson), and an
// absent object maps to null. The scraper emits LOWERCASE enum values ("structured"); the app sets
// spring.jackson.mapper.accept-case-insensitive-enums=true, so this mapper mirrors that — testing the
// real lowercase contract rather than uppercase against a plain mapper.
class ScrapeResponseTest {

    private final ObjectMapper mapper = JsonMapper.builder()
            .enable(MapperFeature.ACCEPT_CASE_INSENSITIVE_ENUMS)
            .build();

    @Test
    void deserializesNestedShopNameProposal() throws Exception {
        String json =
                """
                {"extractionSource":"structured",
                 "priceData":{"price":9.99,"currency":"USD","availability":"available"},
                 "shopNameProposal":{"name":"Musikhaus Thomann","strong":true}}
                """;

        ScrapeResponse resp = mapper.readValue(json, ScrapeResponse.class);

        assertThat(resp.extractionSource()).isEqualTo(ExtractionSource.STRUCTURED);
        assertThat(resp.shopNameProposal()).isNotNull();
        assertThat(resp.shopNameProposal().name()).isEqualTo("Musikhaus Thomann");
        assertThat(resp.shopNameProposal().strong()).isTrue();
        assertThat(resp.priceData().price()).isEqualByComparingTo("9.99");
        // lowercase wire token maps to the enum via accept-case-insensitive-enums
        assertThat(resp.priceData().availability()).isEqualTo(AvailabilityStatus.AVAILABLE);
    }

    @Test
    void absentShopNameProposalIsNull() throws Exception {
        String json = "{\"extractionSource\":\"snippet\",\"snippet\":\"$9.99\"}";

        ScrapeResponse resp = mapper.readValue(json, ScrapeResponse.class);

        assertThat(resp.shopNameProposal()).isNull();
    }
}
