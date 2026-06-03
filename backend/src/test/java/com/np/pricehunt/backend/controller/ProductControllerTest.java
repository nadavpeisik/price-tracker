package com.np.pricehunt.backend.controller;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.np.pricehunt.backend.config.CurrencyProperties;
import com.np.pricehunt.backend.config.WebPaginationConfig;
import com.np.pricehunt.backend.domain.ExtractionSource;
import com.np.pricehunt.backend.dto.*;
import com.np.pricehunt.backend.service.ProductQueryService;
import com.np.pricehunt.backend.service.ProductTrackingService;
import com.np.pricehunt.backend.service.fx.PriceConverter;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.client.RestClientException;
import org.springframework.web.server.ResponseStatusException;

@WebMvcTest(ProductController.class)
@Import(WebPaginationConfig.class)
@EnableConfigurationProperties(CurrencyProperties.class)
class ProductControllerTest {

    @Autowired
    private MockMvc mvc;

    private final ObjectMapper mapper = new ObjectMapper();

    @MockitoBean
    private ProductTrackingService trackingService;

    @MockitoBean
    private ProductQueryService queryService;

    @MockitoBean
    private PriceConverter priceConverter;

    @Test
    void getAllProducts_defaultsToConfiguredDisplayCurrency() throws Exception {
        ProductSummaryResponse summary = summaryFixture();
        when(priceConverter.isSupported("ILS")).thenReturn(true);
        when(queryService.getAllProducts(any(Pageable.class), anyString()))
                .thenReturn(new PageImpl<>(List.of(summary)));

        mvc.perform(get("/api/products"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(1))
                .andExpect(jsonPath("$.content[0].name").value("Laptop"))
                .andExpect(jsonPath("$.content[0].bestPriceConverted").value(363.6364))
                .andExpect(jsonPath("$.content[0].bestPriceConvertedCurrency").value("ILS"))
                .andExpect(jsonPath("$.content[0].bestPriceOriginal").value(100))
                .andExpect(jsonPath("$.content[0].bestPriceOriginalCurrency").value("USD"))
                .andExpect(jsonPath("$.content[0].bestPriceShop").value("amazon.com"))
                .andExpect(jsonPath("$.content[0].conversionAsOf").value("2026-05-24"))
                .andExpect(jsonPath("$.content[0].conversionStale").value(false))
                .andExpect(jsonPath("$.content[0].priceBasis").value("AS_LISTED"))
                .andExpect(jsonPath("$.content[0].mixedCurrencies").value(true))
                .andExpect(jsonPath("$.page.totalElements").value(1));

        verify(queryService).getAllProducts(any(Pageable.class), eq("ILS"));
    }

    @Test
    void getAllProducts_explicitDisplayCurrency_passedToService() throws Exception {
        when(priceConverter.isSupported("USD")).thenReturn(true);
        when(queryService.getAllProducts(any(Pageable.class), anyString())).thenReturn(new PageImpl<>(List.of()));

        mvc.perform(get("/api/products").param("displayCurrency", "USD")).andExpect(status().isOk());

        verify(queryService).getAllProducts(any(Pageable.class), eq("USD"));
    }

    @Test
    void getAllProducts_lowercaseDisplayCurrency_normalizedToUpper() throws Exception {
        when(priceConverter.isSupported("EUR")).thenReturn(true);
        when(queryService.getAllProducts(any(Pageable.class), anyString())).thenReturn(new PageImpl<>(List.of()));

        mvc.perform(get("/api/products").param("displayCurrency", "eur")).andExpect(status().isOk());

        verify(queryService).getAllProducts(any(Pageable.class), eq("EUR"));
    }

    @Test
    void getAllProducts_invalidDisplayCurrencyFormat_returns400() throws Exception {
        mvc.perform(get("/api/products").param("displayCurrency", "ZZZZ")).andExpect(status().isBadRequest());

        verify(queryService, never()).getAllProducts(any(Pageable.class), anyString());
    }

    @Test
    void getAllProducts_unsupportedDisplayCurrency_returns400() throws Exception {
        when(priceConverter.isSupported("ZZZ")).thenReturn(false);

        mvc.perform(get("/api/products").param("displayCurrency", "ZZZ")).andExpect(status().isBadRequest());

        verify(queryService, never()).getAllProducts(any(Pageable.class), anyString());
    }

    @Test
    void getAllProducts_pageParams_passedToService() throws Exception {
        when(priceConverter.isSupported("ILS")).thenReturn(true);
        when(queryService.getAllProducts(any(Pageable.class), anyString())).thenReturn(new PageImpl<>(List.of()));

        mvc.perform(get("/api/products").param("page", "2").param("size", "5")).andExpect(status().isOk());

        verify(queryService).getAllProducts(argThat(p -> p.getPageNumber() == 2 && p.getPageSize() == 5), eq("ILS"));
    }

    @Test
    void getAllProducts_userSortWithoutId_appendsIdTiebreaker() throws Exception {
        when(priceConverter.isSupported("ILS")).thenReturn(true);
        when(queryService.getAllProducts(any(Pageable.class), anyString())).thenReturn(new PageImpl<>(List.of()));

        mvc.perform(get("/api/products").param("sort", "name,asc")).andExpect(status().isOk());

        verify(queryService)
                .getAllProducts(
                        argThat(p -> {
                            Sort.Order idOrder = p.getSort().getOrderFor("id");
                            return idOrder != null && p.getSort().getOrderFor("name") != null;
                        }),
                        eq("ILS"));
    }

    @Test
    void getProduct_found_returnsDetail() throws Exception {
        TrackedItemSummary item = new TrackedItemSummary(
                1L, "https://amazon.com/dp/123", "amazon.com", new BigDecimal("999.99"), "USD", true, Instant.now());
        ProductDetailResponse detail = new ProductDetailResponse(1L, "Laptop", null, List.of(item));
        when(queryService.getProduct(1L)).thenReturn(detail);

        mvc.perform(get("/api/products/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.name").value("Laptop"))
                .andExpect(jsonPath("$.trackedItems[0].shopName").value("amazon.com"))
                .andExpect(jsonPath("$.trackedItems[0].currentPrice").value(999.99));
    }

    @Test
    void getProduct_notFound_returns404() throws Exception {
        when(queryService.getProduct(99L)).thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND));

