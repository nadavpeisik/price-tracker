package com.np.pricehunt.backend.service.fx;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.np.pricehunt.backend.domain.ExchangeRate;
import com.np.pricehunt.backend.repository.ExchangeRateRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ExchangeRateServiceTest {

    private static final LocalDate TODAY = LocalDate.parse("2026-06-04");

    @Mock
    private ExchangeRateRepository repository;

    @Mock
    private FrankfurterRateProvider provider;

    private Clock clock;
    private ExchangeRateService service;

    @BeforeEach
    void setUp() {
        clock = Clock.fixed(TODAY.atStartOfDay(ZoneOffset.UTC).toInstant(), ZoneOffset.UTC);
        service = new ExchangeRateService(repository, provider, clock);
    }

    @Test
    void refresh_success_returnsSnapshotPersistsNewRatesAndUpdatesCache() {
        RateSnapshot fresh = snapshot(TODAY, Map.of("USD", "1.07", "ILS", "3.95"));
        when(provider.fetchLatest()).thenReturn(fresh);
        when(repository.findByAsOf(TODAY)).thenReturn(List.of());

        Optional<RateSnapshot> result = service.refresh();

        assertThat(result).contains(fresh);
        assertThat(service.currentSnapshot()).contains(fresh);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ExchangeRate>> captor = ArgumentCaptor.forClass(List.class);
        verify(repository).saveAll(captor.capture());
        assertThat(captor.getValue()).extracting(ExchangeRate::getQuote).containsExactlyInAnyOrder("USD", "ILS");
    }

    @Test
    void refresh_failure_returnsEmptyAndDoesNotMutateCache() {
        when(provider.fetchLatest()).thenThrow(new RuntimeException("network down"));

        Optional<RateSnapshot> result = service.refresh();

        assertThat(result).isEmpty();
        assertThat(service.currentSnapshot()).isEmpty();
        verify(repository, never()).saveAll(any());
    }

    @Test
    void refresh_skipsRatesAlreadyPersistedForThatDay() {
        RateSnapshot fresh = snapshot(TODAY, Map.of("USD", "1.07", "ILS", "3.95"));
        when(provider.fetchLatest()).thenReturn(fresh);
        when(repository.findByAsOf(TODAY))
                .thenReturn(
                        List.of(ExchangeRate.builder().quote("USD").asOf(TODAY).build()));

        service.refresh();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ExchangeRate>> captor = ArgumentCaptor.forClass(List.class);
        verify(repository).saveAll(captor.capture());
        assertThat(captor.getValue()).extracting(ExchangeRate::getQuote).containsExactly("ILS");
    }

    @Test
    void refresh_skipsSaveAllWhenAllRatesAlreadyExist() {
        RateSnapshot fresh = snapshot(TODAY, Map.of("USD", "1.07"));
        when(provider.fetchLatest()).thenReturn(fresh);
        when(repository.findByAsOf(TODAY))
                .thenReturn(
                        List.of(ExchangeRate.builder().quote("USD").asOf(TODAY).build()));

        service.refresh();

        verify(repository, never()).saveAll(any());
    }

    @Test
    void currentSnapshot_emptyBeforeAnyRefresh() {
        assertThat(service.currentSnapshot()).isEmpty();
    }

    @Test
    void initialRefreshOnStartup_freshDbSnapshotSkipsProviderCall() {
        ExchangeRate row = ExchangeRate.builder()
                .quote("USD")
                .asOf(TODAY)
                .rate(new BigDecimal("1.07"))
                .build();
        when(repository.findTopByOrderByAsOfDesc()).thenReturn(Optional.of(row));
        when(repository.findByAsOf(TODAY)).thenReturn(List.of(row));

        service.initialRefreshOnStartup();

        assertThat(service.currentSnapshot()).isPresent();
        assertThat(service.currentSnapshot().orElseThrow().asOf()).isEqualTo(TODAY);
        verifyNoInteractions(provider);
    }

    @Test
    void initialRefreshOnStartup_triggersRefreshWhenDbEmpty() {
        when(repository.findTopByOrderByAsOfDesc()).thenReturn(Optional.empty());
        when(provider.fetchLatest()).thenReturn(snapshot(TODAY, Map.of("USD", "1.07")));
        when(repository.findByAsOf(TODAY)).thenReturn(List.of());

        service.initialRefreshOnStartup();

        verify(provider).fetchLatest();
        assertThat(service.currentSnapshot()).isPresent();
    }

    @Test
    void initialRefreshOnStartup_triggersRefreshWhenDbSnapshotStale() {
        LocalDate stale = TODAY.minusDays(5);
        ExchangeRate row = ExchangeRate.builder()
                .quote("USD")
                .asOf(stale)
                .rate(new BigDecimal("1.05"))
                .build();
        when(repository.findTopByOrderByAsOfDesc()).thenReturn(Optional.of(row));
        when(repository.findByAsOf(stale)).thenReturn(List.of(row));
        when(provider.fetchLatest()).thenReturn(snapshot(TODAY, Map.of("USD", "1.07")));
        when(repository.findByAsOf(TODAY)).thenReturn(List.of());

        service.initialRefreshOnStartup();

        verify(provider).fetchLatest();
        assertThat(service.currentSnapshot().orElseThrow().asOf()).isEqualTo(TODAY);
    }

    @Test
    void init_swallowsDbExceptionToAllowBeanCreation() {
        when(repository.findTopByOrderByAsOfDesc()).thenThrow(new RuntimeException("pool not ready"));

        service.init();

        assertThat(service.currentSnapshot()).isEmpty();
    }

    @Test
    void init_loadsSnapshotFromDbWhenAvailable() {
        ExchangeRate row = ExchangeRate.builder()
                .quote("USD")
                .asOf(TODAY)
                .rate(new BigDecimal("1.07"))
                .build();
        when(repository.findTopByOrderByAsOfDesc()).thenReturn(Optional.of(row));
        when(repository.findByAsOf(TODAY)).thenReturn(List.of(row));

        service.init();

        assertThat(service.currentSnapshot()).isPresent();
        assertThat(service.currentSnapshot().orElseThrow().rates()).containsEntry("USD", new BigDecimal("1.07"));
    }

    private static RateSnapshot snapshot(LocalDate asOf, Map<String, String> rates) {
        Map<String, BigDecimal> decimal = new HashMap<>();
        rates.forEach((k, v) -> decimal.put(k, new BigDecimal(v)));
        return new RateSnapshot(asOf, decimal);
    }
}
