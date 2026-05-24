package com.np.pricehunt.backend.service.fx;

import com.np.pricehunt.backend.domain.ExchangeRate;
import com.np.pricehunt.backend.repository.ExchangeRateRepository;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

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
        loadFromDb().ifPresent(snap -> {
            this.snapshot = snap;
            log.info("Loaded FX snapshot from DB: asOf={}, currencies={}", snap.asOf(), snap.rates().size());
        });
        if (snapshot == null) {
            log.info("No FX rates persisted; triggering initial refresh");
            refresh();
        }
    }

    @Transactional
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
        for (Map.Entry<String, java.math.BigDecimal> entry : fresh.rates().entrySet()) {
            if (!repository.existsByQuoteAndAsOf(entry.getKey(), fresh.asOf())) {
                repository.save(ExchangeRate.builder()
                        .quote(entry.getKey())
                        .asOf(fresh.asOf())
                        .rate(entry.getValue())
                        .build());
            }
        }
    }

    private Optional<RateSnapshot> loadFromDb() {
        return repository.findTopByOrderByAsOfDesc().map(latest -> {
            List<ExchangeRate> rows = repository.findByAsOf(latest.getAsOf());
            Map<String, java.math.BigDecimal> rates = new HashMap<>(rows.size());
            for (ExchangeRate row : rows) {
                rates.put(row.getQuote(), row.getRate());
            }
            return new RateSnapshot(latest.getAsOf(), rates);
        });
    }
}
