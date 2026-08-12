package com.np.pricehunt.backend.service.fx;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.np.pricehunt.backend.domain.ExchangeRate;
import com.np.pricehunt.backend.repository.ExchangeRateRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Covers the assembly step the repository and calculator tests can't see: combining single-row
 * per-quote anchors with multi-row range results into one {@link HistoricalRateWindow}.
 */
@ExtendWith(MockitoExtension.class)
class HistoricalRateWindowLoaderTest {

    private static final LocalDate START = LocalDate.of(2026, 3, 10);
    private static final LocalDate END = LocalDate.of(2026, 3, 20);

    @Mock
    private ExchangeRateRepository repository;

    private HistoricalRateWindowLoader loader;

    @BeforeEach
    void setUp() {
        // Construct here, not as a field initializer: @Mock fields are injected after field init.
        loader = new HistoricalRateWindowLoader(repository);
    }

    @Test
    void load_groupsRangeRowsPerQuote() {
        when(repository.findTopByQuoteAndAsOfLessThanEqualOrderByAsOfDesc(anyString(), any()))
                .thenReturn(Optional.empty());
        when(repository.findByQuoteInAndAsOfBetweenOrderByAsOfAsc(any(), eq(START), eq(END)))
                .thenReturn(List.of(
                        rate("USD", START, "1.08"),
                        rate("ILS", START, "3.95"),
                        rate("USD", END, "1.10"),
                        rate("ILS", END, "4.01")));

        HistoricalRateWindow window = loader.load(START, END, Set.of("USD", "ILS"));

        assertThat(window.rateOnOrBefore("USD", END).orElseThrow().rate()).isEqualByComparingTo("1.10");
        assertThat(window.rateOnOrBefore("ILS", START).orElseThrow().rate()).isEqualByComparingTo("3.95");
    }

    @Test
    void load_includesAnchorSoDaysBeforeTheFirstInRangeRateStillConvert() {
        when(repository.findTopByQuoteAndAsOfLessThanEqualOrderByAsOfDesc("USD", START))
                .thenReturn(Optional.of(rate("USD", START.minusDays(4), "1.07")));
        when(repository.findByQuoteInAndAsOfBetweenOrderByAsOfAsc(any(), eq(START), eq(END)))
                .thenReturn(List.of(rate("USD", END, "1.10")));

        HistoricalRateWindow window = loader.load(START, END, Set.of("USD"));

        HistoricalRateWindow.DatedRate atStart =
                window.rateOnOrBefore("USD", START).orElseThrow();
        assertThat(atStart.asOf()).isEqualTo(START.minusDays(4));
        assertThat(atStart.rate()).isEqualByComparingTo("1.07");
    }

    @Test
    void load_withoutAnchor_stillFetchesTheRange() {
        when(repository.findTopByQuoteAndAsOfLessThanEqualOrderByAsOfDesc("USD", START))
                .thenReturn(Optional.empty());
        when(repository.findByQuoteInAndAsOfBetweenOrderByAsOfAsc(any(), eq(START), eq(END)))
                .thenReturn(List.of(rate("USD", START.plusDays(2), "1.09")));

        HistoricalRateWindow window = loader.load(START, END, Set.of("USD"));

        assertThat(window.rateOnOrBefore("USD", START)).isEmpty();
        assertThat(window.rateOnOrBefore("USD", START.plusDays(3)).orElseThrow().rate())
                .isEqualByComparingTo("1.09");
    }

    @Test
    void load_partialDateDoesNotShadowAnotherQuotesOlderRate() {
        when(repository.findTopByQuoteAndAsOfLessThanEqualOrderByAsOfDesc(anyString(), any()))
                .thenReturn(Optional.empty());
        // The later date carries USD only; ILS must keep resolving to its own earlier rate.
        when(repository.findByQuoteInAndAsOfBetweenOrderByAsOfAsc(any(), eq(START), eq(END)))
                .thenReturn(List.of(rate("USD", START, "1.08"), rate("ILS", START, "3.95"), rate("USD", END, "1.10")));

        HistoricalRateWindow window = loader.load(START, END, Set.of("USD", "ILS"));

        assertThat(window.rateOnOrBefore("ILS", END).orElseThrow().asOf()).isEqualTo(START);
        assertThat(window.rateOnOrBefore("USD", END).orElseThrow().asOf()).isEqualTo(END);
    }

    @Test
    void load_requestsOnlyTheNeededQuotesWithinTheBoundedRange() {
        when(repository.findTopByQuoteAndAsOfLessThanEqualOrderByAsOfDesc(anyString(), any()))
                .thenReturn(Optional.empty());
        when(repository.findByQuoteInAndAsOfBetweenOrderByAsOfAsc(any(), any(), any()))
                .thenReturn(List.of());

        loader.load(START, END, Set.of("USD"));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<java.util.Collection<String>> quotes = ArgumentCaptor.forClass(java.util.Collection.class);
        verify(repository).findByQuoteInAndAsOfBetweenOrderByAsOfAsc(quotes.capture(), eq(START), eq(END));
        assertThat(quotes.getValue()).containsExactly("USD");
    }

    @Test
    void load_dropsEurBecauseItIsTheImplicitBase() {
        when(repository.findTopByQuoteAndAsOfLessThanEqualOrderByAsOfDesc("USD", START))
                .thenReturn(Optional.empty());
        when(repository.findByQuoteInAndAsOfBetweenOrderByAsOfAsc(any(), any(), any()))
                .thenReturn(List.of());

        loader.load(START, END, Set.of("EUR", "USD"));

        verify(repository, never()).findTopByQuoteAndAsOfLessThanEqualOrderByAsOfDesc(eq("EUR"), any());
    }

    @Test
    void load_withOnlyEurOrEmptyQuotes_shortCircuitsWithoutQuerying() {
        assertThat(loader.load(START, END, Set.of("EUR")).isEmpty()).isTrue();
        assertThat(loader.load(START, END, Set.of()).isEmpty()).isTrue();

        verifyNoInteractions(repository);
    }

    @Test
    void load_invertedRange_returnsEmptyWithoutQuerying() {
        assertThat(loader.load(END, START, Set.of("USD")).isEmpty()).isTrue();

        verifyNoInteractions(repository);
    }

    private static ExchangeRate rate(String quote, LocalDate asOf, String value) {
        return ExchangeRate.builder()
                .quote(quote)
                .asOf(asOf)
                .rate(new BigDecimal(value))
                .build();
    }
}
