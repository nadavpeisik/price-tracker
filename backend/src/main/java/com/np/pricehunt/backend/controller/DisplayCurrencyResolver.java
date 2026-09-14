package com.np.pricehunt.backend.controller;

import com.np.pricehunt.backend.config.CurrencyProperties;
import com.np.pricehunt.backend.exception.ValidationException;
import com.np.pricehunt.backend.service.UserPreferenceService;
import com.np.pricehunt.backend.service.fx.ExchangeRateService;
import java.util.Currency;
import java.util.Locale;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Turns a {@code ?displayCurrency=} parameter into a validated, supported currency code. Since #248 the
 * chain is request parameter, then the caller's stored preference, then the configured default; the
 * same validation runs on whichever won.
 *
 * <p>Extracted from {@code ProductController} when the dashboard endpoint (#146) became a second
 * caller: every endpoint that quotes money must apply the same rule, and a copy would eventually
 * diverge on exactly the awkward part — that a <em>misconfigured default</em> has to surface as a 400
 * too, not silently produce unconvertible prices.
 *
 * <p>A component rather than a static utility so it can hold the beans it needs and be imported into a
 * {@code @WebMvcTest} slice like any other collaborator. It reaches the caller's preference through a
 * service, never {@code CurrentUser}: controllers do not resolve the user (ArchUnit).
 */
@Component
@RequiredArgsConstructor
public class DisplayCurrencyResolver {

    private static final Pattern ISO_4217_CODE = Pattern.compile("^[A-Z]{3}$");

    private final CurrencyProperties currencyProperties;
    private final ExchangeRateService rateService;
    private final UserPreferenceService userPreferenceService;

    /**
     * @param requested the raw query parameter; null or blank falls back to the caller's stored
     *     preference, then to the configured default
     * @return an uppercase ISO 4217 code the converter can actually reach
     * @throws ValidationException for a malformed or unsupported code, from any source
     */
    public String resolve(String requested) {
        // The stored preference and the configured default go through the SAME normalization and format
        // check as a requested code. `pricehunt.currency.default-display` is a plain bound String with no
        // constraint, so `ils` would otherwise be returned lower-cased — this method promises an
        // uppercase ISO code, and every row's `bestPriceConvertedCurrency` is that value echoed onto the
        // wire. One message for the two non-request sources: which of them was malformed is not
        // something the caller can act on.
        boolean explicitlyRequested = requested != null && !requested.isBlank();
        String candidate = explicitlyRequested
                ? requested
                : userPreferenceService.displayCurrencyPreference().orElseGet(currencyProperties::defaultDisplay);
        String resolved = candidate == null ? null : candidate.trim().toUpperCase(Locale.ROOT);

        if (!isIsoCurrencyCode(resolved)) {
            throw new ValidationException(
                    explicitlyRequested
                            ? "displayCurrency must be a 3-letter ISO 4217 code"
                            : "Display currency is not a 3-letter ISO 4217 code");
        }
        // Rejects only what the rate snapshot proves we cannot price. When no snapshot has loaded we
        // accept: same-currency conversions need no rate at all, so an FX outage must not 400 a
        // request the app can serve perfectly. Checked on the resolved value, so a misconfigured
        // default surfaces the same way a bad parameter does.
        if (rateService.isDefinitelyUnsupported(resolved)) {
            throw new ValidationException("Unsupported display currency: " + resolved);
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
