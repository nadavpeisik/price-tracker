package com.np.pricehunt.backend.controller;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.np.pricehunt.backend.config.CurrencyProperties;
import com.np.pricehunt.backend.service.UserPreferenceService;
import com.np.pricehunt.backend.service.fx.ExchangeRateService;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** The HTTP contract of {@code GET /api/me} (#248): the effective display currency, nothing else. */
@WebMvcTest(MeController.class)
@Import(DisplayCurrencyResolver.class)
@EnableConfigurationProperties(CurrencyProperties.class)
// The HTTP contract only: filters off, so the chain does not 401 every request. SecurityPostureTest
// (#245) enumerates every mapping and owns the security posture.
@AutoConfigureMockMvc(addFilters = false)
@TestPropertySource(properties = "spring.security.oauth2.resourceserver.jwt.issuer-uri=https://test-issuer.invalid/")
class MeControllerTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private ExchangeRateService rateService;

    @MockitoBean
    private UserPreferenceService preferences;

    @BeforeEach
    void setUp() {
        when(rateService.isDefinitelyUnsupported(anyString())).thenReturn(false);
    }

    @Test
    void me_quotesTheStoredPreference() throws Exception {
        when(preferences.displayCurrencyPreference()).thenReturn(Optional.of("USD"));

        mvc.perform(get("/api/me"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.displayCurrency").value("USD"))
                .andExpect(jsonPath("$.email").doesNotExist())
                .andExpect(jsonPath("$.id").doesNotExist());
    }

    @Test
    void me_withNoPreference_quotesTheConfiguredDefault() throws Exception {
        when(preferences.displayCurrencyPreference()).thenReturn(Optional.empty());

        mvc.perform(get("/api/me"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.displayCurrency").value("ILS"));
    }

    @Test
    void me_withAMalformedStoredPreference_is400ProblemDetail() throws Exception {
        when(preferences.displayCurrencyPreference()).thenReturn(Optional.of("ZZZ"));

        mvc.perform(get("/api/me"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Display currency is not a 3-letter ISO 4217 code"));
    }
}
