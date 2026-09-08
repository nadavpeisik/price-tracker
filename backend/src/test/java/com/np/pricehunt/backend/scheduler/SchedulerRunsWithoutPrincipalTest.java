package com.np.pricehunt.backend.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.np.pricehunt.backend.client.ScraperClient;
import com.np.pricehunt.backend.domain.AppUser;
import com.np.pricehunt.backend.domain.AvailabilityStatus;
import com.np.pricehunt.backend.domain.ExtractionSource;
import com.np.pricehunt.backend.domain.Product;
import com.np.pricehunt.backend.domain.TrackedItem;
import com.np.pricehunt.backend.dto.ScrapeResponse;
import com.np.pricehunt.backend.repository.AppUserRepository;
import com.np.pricehunt.backend.repository.ProductRepository;
import com.np.pricehunt.backend.repository.TrackedItemRepository;
import com.np.pricehunt.backend.repository.UserProductRepository;
import com.np.pricehunt.backend.service.fx.FxRateProvider;
import com.np.pricehunt.backend.service.fx.RateSnapshot;
import com.np.pricehunt.backend.tenancy.TestTenants;
import com.np.pricehunt.backend.validator.HostResolver;
import java.math.BigDecimal;
import java.net.InetAddress;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * The scheduled jobs are the system, not a user (#246): with the security context cleared they refresh
 * the whole catalog — listings under products tracked by one user, by two, and by nobody — and the FX
 * refresh runs. {@code TenancyBoundaryTest} is the structural half (the scheduler package cannot even
 * reach {@code CurrentUser}); this is the behavioural half, against real Postgres and the real pipeline
 * with only the network mocked.
 */
@SpringBootTest
@Testcontainers
@TestPropertySource(
        properties = {
            "spring.docker.compose.enabled=false",
            // The bean must exist (the test drives it by hand); a day's initial delay keeps the
            // scheduled trigger from racing the test.
            "price.scheduler.initial-delay=24h",
            "spring.ai.openai.api-key=test-key",
            "spring.security.oauth2.resourceserver.jwt.issuer-uri=https://test-issuer.invalid/",
            "pricehunt.currency.fx.refresh-cron=-",
            "scrape.audit.purge-cron=-",
        })
class SchedulerRunsWithoutPrincipalTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(DockerImageName.parse("postgres:17"));

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    private PriceCheckScheduler priceCheckScheduler;

    @Autowired
    private RateRefreshScheduler rateRefreshScheduler;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private TrackedItemRepository trackedItemRepository;

    @Autowired
    private AppUserRepository appUsers;

    @Autowired
    private UserProductRepository memberships;

    @MockitoBean
    private ScraperClient scraperClient;

    @MockitoBean
    private FxRateProvider rateProvider;

    /** Public-looking hosts without touching DNS: the SSRF check resolves every name to a public address. */
    @MockitoBean
    private HostResolver hostResolver;

    @BeforeEach
    void seedThreeKindsOfProduct() throws Exception {
        productRepository.deleteAll();
        SecurityContextHolder.clearContext();
        when(hostResolver.resolve(anyString())).thenReturn(new InetAddress[] {InetAddress.getByName("93.184.216.34")});
        // STRUCTURED short-circuits the waterfall: the real orchestrator handles it with no LLM call.
        when(scraperClient.scrape(anyString()))
                .thenReturn(new ScrapeResponse(
                        ExtractionSource.STRUCTURED,
                        new ScrapeResponse.PriceData(new BigDecimal("49.90"), "USD", AvailabilityStatus.AVAILABLE),
                        null,
                        null,
                        null));
        when(rateProvider.fetchLatest())
                .thenReturn(new RateSnapshot(LocalDate.now(), Map.of("USD", new BigDecimal("1.10"))));

        AppUser alice = TestTenants.admit(appUsers, "auth0|alice");
        AppUser bob = TestTenants.admit(appUsers, "auth0|bob");
        Product byBoth =
                productRepository.save(Product.builder().name("Tracked by both").build());
        Product byOne =
                productRepository.save(Product.builder().name("Tracked by one").build());
        Product byNobody = productRepository.save(
                Product.builder().name("Tracked by nobody").build());
        TestTenants.track(memberships, alice, byBoth);
        TestTenants.track(memberships, bob, byBoth);
        TestTenants.track(memberships, alice, byOne);
        int n = 0;
        for (Product product : List.of(byBoth, byOne, byNobody)) {
            trackedItemRepository.save(TrackedItem.builder()
                    .url("https://shop-" + (++n) + ".com/item/" + n)
                    .shopName("Shop " + n)
                    .product(product)
                    .build());
        }
    }

    @Test
    void priceRefresh_withNoPrincipal_refreshesEveryListing_includingOnesNobodyTracks() {
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();

        priceCheckScheduler.refreshAll();

        List<TrackedItem> items = trackedItemRepository.findAll();
        assertThat(items).hasSize(3);
        // A stamped lastChecked means the pipeline saved a price for that listing.
        assertThat(items).allSatisfy(item -> assertThat(item.getLastChecked()).isNotNull());
    }

    @Test
    void fxRefresh_withNoPrincipal_runs() {
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();

        rateRefreshScheduler.scheduledRefresh();
    }
}
