package com.np.pricehunt.backend.service.fx;

import com.np.pricehunt.backend.domain.ExchangeRate;
import com.np.pricehunt.backend.repository.ExchangeRateRepository;
import jakarta.annotation.PostConstruct;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class ExchangeRateService {

    /**
     * The implicit base of every rate we hold: the provider is queried with {@code ?base=EUR}, so EUR
     * never appears in {@link RateSnapshot#rates()} — its rate is 1 by definition.
     */
    public static final String BASE_CURRENCY = "EUR";

    private final ExchangeRateRepository repository;
    private final FxRateProvider provider;
    private final Clock clock;

    // Volatile is sufficient: snapshot is replaced wholesale on refresh; readers see either old or new, never partial.
    private volatile RateSnapshot snapshot;

    @PostConstruct
    void init() {
        // FX is non-critical: a load failure here (missing table mid-migration, transient connection
        // issue) must not prevent bean creation. ApplicationReadyEvent will retrigger a fresh fetch.
        try {
            loadFromDb().ifPresent(snap -> {
                this.snapshot = snap;
                log.info(
                        "Loaded FX snapshot from DB: asOf={}, currencies={}",
                        snap.asOf(),
                        snap.rates().size());
            });
        } catch (Exception e) {
            log.error("Failed to load FX snapshot from DB on startup; will retry on refresh", e);
        }
    }

    @EventListener(ApplicationReadyEvent.class)
    public void initialRefreshOnStartup() {
        // If @PostConstruct's loadFromDb() threw (transient pool warming, race with Flyway, etc.),
        // snapshot is still null even though the DB is now healthy. Retry the load here before
        // burning an external API call. The stale-check below still runs, so a successfully-loaded
        // but old snapshot will trigger refresh anyway — we only skip the network call when the DB
        // has a fresh-enough snapshot.
        if (snapshot == null) {
            try {
                loadFromDb().ifPresent(snap -> {
                    this.snapshot = snap;
                    log.info(
                            "Loaded FX snapshot from DB on ApplicationReadyEvent: asOf={}, currencies={}",
                            snap.asOf(),
                            snap.rates().size());
                });
            } catch (Exception e) {
                log.error("Retry of DB load failed on ApplicationReadyEvent; falling through to refresh", e);
            }
        }
        // 1-day buffer matches the daily cron cadence: a yesterday-snapshot restart is normal,
        // an older one means we missed at least one cron window and should catch up eagerly.
        if (snapshot == null || snapshot.asOf().isBefore(LocalDate.now(clock).minusDays(1))) {
            log.info("FX snapshot missing or stale; triggering initial refresh");
            refresh();
        }
    }

    // No @Transactional: provider.fetchLatest() is a multi-second network call. Wrapping it in a tx
    // would hold a DB connection for the whole fetch and starve the pool under load. saveAll() inside
    // persist() opens its own short-lived tx for the write — that's the only atomicity we need.
    // synchronized: prevents the ApplicationReadyEvent listener and the scheduled cron from racing
    // on persist() — without it, two concurrent refreshes can both see no rows in findByAsOf() and
    // both try to insert, hitting the uq_exchange_rate_quote_as_of unique constraint. Per-JVM only;
    // a multi-instance deployment would need a DB advisory lock.
    //
    // Returns Optional<RateSnapshot> so callers (e.g. RateRefreshScheduler) can detect success
    // without breaking the "FX failure must not crash the cron" contract: empty = caught exception.
    public synchronized Optional<RateSnapshot> refresh() {
        try {
            RateSnapshot fresh = provider.fetchLatest();
            persist(fresh);
            this.snapshot = fresh;
            log.info(
                    "FX rates refreshed: asOf={}, currencies={}",
                    fresh.asOf(),
                    fresh.rates().size());
            return Optional.of(fresh);
        } catch (Exception e) {
            log.error("FX refresh failed; keeping last known snapshot", e);
            return Optional.empty();
        }
    }

    public Optional<RateSnapshot> currentSnapshot() {
        return Optional.ofNullable(snapshot);
    }

    /**
     * True only when a loaded snapshot proves the currency is unsupported. False means either
     * supported <em>or</em> not currently known.
     */
    public boolean isDefinitelyUnsupported(String currency) {
        if (currency == null) {
            return true;
        }
        String upper = currency.toUpperCase(Locale.ROOT);
        if (BASE_CURRENCY.equals(upper)) {
            return false;
        }
        // One volatile read: a concurrent refresh must not let the null check and the lookup disagree.
        RateSnapshot current = this.snapshot;
        return current != null && !current.rates().containsKey(upper);
    }

    private void persist(RateSnapshot fresh) {
        Set<String> existingQuotes = repository.findByAsOf(fresh.asOf()).stream()
                .map(ExchangeRate::getQuote)
                .collect(Collectors.toSet());

        List<ExchangeRate> toSave = fresh.rates().entrySet().stream()
                .filter(e -> !existingQuotes.contains(e.getKey()))
                .map(e -> ExchangeRate.builder()
                        .quote(e.getKey())
                        .asOf(fresh.asOf())
                        .rate(e.getValue())
                        .build())
                .toList();

        if (!toSave.isEmpty()) {
            repository.saveAll(toSave);
        }
    }

    private Optional<RateSnapshot> loadFromDb() {
        return repository.findTopByOrderByAsOfDesc().map(latest -> {
            List<ExchangeRate> rows = repository.findByAsOf(latest.getAsOf());
            Map<String, BigDecimal> rates = new HashMap<>(rows.size());
            for (ExchangeRate row : rows) {
                rates.put(row.getQuote(), row.getRate());
            }
            return new RateSnapshot(latest.getAsOf(), rates);
        });
    }
}
