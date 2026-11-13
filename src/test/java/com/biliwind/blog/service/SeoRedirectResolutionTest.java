package com.biliwind.blog.service;

import com.biliwind.blog.model.SeoRedirect;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The request filter resolves a redirect chain through the shared rule
 * snapshot, so the status always comes from the first hop and a cycle is
 * dropped instead of being followed.
 */
@QuarkusTest
class SeoRedirectResolutionTest {

    @Inject
    SeoManagementService seo;

    @Test
    void resolvesChainAndKeepsFirstHopStatus() {
        String base = "/seo-chain-" + UUID.randomUUID();
        try {
            QuarkusTransaction.requiringNew().run(() -> {
                seo.saveRedirect(null, base + "/a", base + "/b", 301, true);
                seo.saveRedirect(null, base + "/b", base + "/c", 302, true);
            });

            SeoManagementService.RedirectResolution resolution = seo.resolveRedirect(base + "/a");
            assertNotNull(resolution);
            assertEquals(301, resolution.status());
            assertEquals(base + "/c", resolution.target());

            assertEquals(base + "/c", seo.redirectTarget(base + "/a"));
            assertEquals("/untouched", seo.redirectTarget("/untouched"));
            assertNull(seo.resolveRedirect(base + "/missing"));
        } finally {
            deleteRules(base);
        }
    }

    @Test
    void dropsCyclesInsteadOfFollowingThem() {
        String base = "/seo-cycle-" + UUID.randomUUID();
        try {
            // Bypass saveRedirect's validation to exercise the resolver's own guard.
            QuarkusTransaction.requiringNew().run(() -> {
                persistRule(base + "/x", base + "/y");
                persistRule(base + "/y", base + "/x");
            });
            seo.invalidateRedirectCache();

            assertNull(seo.resolveRedirect(base + "/x"));
            assertNull(seo.redirectTarget(base + "/x"));
        } finally {
            deleteRules(base);
        }
    }

    private static void persistRule(String source, String target) {
        SeoRedirect rule = new SeoRedirect();
        rule.source = source;
        rule.target = target;
        rule.status = 301;
        rule.enabled = true;
        rule.persist();
    }

    /** A leftover cycle would fail every later saveRedirect cycle check. */
    private void deleteRules(String base) {
        QuarkusTransaction.requiringNew().run(() -> SeoRedirect.delete("source like ?1", base + "/%"));
        seo.invalidateRedirectCache();
    }
}
