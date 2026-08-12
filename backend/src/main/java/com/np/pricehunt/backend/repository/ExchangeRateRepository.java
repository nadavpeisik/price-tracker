package com.np.pricehunt.backend.repository;

import com.np.pricehunt.backend.domain.ExchangeRate;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ExchangeRateRepository extends JpaRepository<ExchangeRate, Long> {

    Optional<ExchangeRate> findTopByOrderByAsOfDesc();

    List<ExchangeRate> findByAsOf(LocalDate asOf);

    /**
     * Newest rate for one quote currency at or before {@code asOf} — the per-quote anchor for
     * historical conversion (issue #145).
     *
     * <p>Per-quote rather than a single global anchor date: ECB publishes nothing on weekends and
     * holidays, and a date carrying only some currencies would otherwise hide an older, still-valid
     * rate for the currencies missing from it.
     */
    Optional<ExchangeRate> findTopByQuoteAndAsOfLessThanEqualOrderByAsOfDesc(String quote, LocalDate asOf);

    /** Bounded range fetch restricted to the quotes a batch actually needs. */
    List<ExchangeRate> findByQuoteInAndAsOfBetweenOrderByAsOfAsc(
            Collection<String> quotes, LocalDate from, LocalDate to);
}
