package com.np.pricehunt.backend.validator;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UrlValidatorTest {

    private final UrlValidator validator = new UrlValidator();

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

    private void assertAmazonRejected(String url) {
        assertThatThrownBy(() -> validator.validate(url))
                .isInstanceOfSatisfying(ResponseStatusException.class, e -> {
                    assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(e.getReason()).contains("Amazon");
                });
    }
}
