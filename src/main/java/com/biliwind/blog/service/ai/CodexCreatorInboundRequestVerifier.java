package com.biliwind.blog.service.ai;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.Instant;
import java.util.Optional;

/** Verifies and atomically consumes Codex Creator request nonces. */
@ApplicationScoped
public class CodexCreatorInboundRequestVerifier {
    @ConfigProperty(name = "windblog.ai.codex-creator.shared-secret", defaultValue = "")
    Optional<String> sharedSecret;

    @ConfigProperty(name = "windblog.ai.codex-creator.inbound-clock-skew-seconds", defaultValue = "300")
    long clockSkewSeconds;

    @Inject
    EntityManager entityManager;

    @Transactional(Transactional.TxType.REQUIRES_NEW)
    public boolean verify(String clientId, String timestamp, String nonce,
                          String bodyDigest, String signature, String body) {
        if (sharedSecret.isEmpty() || isBlank(sharedSecret.get())
                || !CodexCreatorHmac.CLIENT_ID.equals(clientId)
                || isBlank(timestamp) || isBlank(nonce) || nonce.length() > 160
                || isBlank(bodyDigest) || isBlank(signature)) {
            return false;
        }

        long timestampSeconds;
        try {
            timestampSeconds = Long.parseLong(timestamp);
        } catch (NumberFormatException ignored) {
            return false;
        }
        long now = Instant.now().getEpochSecond();
        long skew = Math.max(1L, Math.min(clockSkewSeconds, 86_400L));
        if (timestampSeconds < now - skew || timestampSeconds > now + skew) {
            return false;
        }
        if (!CodexCreatorHmac.constantTimeEquals(bodyDigest, CodexCreatorHmac.bodyDigest(body))) {
            return false;
        }
        String expected = CodexCreatorHmac.sign(sharedSecret.get().trim(), clientId, timestamp, nonce, bodyDigest);
        if (!CodexCreatorHmac.constantTimeEquals(expected, signature)) {
            return false;
        }

        try {
            int claimed = entityManager.createNativeQuery("""
                    INSERT INTO codex_creator_integration_nonces (nonce, client_id, expires_at)
                    VALUES (:nonce, :clientId, CURRENT_TIMESTAMP + (:skew * INTERVAL '1 second'))
                    ON CONFLICT (nonce) DO NOTHING
                    """)
                    .setParameter("nonce", nonce)
                    .setParameter("clientId", clientId)
                    .setParameter("skew", skew)
                    .executeUpdate();
            return claimed == 1;
        } catch (RuntimeException ignored) {
            // A missing/unavailable integration table must fail closed.
            return false;
        }
    }

    @io.quarkus.scheduler.Scheduled(every = "1h", identity = "codex-creator-integration-nonce-retention")
    @Transactional
    void removeExpiredNonces() {
        entityManager.createNativeQuery("DELETE FROM codex_creator_integration_nonces WHERE expires_at < CURRENT_TIMESTAMP")
                .executeUpdate();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
