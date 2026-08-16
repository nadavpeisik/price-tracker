package com.np.pricehunt.backend.controller;

import com.np.pricehunt.backend.config.CurrencyProperties;
import com.np.pricehunt.backend.service.fx.ExchangeRateService;
import java.util.Currency;
import java.util.Locale;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/**
 * Turns a {@code ?displayCurrency=} parameter into a validated, supported currency code.
 *
 * <p>Extracted from {@code ProductController} when the dashboard endpoint (#146) became a second
 * caller: every endpoint that quotes money must apply the same rule, and a copy would eventually
 * diverge on exactly the awkward part — that a <em>misconfigured default</em> has to surface as a 400
 * too, not silently produce unconvertible prices.
 *
 * <p>A component rather than a static utility so it can hold the two beans it needs and be imported
 * into a {@code @WebMvcTest} slice like any other collaborator.
 */
@Component
@RequiredArgsConstructor
public class DisplayCurrencyResolver {

    private static final Pattern ISO_4217_CODE = Pattern.compile("^[A-Z]{3}$");

    private final CurrencyProperties currencyProperties;
    private final ExchangeRateService rateService;

    /**
     * @param requested the raw query parameter; null or blank selects the configured default
     * @return an uppercase ISO 4217 code the converter can actually reach
     * @throws ResponseStatusException 400 for a malformed or unsupported code, from either source
     */
    public String resolve(String requested) {
        // The configured default goes through the SAME normalization and format check as a requested
        // code. `pricehunt.currency.default-display` is a plain bound String with no constraint, so
        // `ils` would otherwise be returned lower-cased — this method promises an uppercase ISO code,
        // and every row's `bestPriceConvertedCurrency` is that value echoed onto the wire.
        boolean explicitlyRequested = requested != null && !requested.isBlank();
        String candidate = explicitlyRequested ? requested : currencyProperties.defaultDisplay();
        String resolved = candidate == null ? null : candidate.trim().toUpperCase(Locale.ROOT);

        if (!isIsoCurrencyCode(resolved)) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    explicitlyRequested
                            ? "displayCurrency must be a 3-letter ISO 4217 code"
                            : "Configured default display currency is not a 3-letter ISO 4217 code");
        }
        // Rejects only what the rate snapshot proves we cannot price. When no snapshot has loaded we
        // accept: same-currency conversions need no rate at all, so an FX outage must not 400 a
        // request the app can serve perfectly. Checked on the resolved value, so a misconfigured
        // default surfaces the same way a bad parameter does.
        if (rateService.isDefinitelyUnsupported(resolved)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsupported display currency: " + resolved);
        }
        return resolved;
    }

    /**
     * Whether this is a real currency, as opposed to three capital letters.
     *
     * <p>Separate from the rate check on purpose: ISO membership is a permanent fact about the input
     * and must stay answerable while the FX provider is down. The regex is a cheap pre-filter that
     * keeps obviously-malformed input off the exception path.
     */
    private static boolean isIsoCurrencyCode(String code) {
        if (code == null || !ISO_4217_CODE.matcher(code).matches()) {
            return false;
        }
        try {
            Currency.getInstance(code);
            return true;
        } catch (IllegalArgumentException notACurrency) {
            return false;
        }
    }
}
