package com.np.pricehunt.backend.service;

import com.np.pricehunt.backend.dto.ScrapeResponse;
import com.np.pricehunt.backend.repository.TrackedItemRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

/**
 * Assigns a listing its shop name, as good as the evidence allows, around a scrape. It lives in the
 * price-check pipeline because the scrape is the only place the strongest evidence (the page's own
 * site name) ever appears — and it runs as two best-effort steps, split around that scrape, so no
 * database transaction is held across the network call and a failed price check still leaves the
 * listing labelled.
 *
 * <p><b>A curated mapping wins.</b> When {@link #applyNameFromUrl} resolves one, the name is final
 * and the caller skips {@link #applyNameFromPage} — that rule is the pipeline's to enforce, which is
 * why the page step takes no flag. Transactions, by case: curated is one (the URL name); a
 * <b>weak</b> {@code <title>} proposal is two (URL name, then the page name as {@code DETECTED}); a
 * <b>strong</b> site-level proposal is three (URL name, {@link ShopNameResolver#learn} in its own
 * {@code REQUIRES_NEW}, then the page name promoted to {@code MAPPING}). Every write goes through
 * {@link TrackedItemRepository#applyShopName} — by id, never via a managed entity — so precedence is
 * enforced in one place and a stale entity can never flush over the result. Resolution rules
 * themselves belong to {@link ShopNameResolver}; this class only decides when they run and commits
 * what they return.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ShopNameAssignment {

    private final ShopNameResolver resolver;
    private final TrackedItemRepository trackedItemRepository;
    private final TransactionTemplate transactionTemplate;

    /**
     * Pre-scrape: commit the best name knowable from the URL alone (mapping, else prettified host).
     *
     * @return true when a curated mapping resolved — the name is authoritative. Best-effort: a
     *     failure logs and returns false.
     */
    public boolean applyNameFromUrl(Long itemId, String url) {
        try {
            return Boolean.TRUE.equals(transactionTemplate.execute(status -> {
                ShopNameResolver.Resolved resolved = resolver.resolve(url, null);
                trackedItemRepository.applyShopName(itemId, resolved.name(), resolved.source());
                return resolved.curated();
            }));
        } catch (RuntimeException e) {
            log.warn("Pre-scrape shop-name resolution failed for url={} — proceeding without it", url, e);
            return false;
        }
    }

    /**
     * Post-scrape: apply the page's proposal. A strong proposal is learned into the shared mapping
     * first so the re-resolve promotes it; a weak one is only ever {@code DETECTED}. A null or blank
     * proposal is a no-op. Best-effort: a failure logs and keeps the URL name.
     */
    public void applyNameFromPage(Long itemId, String url, ScrapeResponse.ShopNameProposal proposal) {
        if (proposal == null || !StringUtils.hasText(proposal.name())) {
            return;
        }
        try {
            if (proposal.strong()) {
                resolver.learn(url, proposal.name());
            }
            transactionTemplate.executeWithoutResult(status -> {
                ShopNameResolver.Resolved resolved = resolver.resolve(url, proposal.name());
                trackedItemRepository.applyShopName(itemId, resolved.name(), resolved.source());
            });
        } catch (RuntimeException e) {
            log.warn("Shop-name learn/resolve failed for url={} — keeping the pre-scrape name", url, e);
        }
    }
}
