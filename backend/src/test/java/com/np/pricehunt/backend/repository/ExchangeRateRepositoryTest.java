package com.np.pricehunt.backend.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.np.pricehunt.backend.domain.ExchangeRate;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.test.context.ActiveProfiles;

/**
 * Covers the historical-FX finders added for the price-trend engine (#145).
 *
 * <p>The fixture deliberately has a gap (no rows on the 3rd) and an asymmetric USD/ILS calendar, so
 * "nearest earlier rate" and per-quote independence are exercised rather than assumed.
 */
@DataJpaTest
@ActiveProfiles("test")
class ExchangeRateRepositoryTest {

    private static final LocalDate D1 = LocalDate.of(2026, 3, 1);
    private static final LocalDate D2 = LocalDate.of(2026, 3, 2);
    private static final LocalDate D4 = LocalDate.of(2026, 3, 4);

    @Autowired
    private TestEntityManager em;

    @Autowired
    private ExchangeRateRepository repository;

    @BeforeEach
    void setUp() {
        em.persist(rate("USD", D1, "1.0800"));
        em.persist(rate("ILS", D1, "3.9500"));
        em.persist(rate("USD", D2, "1.0850"));
        // D2 has no ILS row, D3 nothing at all, D4 only ILS.
        em.persist(rate("ILS", D4, "4.0100"));
        em.flush();
    }

    @Test
    void anchor_returnsExactDateWhenPresent() {
        assertThat(repository
                        .findTopByQuoteAndAsOfLessThanEqualOrderByAsOfDesc("USD", D2)
                        .orElseThrow()
                        .getRate())
                .isEqualByComparingTo("1.0850");
    }

    @Test
    void anchor_fallsBackToNearestEarlierDate() {
        // ILS published on the 1st but not the 2nd — the 1st is still its valid rate.
        ExchangeRate anchor = repository
                .findTopByQuoteAndAsOfLessThanEqualOrderByAsOfDesc("ILS", D2)
                .orElseThrow();

        assertThat(anchor.getAsOf()).isEqualTo(D1);
        assertThat(anchor.getRate()).isEqualByComparingTo("3.9500");
    }

    @Test
    void anchor_isPerQuote_ignoringOtherCurrenciesRows() {
        // USD has nothing after the 2nd; the ILS row on the 4th must not be visible to USD.
        ExchangeRate anchor = repository
                .findTopByQuoteAndAsOfLessThanEqualOrderByAsOfDesc("USD", D4)
                .orElseThrow();

        assertThat(anchor.getAsOf()).isEqualTo(D2);
    }

    @Test
    void anchor_emptyWhenQuoteHasNoRateAtOrBeforeTheDate() {
        assertThat(repository.findTopByQuoteAndAsOfLessThanEqualOrderByAsOfDesc("USD", D1.minusDays(1)))
                .isEmpty();
        assertThat(repository.findTopByQuoteAndAsOfLessThanEqualOrderByAsOfDesc("GBP", D4))
                .isEmpty();
    }

    @Test
    void rangeFetch_returnsRequestedQuotesOnlyOrderedByDate() {
        List<ExchangeRate> results = repository.findByQuoteInAndAsOfBetweenOrderByAsOfAsc(List.of("USD"), D1, D4);

        assertThat(results).extracting(ExchangeRate::getQuote).containsOnly("USD");
        assertThat(results).extracting(ExchangeRate::getAsOf).containsExactly(D1, D2);
    }

    @Test
    void rangeFetch_boundsAreInclusiveAndExcludeOutsideDates() {
        List<ExchangeRate> results =
                repository.findByQuoteInAndAsOfBetweenOrderByAsOfAsc(List.of("USD", "ILS"), D2, D4);

        assertThat(results).extracting(ExchangeRate::getAsOf).containsExactly(D2, D4);
    }

    private static ExchangeRate rate(String quote, LocalDate asOf, String value) {
        return ExchangeRate.builder()
                .quote(quote)
                .asOf(asOf)
                .rate(new BigDecimal(value))
                .build();
    }
}
