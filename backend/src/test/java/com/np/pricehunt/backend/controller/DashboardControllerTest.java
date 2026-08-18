package com.np.pricehunt.backend.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.np.pricehunt.backend.config.CurrencyProperties;
import com.np.pricehunt.backend.config.DashboardProperties;
import com.np.pricehunt.backend.dto.AvailabilityRollupStatus;
import com.np.pricehunt.backend.dto.DashboardAvailabilityResponse;
import com.np.pricehunt.backend.dto.DashboardBiggestDrop;
import com.np.pricehunt.backend.dto.DashboardFacets;
import com.np.pricehunt.backend.dto.DashboardPageMeta;
import com.np.pricehunt.backend.dto.DashboardPricePointResponse;
import com.np.pricehunt.backend.dto.DashboardProductResponse;
import com.np.pricehunt.backend.dto.DashboardQueryRequest;
import com.np.pricehunt.backend.dto.DashboardResponse;
import com.np.pricehunt.backend.dto.DashboardSortKey;
import com.np.pricehunt.backend.dto.DashboardSummary;
import com.np.pricehunt.backend.service.dashboard.DashboardQueryService;
import com.np.pricehunt.backend.service.fx.ExchangeRateService;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The HTTP boundary: parameter binding, validation, and the exact JSON shape the frontend consumes.
 * The service is mocked — what this pins is the contract, not the computation.
 */
