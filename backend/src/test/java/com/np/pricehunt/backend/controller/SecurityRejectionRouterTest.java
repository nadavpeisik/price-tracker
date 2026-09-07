package com.np.pricehunt.backend.controller;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.web.servlet.HandlerExceptionResolver;
import org.springframework.web.servlet.ModelAndView;

/** The router only carries the exception into the MVC pipeline; it never writes a response itself. */
@ExtendWith(MockitoExtension.class)
class SecurityRejectionRouterTest {

    @Mock
    private HandlerExceptionResolver resolver;

    private final MockHttpServletRequest request = new MockHttpServletRequest();
    private final MockHttpServletResponse response = new MockHttpServletResponse();

    @Test
    void authenticationFailure_isResolvedWithNoHandler() {
        var ex = new InsufficientAuthenticationException("none");
        when(resolver.resolveException(same(request), same(response), isNull(), same(ex)))
                .thenReturn(new ModelAndView());

        new SecurityRejectionRouter(resolver).commence(request, response, ex);

        verify(resolver).resolveException(same(request), same(response), isNull(), same(ex));
    }

    @Test
    void accessDenied_isResolvedWithNoHandler() {
        var ex = new AccessDeniedException("no");
        when(resolver.resolveException(any(), any(), isNull(), same(ex))).thenReturn(new ModelAndView());

        new SecurityRejectionRouter(resolver).handle(request, response, ex);

        verify(resolver).resolveException(same(request), same(response), isNull(), same(ex));
    }

    @Test
    void unresolvedRejection_failsLoudly_neverSendError() {
        // sendError would start a container ERROR dispatch that the chain secures again; an
        // unresolved rejection is a wiring bug in the advice and must surface as one.
        var ex = new AccessDeniedException("no");
        when(resolver.resolveException(any(), any(), isNull(), same(ex))).thenReturn(null);

        var router = new SecurityRejectionRouter(resolver);
        assertThatThrownBy(() -> router.handle(request, response, ex))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No exception handler");
    }
}
