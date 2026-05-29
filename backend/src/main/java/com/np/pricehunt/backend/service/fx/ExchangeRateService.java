package com.np.pricehunt.backend.service.fx;

import com.np.pricehunt.backend.domain.ExchangeRate;
import com.np.pricehunt.backend.repository.ExchangeRateRepository;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Service
public class ExchangeRateService {

    private final ExchangeRateRepository repository;
    private final FrankfurterRateProvider provider;

    // Volatile is sufficient: snapshot is replaced wholesale on refresh; readers see either old or new, never partial.
    private volatile RateSnapshot snapshot;

    public ExchangeRateService(ExchangeRateRepository repository, FrankfurterRateProvider provider) {
        this.repository = repository;
        this.provider = provider;
    }

    @PostConstruct
    void init() {
        // FX is non-critical: a load failure here (missing table mid-migration, transient connection
        // issue) must not prevent bean creation. ApplicationReadyEvent will retrigger a fresh fetch.
        try {
            loadFromDb().ifPresent(snap -> {
                this.snapshot = snap;
                log.info("Loaded FX snapshot from DB: asOf={}, currencies={}", snap.asOf(), snap.rates().size());
            });
        } catch (Exception e) {
            log.error("Failed to load FX snapshot from DB on startup; will retry on refresh", e);
        }
    }

    @EventListener(ApplicationReadyEvent.class)
    public void initialRefreshOnStartup() {
        if (snapshot == null) {
            log.info("No FX rates persisted; triggering initial refresh");
            refresh();
        }
    }

    // No @Transactional: provider.fetchLatest() is a multi-second network call. Wrapping it in a tx
    // would hold a DB connection for the whole fetch and starve the pool under load. saveAll() inside
    // persist() opens its own short-lived tx for the write — that's the only atomicity we need.
    public void refresh() {
        try {
            RateSnapshot fresh = provider.fetchLatest();
            persist(fresh);
            this.snapshot = fresh;
            log.info("FX rates refreshed: asOf={}, currencies={}", fresh.asOf(), fresh.rates().size());
        } catch (Exception e) {
            log.error("FX refresh failed; keeping last known snapshot", e);
        }
    }

    public Optional<RateSnapshot> currentSnapshot() {
        return Optional.ofNullable(snapshot);
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
