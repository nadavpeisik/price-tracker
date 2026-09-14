package com.np.pricehunt.backend.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.np.pricehunt.backend.config.CurrencyProperties;
import com.np.pricehunt.backend.exception.ValidationException;
import com.np.pricehunt.backend.service.UserPreferenceService;
import com.np.pricehunt.backend.service.fx.ExchangeRateService;
import java.math.BigDecimal;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * The three-step chain (#248): request parameter, then the caller's stored preference, then the
 * configured default — and one validation on whichever won, so a bad stored preference 400s exactly
 * like a bad parameter.
 */
@ExtendWith(MockitoExtension.class)
class DisplayCurrencyResolverTest {

    @Mock
    private ExchangeRateService rateService;

    @Mock
    private UserPreferenceService preferences;

    private DisplayCurrencyResolver resolver;

    @BeforeEach
    void setUp() {
        resolver = new DisplayCurrencyResolver(
                new CurrencyProperties("ils", BigDecimal.ZERO, null), rateService, preferences);
    }

    @Test
    void requestedCode_winsOverThePreference_andIsNormalized() {
        when(rateService.isDefinitelyUnsupported("JPY")).thenReturn(false);

        assertThat(resolver.resolve(" jpy ")).isEqualTo("JPY");
        verifyNoInteractions(preferences);
    }

    @Test
    void noParameter_fallsBackToTheStoredPreference() {
        when(preferences.displayCurrencyPreference()).thenReturn(Optional.of("usd"));
        when(rateService.isDefinitelyUnsupported("USD")).thenReturn(false);

        assertThat(resolver.resolve(null)).isEqualTo("USD");
        assertThat(resolver.resolve("  ")).isEqualTo("USD");
    }

    @Test
    void noParameterAndNoPreference_fallsBackToTheConfiguredDefault_normalized() {
        when(preferences.displayCurrencyPreference()).thenReturn(Optional.empty());
        when(rateService.isDefinitelyUnsupported("ILS")).thenReturn(false);

        assertThat(resolver.resolve(null)).isEqualTo("ILS");
    }

    @Test
    void malformedParameter_is400_withTheParameterMessage() {
        assertThatThrownBy(() -> resolver.resolve("ZZZ"))
                .isInstanceOf(ValidationException.class)
                .hasMessage("displayCurrency must be a 3-letter ISO 4217 code");
    }

    @Test
    void malformedStoredPreference_is400_withTheSourceNeutralMessage() {
        when(preferences.displayCurrencyPreference()).thenReturn(Optional.of("ZZZ"));

        assertThatThrownBy(() -> resolver.resolve(null))
                .isInstanceOf(ValidationException.class)
                .hasMessage("Display currency is not a 3-letter ISO 4217 code");
    }

    @Test
    void unsupportedByTheRateSnapshot_is400_fromAnySource() {
        when(preferences.displayCurrencyPreference()).thenReturn(Optional.of("CHF"));
        when(rateService.isDefinitelyUnsupported(anyString())).thenReturn(true);

        assertThatThrownBy(() -> resolver.resolve(null))
                .isInstanceOf(ValidationException.class)
                .hasMessage("Unsupported display currency: CHF");
    }
}
