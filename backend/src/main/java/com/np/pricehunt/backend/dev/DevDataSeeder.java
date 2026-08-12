package com.np.pricehunt.backend.dev;

import com.np.pricehunt.backend.domain.AvailabilityStatus;
import com.np.pricehunt.backend.domain.ExchangeRate;
import com.np.pricehunt.backend.domain.ExtractionSource;
import com.np.pricehunt.backend.domain.PriceRecord;
import com.np.pricehunt.backend.domain.Product;
import com.np.pricehunt.backend.domain.TrackedItem;
import com.np.pricehunt.backend.repository.ExchangeRateRepository;
import com.np.pricehunt.backend.repository.ProductRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Writes back-dated demo data so the price-trend feature can be seen working on a fresh database
 * (issue #145).
 *
 * <p>Without it, a newly populated database produces a dashboard where every row is correctly but
 * indistinguishably "New": no seven-day window to compare against, no sparkline, no drops to sort by.
 * The fixtures below deliberately cover the states that are rare in real data but decide the rules —
 * a sample exactly at the seven-day boundary, history that stops short of a week, a perfectly flat
 * price, an out-of-stock listing, a never-checked listing, mixed currencies, and a product with no
 * listings at all.
 *
 * <p><b>Gating.</b> One gate — {@code @Profile("seed")} — deliberately, unlike the double-gated
 * scrape-attempt export controller: that one serves untrusted page text over HTTP at runtime, while
 * this only writes rows at startup under a profile someone has to type, and only ever deletes rows
 * carrying its own {@link #SEED_MARKER} prefix.
 *
 * <p><b>Safety around real data.</b> Exchange rates are inserted only where a {@code (quote, asOf)}
 * row is absent and only for dates at least two days old, so the startup FX refresh still runs and
 * today's real snapshot always wins. Seeded URLs live under the reserved {@code .invalid} TLD and are
 * blocklisted in {@code application.properties}, so the scheduler never tries to scrape them.
 */
@Slf4j
@Component
@Profile("seed")
@RequiredArgsConstructor
public class DevDataSeeder implements CommandLineRunner {

    /** Reserved description prefix: what this seeder writes, it may delete. */
    static final String SEED_MARKER = "[dev-seed] ";

    private static final String ILS = "ILS";
    private static final String USD = "USD";
    private static final int FX_HISTORY_DAYS = 35;
    /** Newest seeded FX date, so {@code initialRefreshOnStartup} never sees seeded data as fresh. */
    private static final int FX_LATEST_RATE_DAYS_AGO = 2;

    private final ProductRepository productRepository;
    private final ExchangeRateRepository exchangeRateRepository;
    private final Clock clock;

    @Override
    @Transactional
    public void run(String... args) {
        Instant now = clock.instant();

        List<Product> existingSeedProducts = productRepository.findByDescriptionStartingWith(SEED_MARKER);
        if (!existingSeedProducts.isEmpty()) {
            productRepository.deleteAll(existingSeedProducts);
            productRepository.flush();
        }

        int insertedRateCount = seedExchangeRates(LocalDate.ofInstant(now, ZoneOffset.UTC));
        List<Product> products = productRepository.saveAll(fixtures(now));

        log.info(
                "Dev seed complete: replaced {} product(s) with {}, plus {} exchange-rate row(s)",
                existingSeedProducts.size(),
                products.size(),
                insertedRateCount);
    }

    /**
     * Weekday-only EUR-base rates with a mild drift. The weekend gaps are the point: they exercise the
     * nearest-earlier rate lookup the same way ECB's publication calendar does.
     */
    private int seedExchangeRates(LocalDate today) {
        LocalDate newest = today.minusDays(FX_LATEST_RATE_DAYS_AGO);
        LocalDate oldest = today.minusDays(FX_HISTORY_DAYS);
        // The span between the first and last seeded day — NOT FX_HISTORY_DAYS, which measures from
        // today and so overshoots by the deliberate two-day gap at the recent end.
        long seededSpanDays = ChronoUnit.DAYS.between(oldest, newest);

        // Seeded from what the database already holds, then extended as the loop claims keys — so a
        // single run can neither collide with a stored rate nor queue the same date twice.
        Set<String> seenRateKeys = new HashSet<>();
        exchangeRateRepository
                .findByQuoteInAndAsOfBetweenOrderByAsOfAsc(List.of(USD, ILS), oldest, newest)
                .forEach(rate -> seenRateKeys.add(rateKey(rate.getQuote(), rate.getAsOf())));

        List<ExchangeRate> ratesToInsert = new ArrayList<>();
        for (LocalDate day = oldest; !day.isAfter(newest); day = day.plusDays(1)) {
            if (day.getDayOfWeek() == DayOfWeek.SATURDAY || day.getDayOfWeek() == DayOfWeek.SUNDAY) {
                continue;
            }
            // 0 on the oldest seeded day, 1 on the newest, so the rates drift the full start→end range.
            long daysBeforeNewest = ChronoUnit.DAYS.between(day, newest);
            double progressFraction =
                    seededSpanDays == 0 ? 1.0 : (double) (seededSpanDays - daysBeforeNewest) / seededSpanDays;
            queueRateIfUnseen(ratesToInsert, seenRateKeys, USD, day, interpolateRate("1.08", "1.10", progressFraction));
            queueRateIfUnseen(ratesToInsert, seenRateKeys, ILS, day, interpolateRate("3.95", "4.05", progressFraction));
        }
        exchangeRateRepository.saveAll(ratesToInsert);
        return ratesToInsert.size();
    }

    /**
     * Queues one rate for later insertion, unless its {@code (quote, date)} has been seen already.
     *
     * <p>Nothing is written here — {@code ratesToInsert} is flushed by a single {@code saveAll} once the
     * loop finishes. Note the check also <em>records</em> the key: {@link Set#add} answers "was this new?"
     * and claims it in one operation, which is what keeps a run from queueing a duplicate.
     */
    private static void queueRateIfUnseen(
            List<ExchangeRate> ratesToInsert, Set<String> seenRateKeys, String quote, LocalDate day, BigDecimal rate) {
        if (seenRateKeys.add(rateKey(quote, day))) {
            ratesToInsert.add(
                    ExchangeRate.builder().quote(quote).asOf(day).rate(rate).build());
        }
    }

    private static String rateKey(String quote, LocalDate day) {
        return quote + "@" + day;
    }

    private static BigDecimal interpolateRate(String startRate, String endRate, double progressFraction) {
        BigDecimal start = new BigDecimal(startRate);
        BigDecimal end = new BigDecimal(endRate);
        return start.add(end.subtract(start).multiply(BigDecimal.valueOf(progressFraction)))
                .setScale(8, RoundingMode.HALF_UP);
    }

    private List<Product> fixtures(Instant now) {
        List<Product> products = new ArrayList<>();

        products.add(product(
                "Sony WH-1000XM5",
                "Wireless noise-cancelling headphones",
                listing(
                        "Ivory",
                        "ivory",
                        1001,
                        now,
                        obs(14, "1330"),
                        obs(11, "1319"),
                        obs(9, "1305"),
                        obs(7, "1298"),
                        obs(4, "1288"),
                        obs(2, "1282"),
                        obs(0.2, "1279")),
                listing("KSP", "ksp", 1002, now, obs(13, "1385"), obs(8, "1349"), obs(5, "1320"), obs(1, "1303")),
                listing("Bug", "bug", 1003, now, obs(12, "1390"), obs(6, "1370"), obs(3, "1360"), obs(0.5, "1349"))));

        products.add(product(
                "Logitech MX Master 3S",
                "Wireless productivity mouse",
                listing(
                        "Bug",
                        "bug",
                        2001,
                        now,
                        obs(15, "454"),
                        obs(10, "438"),
                        obs(8, "428"),
                        obs(6, "419"),
                        obs(3, "405"),
                        obs(0.3, "399")),
                listing("KSP", "ksp", 2002, now, obs(14, "455"), obs(7, "446"), obs(2, "431"))));

        products.add(product(
                "LG C3 55\" OLED",
                "4K OLED evo television",
                listing(
                        "TMS",
                        "tms",
                        3001,
                        now,
                        obs(12, "4390"),
                        obs(9, "4440"),
                        obs(7, "4470"),
                        obs(4, "4530"),
                        obs(1, "4590")),
                listing("Ivory", "ivory", 3002, now, obs(11, "4500"), obs(5, "4620"), obs(2, "4690"))));

        products.add(product(
                "Dell U2723QE",
                "27-inch 4K USB-C monitor",
                listing(
                        "Ivory",
                        "ivory",
                        4001,
                        now,
                        obs(10, "2150", AvailabilityStatus.UNKNOWN),
                        obs(8, "2150", AvailabilityStatus.UNKNOWN),
                        obs(3, "2150", AvailabilityStatus.UNKNOWN),
                        obs(0.4, "2150", AvailabilityStatus.UNKNOWN)),
                listing("TMS", "tms", 4002, now, obs(9, "2210"), obs(5, "2205"), obs(1, "2199"))));

        products.add(product(
                "AirPods Pro 2",
                "Active noise-cancelling earbuds",
                listing("Bug", "bug", 5001, now, obs(5, "830"), obs(3, "810"), obs(1, "796"), obs(0.1, "789")),
                listing(
                        "KSP",
                        "ksp",
                        5002,
                        now,
                        obs(4, "869", AvailabilityStatus.UNAVAILABLE),
                        obs(2, "838", AvailabilityStatus.UNAVAILABLE),
                        obs(0.2, "799", AvailabilityStatus.UNAVAILABLE))));

        products.add(product(
                "Samsung 990 Pro 2TB",
                "NVMe Gen4 SSD",
                // The 7-days-ago sample is stamped exactly at now−7d: the inclusive baseline boundary.
                listing("KSP", "ksp", 6001, now, obs(10, "735"), obs(7, "730"), obs(4, "737"), obs(0.6, "739")),
                listing("Bug", "bug", 6002, now, obs(9, "740"), obs(3, "746"), obs(0.8, "749"))));

        products.add(product(
                "מקלדת Keychron K8 Pro",
                "מקלדת מכנית אלחוטית",
                listing("אלקטרה", "electra", 7001, now, obs(12, "420"), obs(7, "409"), obs(1, "389"), obs(0.2, "385")),
                listingIn("Amazon", "amazon-seed", 7002, USD, now, obs(11, "119"), obs(6, "110"), obs(2, "102"))));

        products.add(product(
                "Nintendo Switch 2",
                "Hybrid games console",
                listing(
                        "KSP",
                        "ksp",
                        8001,
                        now,
                        obs(6, "1799", AvailabilityStatus.UNAVAILABLE),
                        obs(2, "1799", AvailabilityStatus.UNAVAILABLE)),
                listing(
                        "Bug",
                        "bug",
                        8002,
                        now,
                        obs(5, "1849", AvailabilityStatus.UNAVAILABLE),
                        obs(1, "1829", AvailabilityStatus.UNAVAILABLE))));

        // Stale beyond the carry-forward TTL → no current price; plus a listing never scraped at all.
        Product framework = product(
                "Framework Laptop 16",
                "Modular repairable laptop",
                listing("TMS", "tms", 9001, now, obs(9, "8890")),
                neverChecked("Ivory", "ivory", 9002));
        products.add(framework);

        products.add(product("Bambu Lab A1 mini", "Compact 3D printer"));

        return products;
    }

    private static Product product(String name, String description, TrackedItem... items) {
        Product product = Product.builder()
                .name(name)
                .description(SEED_MARKER + description)
                .trackedItems(new ArrayList<>())
                .build();
        for (TrackedItem item : items) {
            item.setProduct(product);
            product.getTrackedItems().add(item);
        }
        return product;
    }

    private static TrackedItem listing(String shop, String host, int itemNo, Instant now, Observation... history) {
        return listingIn(shop, host, itemNo, ILS, now, history);
    }

    private static TrackedItem listingIn(
            String shop, String host, int itemNo, String currency, Instant now, Observation... history) {
        TrackedItem item = TrackedItem.builder()
                .url("https://" + host + ".seed.invalid/item/" + itemNo)
                .shopName(shop)
                .priceHistory(new ArrayList<>())
                .build();

        Instant newest = null;
        for (Observation observation : history) {
            Instant at = observation.instantBefore(now);
            item.getPriceHistory()
                    .add(PriceRecord.builder()
                            .price(new BigDecimal(observation.price()))
                            .currency(currency)
                            .availability(observation.availability())
                            .extractionSource(ExtractionSource.STRUCTURED)
                            .timestamp(at)
                            .trackedItem(item)
                            .build());
            if (newest == null || at.isAfter(newest)) {
                newest = at;
            }
        }
        // Mirror what the tracking service does after a successful scrape.
        item.setLastChecked(newest);
        return item;
    }

    /** A tracked listing that has never produced a price — {@code lastChecked} stays null. */
    private static TrackedItem neverChecked(String shop, String host, int itemNo) {
        return TrackedItem.builder()
                .url("https://" + host + ".seed.invalid/item/" + itemNo)
                .shopName(shop)
                .priceHistory(new ArrayList<>())
                .build();
    }

    private static Observation obs(double daysAgo, String price) {
        return new Observation(daysAgo, price, AvailabilityStatus.AVAILABLE);
    }

    private static Observation obs(double daysAgo, String price, AvailabilityStatus availability) {
        return new Observation(daysAgo, price, availability);
    }

    private record Observation(double daysAgo, String price, AvailabilityStatus availability) {
        Instant instantBefore(Instant now) {
            return now.minusMillis(Math.round(daysAgo * 24 * 60 * 60 * 1000));
        }
    }
}
