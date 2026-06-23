package com.np.pricehunt.backend.service;

import com.np.pricehunt.backend.domain.MappingOrigin;
import com.np.pricehunt.backend.domain.ShopNameMapping;
import com.np.pricehunt.backend.domain.ShopNameSource;
import com.np.pricehunt.backend.repository.ShopNameMappingRepository;
import com.np.pricehunt.backend.util.DomainNormalizer;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
public class ShopNameResolver {

    private static final int MAX_NAME_LENGTH = 200;

    private final ShopNameMappingRepository mappingRepository;

    /** Outcome of {@link #resolve}: the name, which tier produced it, and (if from a mapping row) that row's origin. */
    public record Resolved(String name, ShopNameSource source, MappingOrigin origin) {
        public boolean curated() {
            return origin == MappingOrigin.CURATED;
        }
    }

    /** Read-only resolution: mapping (curated or learned) → detected candidate → prettified host floor. */
    public Resolved resolve(String url, String detectedName) {
        String domain = DomainNormalizer.registrableDomain(url);
        if (domain != null) {
            ShopNameMapping mapping = mappingRepository.findByDomain(domain).orElse(null);
            if (mapping != null) {
                return new Resolved(mapping.getDisplayName(), ShopNameSource.MAPPING, mapping.getOrigin());
            }
        }
        String detected = normalize(detectedName);
        if (detected != null) {
            return new Resolved(detected, ShopNameSource.DETECTED, null);
        }
        return new Resolved(DomainNormalizer.prettyLabel(domain), ShopNameSource.HOST_FALLBACK, null);
    }

    /**
     * Persist a strong detection as a LEARNED mapping row via the self-healing upsert. REQUIRES_NEW
     * so the name commits independently of the surrounding price work (and never holds a connection
     * across the scrape/LLM I/O). Only the caller's "strong" gate should reach here.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void learn(String url, String name) {
        String domain = DomainNormalizer.registrableDomain(url);
        String normalized = normalize(name);
        if (domain == null || normalized == null) {
            return;
        }
        mappingRepository.upsertLearned(domain, normalized);
    }

    // Trim, collapse whitespace, strip control chars, reject blank, cap length.
    private String normalize(String raw) {
        if (raw == null) {
            return null;
        }
        String cleaned = raw.replaceAll("\\p{Cc}", " ").replaceAll("\\s+", " ").trim();
        if (cleaned.isEmpty()) {
            return null;
        }
        if (cleaned.length() <= MAX_NAME_LENGTH) {
            return cleaned;
        }
        // Don't split a surrogate pair at the cap — it would leave a malformed lone surrogate.
        int end =
                Character.isHighSurrogate(cleaned.charAt(MAX_NAME_LENGTH - 1)) ? MAX_NAME_LENGTH - 1 : MAX_NAME_LENGTH;
        return cleaned.substring(0, end).trim();
    }
}
