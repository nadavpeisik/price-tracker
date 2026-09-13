package com.np.pricehunt.bff.auth;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;

/** Only a safe, lower-case OAuth2 error code ever reaches the redirect URL. */
class LoginFailureRedirectTest {

    @Test
    void oauth2Code_passesThrough() {
        assertThat(LoginFailureRedirect.errorCode(new OAuth2AuthenticationException(new OAuth2Error("invalid_grant"))))
                .isEqualTo("invalid_grant");
    }

    @Test
    void unsafeOrForeignCodes_becomeUnknown() {
        assertThat(LoginFailureRedirect.errorCode(
                        new OAuth2AuthenticationException(new OAuth2Error("<script>alert(1)</script>"))))
                .isEqualTo("unknown");
        assertThat(LoginFailureRedirect.errorCode(new OAuth2AuthenticationException(new OAuth2Error("a".repeat(65)))))
                .isEqualTo("unknown");
        assertThat(LoginFailureRedirect.errorCode(new BadCredentialsException("x")))
                .isEqualTo("unknown");
    }
}
