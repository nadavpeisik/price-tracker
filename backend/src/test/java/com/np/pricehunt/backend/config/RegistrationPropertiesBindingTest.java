package com.np.pricehunt.backend.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * Binds the two #249 records: the gate defaults to invite-only, the claim names default to the
 * namespace the Auth0 Action writes, and an explicitly empty claim name fails the boot rather than
 * silently disabling redemption.
 */
class RegistrationPropertiesBindingTest {

    @EnableConfigurationProperties({RegistrationProperties.class, IdentityClaimProperties.class})
    static class Config {}

    private final ApplicationContextRunner runner = new ApplicationContextRunner().withUserConfiguration(Config.class);

    @Test
    void defaults_inviteOnly_andTheNamespacedClaims() {
        runner.run(ctx -> {
            assertThat(ctx.getBean(RegistrationProperties.class).inviteOnly()).isTrue();
            IdentityClaimProperties claims = ctx.getBean(IdentityClaimProperties.class);
            assertThat(claims.email()).isEqualTo("https://pricehunt.app/email");
            assertThat(claims.emailVerified()).isEqualTo("https://pricehunt.app/email_verified");
        });
    }

    @Test
    void explicitValues_bind() {
        runner.withPropertyValues(
                        "pricehunt.registration.invite-only=false",
                        "pricehunt.auth.claims.email=email",
                        "pricehunt.auth.claims.email-verified=email_verified")
                .run(ctx -> {
                    assertThat(ctx.getBean(RegistrationProperties.class).inviteOnly())
                            .isFalse();
                    assertThat(ctx.getBean(IdentityClaimProperties.class).email())
                            .isEqualTo("email");
                    assertThat(ctx.getBean(IdentityClaimProperties.class).emailVerified())
                            .isEqualTo("email_verified");
                });
    }

    @Test
    void aBlankClaimName_failsTheBoot() {
        runner.withPropertyValues("pricehunt.auth.claims.email=")
                .run(ctx -> assertThat(ctx).hasFailed());
    }
}
