package com.np.pricehunt.bff.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * The two lifetimes of a logged-in session, per remember-me choice, plus how long a request whose
 * session is mid-refresh waits for the in-flight refresh. Both bounds are enforced server-side
 * ({@code session/}): the inactivity bound is Spring Session's own per-session interval, the absolute
 * bound is an attribute the policy filter checks on every request. The cookie itself carries no
 * lifetime the SPA should rely on.
 */
@Validated
@ConfigurationProperties("pricehunt.bff.session")
public record SessionPolicyProperties(
        @DefaultValue("24h") @NotNull @DurationMin(seconds = 1) Duration absoluteTtl,
        @DefaultValue("24h") @NotNull @DurationMin(seconds = 1) Duration inactivityTtl,
        // @Valid: Bean Validation does not cascade into a nested record without it.
        @DefaultValue @NotNull @Valid RememberMe rememberMe,
        @DefaultValue("20s") @NotNull @DurationMin(millis = 1) Duration refreshWaitTimeout) {

    // Runs before @NotNull is evaluated, which needs an instance to validate. Nothing can be null here
    // anyway: @DefaultValue fills every component first, for an absent property and an empty one alike.
    public SessionPolicyProperties {
        requireInactivityWithinAbsolute("pricehunt.bff.session", inactivityTtl, absoluteTtl);
        requireInactivityWithinAbsolute(
                "pricehunt.bff.session.remember-me", rememberMe.inactivityTtl(), rememberMe.absoluteTtl());
    }

    public record RememberMe(
            @DefaultValue("90d") @NotNull @DurationMin(seconds = 1) Duration absoluteTtl,
            @DefaultValue("30d") @NotNull @DurationMin(seconds = 1) Duration inactivityTtl) {}

    /** The absolute bound is the advertised retention of the row; an inactivity bound past it would be a lie until the first clamp. */
    private static void requireInactivityWithinAbsolute(String prefix, Duration inactivity, Duration absolute) {
        if (inactivity.compareTo(absolute) > 0) {
            throw new IllegalStateException(prefix + ".inactivity-ttl (" + inactivity + ") must not exceed " + prefix
                    + ".absolute-ttl (" + absolute + ")");
        }
    }

    /** {@code persistent} is the remember-me choice: the longer pair of bounds rather than the day one. */
    public Duration absoluteTtlFor(boolean persistent) {
        return persistent ? rememberMe.absoluteTtl() : absoluteTtl;
    }

    public Duration inactivityTtlFor(boolean persistent) {
        return persistent ? rememberMe.inactivityTtl() : inactivityTtl;
    }
}
