package com.np.pricehunt.backend.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Whether an account needs an invitation (issue #249). The identity provider's self-signup stays on
 * either way; this is our gate, so opening to the public is a configuration change, not a migration.
 * With it off, a verified email is still required and a matching invitation is still consumed.
 *
 * <p>Do not turn it off in production before the epic's preconditions exist: distributed rate limits,
 * per-user quotas (#172) and scraper egress hardening (#148, #149).
 */
@ConfigurationProperties("pricehunt.registration")
public record RegistrationProperties(@DefaultValue("true") boolean inviteOnly) {}
