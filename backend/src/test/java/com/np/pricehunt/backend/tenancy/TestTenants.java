package com.np.pricehunt.backend.tenancy;

import com.np.pricehunt.backend.domain.AppUser;
import com.np.pricehunt.backend.domain.Product;
import com.np.pricehunt.backend.domain.UserProduct;
import com.np.pricehunt.backend.repository.AppUserRepository;
import com.np.pricehunt.backend.repository.UserProductRepository;

/**
 * Fixture helpers for tests that need an admitted account and memberships (#246). The issuer matches
 * the shadow {@code issuer-uri} in {@code application-test.properties} and the fake identity provider,
 * so a row admitted here is also what a token from that provider resolves to.
 */
public final class TestTenants {

    public static final String ISSUER = "https://test-issuer.invalid/";

    private TestTenants() {}

    /** Idempotent: shared contexts are not transactional, and a second save would trip the identity index. */
    public static AppUser admit(AppUserRepository appUsers, String sub) {
        return appUsers.findByIssuerAndSub(ISSUER, sub)
                .orElseGet(() ->
                        appUsers.save(AppUser.builder().issuer(ISSUER).sub(sub).build()));
    }

    public static UserProduct track(UserProductRepository memberships, AppUser user, Product product) {
        return memberships.save(
                UserProduct.builder().user(user).product(product).build());
    }
}