        mvc.perform(get("/api/products/99")).andExpect(status().isNotFound());
    }

    @Test
    void deleteProduct_returnsNoContent() throws Exception {
        mvc.perform(delete("/api/products/1")).andExpect(status().isNoContent());
        verify(trackingService).deleteProduct(1L);
    }

    @Test
    void deleteProduct_notFound_returns404() throws Exception {
        doThrow(new ResponseStatusException(HttpStatus.NOT_FOUND))
                .when(trackingService)
                .deleteProduct(99L);

        mvc.perform(delete("/api/products/99")).andExpect(status().isNotFound());
    }

    @Test
    void deleteTrackedItem_returnsNoContent() throws Exception {
        mvc.perform(delete("/api/products/1/tracked-items/2")).andExpect(status().isNoContent());
        verify(trackingService).deleteTrackedItem(1L, 2L);
    }

    @Test
    void deleteTrackedItem_wrongProduct_returns404() throws Exception {
        doThrow(new ResponseStatusException(HttpStatus.NOT_FOUND))
                .when(trackingService)
                .deleteTrackedItem(1L, 99L);

        mvc.perform(delete("/api/products/1/tracked-items/99")).andExpect(status().isNotFound());
    }

    @Test
    void updateProduct_returnsLightweightResponse() throws Exception {
        when(trackingService.updateProduct(eq(1L), any())).thenReturn(new ProductResponse(1L, "New Name", null));

        mvc.perform(patch("/api/products/1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(new UpdateProductRequest("New Name", null))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.name").value("New Name"))
                .andExpect(jsonPath("$.trackedItems").doesNotExist());
    }

    @Test
    void refreshTrackedItem_returnsTrackResponse() throws Exception {
        TrackResponse response = new TrackResponse(
                1L,
                "Laptop",
                1L,
                "https://amazon.com/dp/123",
                "amazon.com",
                new BigDecimal("949.99"),
                "USD",
                true,
                Instant.now(),
                ExtractionSource.FULLTEXT);
        when(trackingService.refreshTrackedItem(1L, 1L)).thenReturn(response);

        mvc.perform(post("/api/products/1/tracked-items/1/refresh"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currentPrice").value(949.99))
                .andExpect(jsonPath("$.extractionSource").value("FULLTEXT"));
    }

    @Test
    void refreshTrackedItem_scraperFails_returns502() throws Exception {
        when(trackingService.refreshTrackedItem(1L, 1L)).thenThrow(new RestClientException("scraper unreachable"));

        mvc.perform(post("/api/products/1/tracked-items/1/refresh")).andExpect(status().isBadGateway());
    }

    @Test
    void getPriceHistory_noParams_returnsFullHistory() throws Exception {
        PricePointResponse point =
                new PricePointResponse(new BigDecimal("999.99"), "USD", true, Instant.now(), "STRUCTURED");
        PriceHistoryResponse history =
                new PriceHistoryResponse(1L, "amazon.com", "https://amazon.com/dp/123", List.of(point));
        when(queryService.getPriceHistory(eq(1L), eq(1L), isNull(), isNull())).thenReturn(history);

        mvc.perform(get("/api/products/1/tracked-items/1/price-history"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.trackedItemId").value(1))
                .andExpect(jsonPath("$.history[0].currency").value("USD"))
                .andExpect(jsonPath("$.history[0].extractionSource").value("STRUCTURED"));
    }

    @Test
    void getPriceHistory_withFromParam_parsesDateAndCallsService() throws Exception {
        PriceHistoryResponse history =
                new PriceHistoryResponse(1L, "amazon.com", "https://amazon.com/dp/123", List.of());
        when(queryService.getPriceHistory(eq(1L), eq(1L), any(Instant.class), isNull()))
                .thenReturn(history);

        mvc.perform(get("/api/products/1/tracked-items/1/price-history").param("from", "2026-01-01T00:00:00Z"))
                .andExpect(status().isOk());

        verify(queryService).getPriceHistory(eq(1L), eq(1L), eq(Instant.parse("2026-01-01T00:00:00Z")), isNull());
    }

    @Test
    void getPriceHistory_withBothParams_passesBothToService() throws Exception {
        PriceHistoryResponse history =
                new PriceHistoryResponse(1L, "amazon.com", "https://amazon.com/dp/123", List.of());
        when(queryService.getPriceHistory(eq(1L), eq(1L), any(Instant.class), any(Instant.class)))
                .thenReturn(history);

        mvc.perform(get("/api/products/1/tracked-items/1/price-history")
                        .param("from", "2026-01-01T00:00:00Z")
                        .param("to", "2026-04-01T00:00:00Z"))
                .andExpect(status().isOk());

        verify(queryService)
                .getPriceHistory(
                        eq(1L),
                        eq(1L),
                        eq(Instant.parse("2026-01-01T00:00:00Z")),
                        eq(Instant.parse("2026-04-01T00:00:00Z")));
    }

    private static ProductSummaryResponse summaryFixture() {
        return new ProductSummaryResponse(
                1L,
                "Laptop",
                null,
                2,
                new BigDecimal("363.6364"),
                "ILS",
                new BigDecimal("100"),
                "USD",
                "amazon.com",
                LocalDate.of(2026, 5, 24),
                false,
                PriceBasis.AS_LISTED,
                true,
                true);
    }
}
