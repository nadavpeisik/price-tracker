package com.np.pricehunt.backend.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;

/** The admission gate is exactly "resolves to an account": nothing more, and it never throws. */
@ExtendWith(MockitoExtension.class)
class AdmissionAuthorizationManagerTest {

    @Mock
    private CurrentUser currentUser;

    @Mock
    private RequestAuthorizationContext context;

    @InjectMocks
    private AdmissionAuthorizationManager admission;

    private final Authentication someone = new TestingAuthenticationToken("someone", null);

    @Test
    void admittedIdentity_isGranted() {
        when(currentUser.resolveUserId(any())).thenReturn(Optional.of(1L));
        assertThat(admission.authorize(() -> someone, context).isGranted()).isTrue();
    }

    @Test
    void unknownIdentity_isDenied() {
        when(currentUser.resolveUserId(any())).thenReturn(Optional.empty());
        assertThat(admission.authorize(() -> someone, context).isGranted()).isFalse();
    }
}
