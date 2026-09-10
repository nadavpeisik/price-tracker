package com.np.pricehunt.bff.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerExceptionResolver;
import org.springframework.web.servlet.ModelAndView;

/**
 * Carries Spring Security's rejections into the MVC exception pipeline so {@link GlobalExceptionHandler}
 * stays the only writer of {@code ProblemDetail} bodies (copied from the backend: #245, contract from #231). Entry point
 * and access-denied handler run in the filter chain, outside {@code DispatcherServlet}, where a
 * {@code @ControllerAdvice} never sees them; the {@code handlerExceptionResolver} bean hands the
 * exception to the advice as if a controller had thrown it.
 *
 * <p>Never falls back to {@code response.sendError}, which would start a container ERROR dispatch to
 * {@code /error} that the chain secures again and that re-enters this router on an already-handled
 * response. The advice maps every type that can arrive here, so an unresolved one is a wiring bug.
 */
@Component
public class SecurityRejectionRouter implements AuthenticationEntryPoint, AccessDeniedHandler {

    private final HandlerExceptionResolver resolver;

    public SecurityRejectionRouter(@Qualifier("handlerExceptionResolver") HandlerExceptionResolver resolver) {
        this.resolver = resolver;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response, AuthenticationException ex) {
        route(request, response, ex);
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response, AccessDeniedException ex) {
        route(request, response, ex);
    }

    private void route(HttpServletRequest request, HttpServletResponse response, Exception ex) {
        ModelAndView resolved = resolver.resolveException(request, response, null, ex);
        if (resolved == null) {
            throw new IllegalStateException("No exception handler resolved a security rejection: " + ex, ex);
        }
    }
}
