package com.np.pricehunt.backend.validator;

import com.np.pricehunt.backend.config.UrlValidationProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

@Slf4j
@Component
public class UrlValidator {

    private final boolean unsupportedSitesEnabled;
    private final List<Pattern> blockedHostPatterns;

    public UrlValidator(UrlValidationProperties properties) {
        this.unsupportedSitesEnabled = properties.unsupportedSitesEnabled();
        this.blockedHostPatterns = properties.unsupportedHostPatterns().stream()
                .filter(s -> !s.isBlank())
                .map(s -> Pattern.compile(s, Pattern.CASE_INSENSITIVE))
                .toList();
        if (!unsupportedSitesEnabled) {
            log.warn("Unsupported-sites blocklist is DISABLED via configuration; all hosts will be allowed past UrlValidator.");
        }
    }

    public void validate(String url) {
        URI uri = parseOrThrow(url);
        String host = uri.getHost();
        String scheme = uri.getScheme();
        if (host == null || scheme == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid URL");
        }
        String normalizedScheme = scheme.toLowerCase(Locale.ROOT);
        if (!normalizedScheme.equals("http") && !normalizedScheme.equals("https")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "URL must be http or https");
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
        if (!unsupportedSitesEnabled) {
            return;
        }
        for (Pattern pattern : blockedHostPatterns) {
            if (pattern.matcher(host).find()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "URLs from " + host + " are not currently supported");
            }
        }
    }
}
