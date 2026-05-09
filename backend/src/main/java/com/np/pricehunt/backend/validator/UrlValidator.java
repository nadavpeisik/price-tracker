package com.np.pricehunt.backend.validator;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.regex.Pattern;

@Component
public class UrlValidator {

    // Anchored on (^|.) so amazon-clone.com / notamazon.com don't match;
    // covers amazon.com, amazon.co.uk, amazon.de, amazon.com.br, etc.
    private static final Pattern AMAZON_HOST = Pattern.compile(
            "(^|\\.)amazon\\.[a-z]{2,3}(\\.[a-z]{2})?$");

    public void validate(String url) {
        URI uri = parseOrThrow(url);
        String host = uri.getHost();
        if (host == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid URL");
        }
        rejectUnsupportedSites(host.toLowerCase(Locale.ROOT));
    }

    private URI parseOrThrow(String url) {
        try {
            return new URI(url);
        } catch (URISyntaxException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid URL");
        }
    }

    private void rejectUnsupportedSites(String host) {
        if (AMAZON_HOST.matcher(host).find()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Amazon URLs are not currently supported");
        }
    }
}
