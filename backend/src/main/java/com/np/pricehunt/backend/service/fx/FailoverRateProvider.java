package com.np.pricehunt.backend.service.fx;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Tries each rate source in order and returns the first usable snapshot.
 *
 * <p>Two jobs live here rather than in the sources themselves. First, <b>ordering</b>: sources are
 * listed explicitly in the constructor rather than collected by {@code @Order}, so the chain is
 * readable at the one place it matters. Second, <b>the usability check</b> — a snapshot must carry a
 * date, at least one rate, and no rate at or below zero, because {@code PriceConverter} divides by a
 * rate and a zero would throw where a negative would quietly invert a price. That check sits here
 * because it is the same rule for every source, so stating it once beats repeating it in each; a
 * source that enforced it itself would fail over just as well, since either way the loop below catches
 * and moves on.
 *
 * <p>When every source fails, the thrown exception carries the first failure as its cause and the rest
 * as suppressed. The previous fallback swallowed the earlier failure into a one-line {@code WARN} and
 * rethrew only the last one, so the stack trace in the log described the fallback while the question
 * being asked was always why the primary went away.
 */
@Slf4j
@Component
public class FailoverRateProvider implements FxRateProvider {

    private final List<FxRateSource> sources;

    public FailoverRateProvider(FrankfurterRateSource frankfurter, EcbRateSource ecb) {
        this.sources = List.of(frankfurter, ecb);
    }

    @Override
    public RateSnapshot fetchLatest() {
        List<Exception> failures = new ArrayList<>(sources.size());
        for (FxRateSource source : sources) {
            try {
                RateSnapshot snapshot = source.fetchLatest();
                requireUsable(source.sourceName(), snapshot);
                return snapshot;
            } catch (Exception e) {
                failures.add(e);
                log.warn(
                        "FX source {} failed ({}: {}); {} source(s) left to try",
                        source.sourceName(),
                        e.getClass().getSimpleName(),
                        e.getMessage(),
                        sources.size() - failures.size());
            }
        }
        throw allSourcesFailed(failures);
    }

    private static void requireUsable(String sourceName, RateSnapshot snapshot) {
        if (snapshot == null || snapshot.asOf() == null || snapshot.rates().isEmpty()) {
            throw new IllegalStateException(sourceName + " returned empty FX payload");
        }
        // Null values need no check here: RateSnapshot copies the map through Map.copyOf, which rejects
        // them, so a rate that reached this point is a number.
        for (Map.Entry<String, BigDecimal> rate : snapshot.rates().entrySet()) {
            if (rate.getValue().compareTo(BigDecimal.ZERO) <= 0) {
                throw new IllegalStateException(
                        sourceName + " returned non-positive rate for " + rate.getKey() + ": " + rate.getValue());
            }
        }
    }

    private IllegalStateException allSourcesFailed(List<Exception> failures) {
        String names = sources.stream().map(FxRateSource::sourceName).toList().toString();
        IllegalStateException combined = new IllegalStateException(
                "All FX sources failed " + names, failures.isEmpty() ? null : failures.getFirst());
        failures.stream().skip(1).forEach(combined::addSuppressed);
        return combined;
    }
}