@WebMvcTest(DashboardController.class)
@Import(DisplayCurrencyResolver.class)
@EnableConfigurationProperties({CurrencyProperties.class, DashboardProperties.class})
class DashboardControllerTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private DashboardQueryService queryService;

    @MockitoBean
    private ExchangeRateService rateService;

    @BeforeEach
    void setUp() throws Exception {
        when(rateService.isDefinitelyUnsupported(anyString())).thenReturn(false);
    }

    // --- parameter binding ---

    @Test
    void bareRequestIsValid_everyFilterIsOptional() throws Exception {
        stubEmptyResponse();

        mvc.perform(get("/api/tracked-products")).andExpect(status().isOk());

        DashboardQueryRequest request = capturedRequest();
        assertThat(request.search()).isNull();
        assertThat(request.shops()).isEmpty();
        assertThat(request.sort()).isEqualTo(DashboardSortKey.NAME);
        assertThat(request.page()).isEqualTo(1);
        assertThat(request.size()).isEqualTo(20);
    }

    @Test
    void repeatedShopParamsBecomeSeparateFilters() throws Exception {
        stubEmptyResponse();

        mvc.perform(get("/api/tracked-products").param("shops", "Amazon").param("shops", "KSP"));

        assertThat(capturedRequest().shops()).containsExactly("amazon", "ksp");
    }

    @Test
    void aSingleShopContainingACommaSurvivesAsOneFilter() throws Exception {
        // THE binding pin. @RequestParam List<String> splits a single value on commas, so a shop
        // named "ACME, Inc." would become two filters matching nothing — the chip would silently
        // return zero results for its own shop.
        stubEmptyResponse();

        mvc.perform(get("/api/tracked-products").param("shops", "ACME, Inc."));

        assertThat(capturedRequest().shops()).containsExactly("acme, inc.");
    }

    @Test
    void shopFiltersAreFoldedAndDeduplicated() throws Exception {
        stubEmptyResponse();

        mvc.perform(get("/api/tracked-products").param("shops", "Amazon").param("shops", "amazon"));

        assertThat(capturedRequest().shops()).containsExactly("amazon");
    }

    @Test
    void blankShopParamIsDroppedRatherThanRejected() throws Exception {
        // A bookmarked "?shops=" is a coherent request for everything, not a client error.
        stubEmptyResponse();

        mvc.perform(get("/api/tracked-products").param("shops", "").param("shops", "   "))
                .andExpect(status().isOk());

        assertThat(capturedRequest().shops()).isEmpty();
    }

    @Test
    void blankSearchIsNoSearch_notAMatchEverythingSubstring() throws Exception {
        stubEmptyResponse();

        mvc.perform(get("/api/tracked-products").param("search", "   "));

        assertThat(capturedRequest().search()).isNull();
    }

    @Test
    void searchIsTrimmed() throws Exception {
        stubEmptyResponse();

        mvc.perform(get("/api/tracked-products").param("search", "  sony  "));

        assertThat(capturedRequest().search()).isEqualTo("sony");
    }

    @Test
    void everySortValueTheFrontendCanSendBinds() throws Exception {
        stubEmptyResponse();

        mvc.perform(get("/api/tracked-products").param("sort", "lowestCurrentPrice"));
        assertThat(capturedRequest().sort()).isEqualTo(DashboardSortKey.LOWEST_CURRENT_PRICE);
    }

    // --- validation ---

    @Test
    void pageZeroIs400_becausePaginationIsOneBased() throws Exception {
        mvc.perform(get("/api/tracked-products").param("page", "0")).andExpect(status().isBadRequest());
        verify(queryService, never()).query(any());
    }

    @Test
    void negativePageIs400() throws Exception {
        mvc.perform(get("/api/tracked-products").param("page", "-1")).andExpect(status().isBadRequest());
    }

    @Test
    void sizeBelowOneIs400() throws Exception {
        mvc.perform(get("/api/tracked-products").param("size", "0")).andExpect(status().isBadRequest());
    }

    @Test
    void oversizedPageIsClampedRatherThanRejected() throws Exception {
        stubEmptyResponse();

        mvc.perform(get("/api/tracked-products").param("size", "5000")).andExpect(status().isOk());

        assertThat(capturedRequest().size()).isEqualTo(100);
    }

    @Test
    void unknownSortIs400_namingTheAcceptedValues() throws Exception {
        // The point of hand-parsing the enum rather than letting Spring convert it is the message,
        // so assert the message — a status-only check would pass on a bare "400 Bad Request".
        Exception rejection = mvc.perform(get("/api/tracked-products").param("sort", "cheapest"))
                .andExpect(status().isBadRequest())
                .andReturn()
                .getResolvedException();

        assertThat(rejection)
                .hasMessageContaining("cheapest")
                .hasMessageContaining("name")
                .hasMessageContaining("lowestCurrentPrice")
                .hasMessageContaining("biggest7dDrop");
        verify(queryService, never()).query(any());
    }

    @Test
    void currencyTheRateSnapshotCannotPriceIs400() throws Exception {
        // A real ISO code, so it clears the format gate and the rejection comes from the FX side.
        when(rateService.isDefinitelyUnsupported("JPY")).thenReturn(true);

        mvc.perform(get("/api/tracked-products").param("displayCurrency", "JPY"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void codeThatIsNotARealCurrencyIs400_withoutConsultingTheRateService() throws Exception {
        // ZZZ matches ^[A-Z]{3}$ but is not ISO 4217. Membership is a permanent fact about the input,
        // so it must be answerable while FX is unavailable — hence rejected before the rate check.
        mvc.perform(get("/api/tracked-products").param("displayCurrency", "ZZZ"))
                .andExpect(status().isBadRequest());

        verify(rateService, never()).isDefinitelyUnsupported("ZZZ");
    }

    @Test
    void displayCurrencyIsNormalizedAndTravelsInsideTheRequest() throws Exception {
        stubEmptyResponse();

        mvc.perform(get("/api/tracked-products").param("displayCurrency", " ils "))
                .andExpect(status().isOk());

        assertThat(capturedRequest().displayCurrency()).isEqualTo("ILS");
    }

    @Test
    void omittedDisplayCurrencyFallsBackToTheConfiguredDefault() throws Exception {
        stubEmptyResponse();

        mvc.perform(get("/api/tracked-products")).andExpect(status().isOk());

        assertThat(capturedRequest().displayCurrency()).isEqualTo("ILS");
    }

    // --- response shape ---

    @Test
    void moneyIsSerializedAsStrings_andTheDeltaAsANumber() throws Exception {
        stubResponse(fullResponse());

        mvc.perform(get("/api/tracked-products"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].bestPriceConverted").value("363.6364"))
                .andExpect(jsonPath("$.items[0].bestPriceOriginal").value("100.0000"))
                .andExpect(jsonPath("$.items[0].sparkline[0].price").value("99.5000"))
                .andExpect(jsonPath("$.items[0].delta7d").value(-8.25));
    }

    @Test
    void rowsCarryNoListings_soTheLazyFetchStaysEnforced() throws Exception {
        stubResponse(fullResponse());

        // A scalar id for the winning listing is fine (#157); the listings themselves are not.
        mvc.perform(get("/api/tracked-products"))
                .andExpect(jsonPath("$.items[0].listings").doesNotExist())
                .andExpect(jsonPath("$.items[0].bestTrackedItemId").value(7));
    }

    @Test
    void envelopeCarriesFacetsAndBothSummaries() throws Exception {
        stubResponse(fullResponse());

        mvc.perform(get("/api/tracked-products"))
                .andExpect(jsonPath("$.facets.shops[0]").value("Amazon"))
                .andExpect(jsonPath("$.globalSummary.totalTracked").value(9))
                .andExpect(jsonPath("$.globalSummary.biggestDrop.productName").value("Sony"))
                .andExpect(jsonPath("$.summaryForCurrentQuery.totalTracked").value(1))
                .andExpect(jsonPath("$.items[0].availability.status").value("MIXED"))
                .andExpect(jsonPath("$.items[0].availability.availableCount").value(1))
                .andExpect(jsonPath("$.items[0].availability.total").value(2));
    }

    @Test
    void pageNumberEchoesTheRequestedPage() throws Exception {
        stubResponseForPage(3);

        mvc.perform(get("/api/tracked-products").param("page", "3"))
                .andExpect(jsonPath("$.page.number").value(3));
    }

    @Test
    void extremePageNumberComesBackUnchanged_notWrapped() throws Exception {
        stubResponseForPage(Integer.MAX_VALUE);

        mvc.perform(get("/api/tracked-products").param("page", String.valueOf(Integer.MAX_VALUE)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page.number").value(Integer.MAX_VALUE));
    }

    // --- fixtures ---

    private DashboardQueryRequest capturedRequest() {
        org.mockito.ArgumentCaptor<DashboardQueryRequest> captor =
                org.mockito.ArgumentCaptor.forClass(DashboardQueryRequest.class);
        verify(queryService).query(captor.capture());
        return captor.getValue();
    }

    private void stubEmptyResponse() {
        stubResponseForPage(1);
    }

    private void stubResponseForPage(int page) {
        stubResponse(new DashboardResponse(
                List.of(),
                new DashboardPageMeta(page, 20, 0, 0),
                new DashboardFacets(List.of()),
                new DashboardSummary(0, 0, null),
                new DashboardSummary(0, 0, null)));
    }

    private void stubResponse(DashboardResponse response) {
        when(queryService.query(any())).thenReturn(response);
    }

    private static DashboardResponse fullResponse() {
        DashboardProductResponse row = new DashboardProductResponse(
                1L,
                "Sony WH-1000XM5",
                null,
                null,
                "363.6364",
                "ILS",
                "100.0000",
                "USD",
                "Amazon",
                7L,
                false,
                LocalDate.of(2026, 3, 20),
                true,
                new DashboardAvailabilityResponse(AvailabilityRollupStatus.MIXED, 1, 2),
                new BigDecimal("-8.25"),
                List.of(new DashboardPricePointResponse(Instant.parse("2026-03-19T00:00:00Z"), "99.5000")));

        return new DashboardResponse(
                List.of(row),
                new DashboardPageMeta(1, 20, 1, 1),
                new DashboardFacets(List.of("Amazon", "KSP")),
                new DashboardSummary(9, 3, new DashboardBiggestDrop(1L, "Sony", new BigDecimal("-8.25"))),
                new DashboardSummary(1, 1, new DashboardBiggestDrop(1L, "Sony", new BigDecimal("-8.25"))));
    }
}
