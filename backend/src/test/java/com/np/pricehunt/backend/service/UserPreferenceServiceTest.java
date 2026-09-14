package com.np.pricehunt.backend.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.np.pricehunt.backend.auth.CurrentUser;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** Delegation only: the raw preference, no default folded in (the resolver validates once). */
@ExtendWith(MockitoExtension.class)
class UserPreferenceServiceTest {

    @Mock
    private CurrentUser currentUser;

    @InjectMocks
    private UserPreferenceService service;

    @Test
    void returnsTheStoredPreference() {
        when(currentUser.displayCurrencyPreference()).thenReturn(Optional.of("USD"));
        assertThat(service.displayCurrencyPreference()).contains("USD");
    }

    @Test
    void noPreference_isEmpty_notTheDefault() {
        when(currentUser.displayCurrencyPreference()).thenReturn(Optional.empty());
        assertThat(service.displayCurrencyPreference()).isEmpty();
    }
}
