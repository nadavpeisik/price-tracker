package com.np.pricehunt.backend.validator;

import com.np.pricehunt.backend.config.UrlValidationProperties;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UrlValidatorTest {

    private static final String AMAZON_PATTERN = "(^|\\.)amazon\\.[a-z]{2,3}(\\.[a-z]{2})?$";

    private final UrlValidator validator = validatorWith(AMAZON_PATTERN);

    @Test
    void validate_thomannUrl_passes() {
        assertThatCode(() -> validator.validate("https://www.thomann.de/gb/some_product.htm"))
                .doesNotThrowAnyException();
    }

    @Test
    void validate_string6Url_passes() {
        assertThatCode(() -> validator.validate("https://www.string6.co.il/product/benson-amps-preamp"))
                .doesNotThrowAnyException();
    }

    @Test
    void validate_amazonClone_passes() {
        assertThatCode(() -> validator.validate("https://amazon-clone.com/product/123"))
                .doesNotThrowAnyException();
    }

    @Test
    void validate_amazonComBareHost_rejected() {
        assertAmazonRejected("https://amazon.com/dp/B000000000");
    }

    @Test
    void validate_amazonComWithSubdomain_rejected() {
        assertAmazonRejected("https://www.amazon.com/dp/B000000000");
    }

    @Test
    void validate_amazonCoUk_rejected() {
        assertAmazonRejected("https://www.amazon.co.uk/dp/B000000000");
    }

    @Test
    void validate_amazonComBr_rejected() {
        assertAmazonRejected("https://www.amazon.com.br/dp/B000000000");
    }

    @Test
    void validate_amazonDe_rejected() {
        assertAmazonRejected("https://www.amazon.de/dp/B000000000");
    }

    @Test
    void validate_uppercaseHost_rejected() {
        // host comparison must be case-insensitive
        assertAmazonRejected("https://WWW.AMAZON.COM/dp/B000000000");
    }

    @Test
    void validate_malformedUrl_rejected() {
        assertThatThrownBy(() -> validator.validate("not a url"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Invalid URL");
    }

    @Test
    void validate_mailtoNoHost_rejected() {
        assertThatThrownBy(() -> validator.validate("mailto:foo@bar.com"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Invalid URL");
    }

    @Test
    void validate_ftpScheme_rejected() {
        assertThatThrownBy(() -> validator.validate("ftp://example.com/file"))
                .isInstanceOfSatisfying(ResponseStatusException.class, e -> {
                    assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(e.getReason()).contains("http or https");
                });
    }

    @Test
    void validate_uppercaseHttpsScheme_passes() {
        assertThatCode(() -> validator.validate("HTTPS://www.thomann.de/gb/some_product.htm"))
                .doesNotThrowAnyException();
    }

    @Test
    void validate_emptyBlocklist_amazonPasses() {
        UrlValidator unrestricted = validatorWith();
        assertThatCode(() -> unrestricted.validate("https://www.amazon.com/dp/B000000000"))
                .doesNotThrowAnyException();
    }

    @Test
    void validate_blocklistDisabled_amazonPasses() {
        UrlValidator disabled = disabledValidatorWith(AMAZON_PATTERN);
        assertThatCode(() -> disabled.validate("https://www.amazon.com/dp/B000000000"))
                .doesNotThrowAnyException();
    }

    @Test
    void validate_blankPatternEntry_ignored() {
        // A blank string compiles to a regex that matches every host — would brick the API.
        UrlValidator withBlank = validatorWith("   ", "");
        assertThatCode(() -> withBlank.validate("https://www.amazon.com/dp/B000000000"))
                .doesNotThrowAnyException();
    }

    @Test
    void validate_uppercasePatternConfig_stillMatchesLowercaseHost() {
        UrlValidator caseInsensitive = validatorWith("(^|\\.)AMAZON\\.[A-Z]{2,3}(\\.[A-Z]{2})?$");
        assertThatThrownBy(() -> caseInsensitive.validate("https://www.amazon.com/dp/B000000000"))
                .isInstanceOfSatisfying(ResponseStatusException.class, e -> {
                    assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(e.getReason()).contains("amazon");
                });
    }

    @Test
    void validate_customBlocklistEntry_rejected() {
        UrlValidator custom = validatorWith("(^|\\.)example\\.com$");
        assertThatThrownBy(() -> custom.validate("https://www.example.com/foo"))
                .isInstanceOfSatisfying(ResponseStatusException.class, e -> {
                    assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(e.getReason())
                            .contains("www.example.com")
                            .contains("not currently supported");
                });
    }

    private static UrlValidator validatorWith(String... patterns) {
        return new UrlValidator(new UrlValidationProperties(true, List.of(patterns)));
    }

    private static UrlValidator disabledValidatorWith(String... patterns) {
        return new UrlValidator(new UrlValidationProperties(false, List.of(patterns)));
    }

    private void assertAmazonRejected(String url) {
        assertThatThrownBy(() -> validator.validate(url))
                .isInstanceOfSatisfying(ResponseStatusException.class, e -> {
                    assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(e.getReason())
                            .contains("amazon")
                            .contains("not currently supported");
                });
    }
}
