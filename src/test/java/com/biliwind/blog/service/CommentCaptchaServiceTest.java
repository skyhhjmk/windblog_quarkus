package com.biliwind.blog.service;

import jakarta.ws.rs.BadRequestException;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommentCaptchaServiceTest {

    private static final String SECRET = "unit-test-comment-captcha-secret-0123456789";

    @Test
    void shouldIssueAChallengeWithoutServerState() {
        CommentCaptchaService service = service(SECRET);

        CommentCaptchaService.Challenge challenge = service.issue();

        assertTrue(challenge.id().startsWith("v1."));
        assertEquals(CommentCaptchaService.DEFAULT_DIFFICULTY, challenge.difficulty());
        assertTrue(challenge.expiresAt() > Instant.now().getEpochSecond());
    }

    @Test
    void shouldAcceptSolvedProof() {
        CommentCaptchaService service = service(SECRET);
        CommentCaptchaService.Challenge challenge = service.issue(now(), 300, 12);

        String proof = solve(challenge.id(), challenge.difficulty());

        assertDoesNotThrow(() -> service.verify(challenge.id(), proof));
    }

    @Test
    void shouldRejectWrongProof() {
        CommentCaptchaService service = service(SECRET);
        CommentCaptchaService.Challenge challenge = service.issue(now(), 300, 12);
        String wrongProof = firstFailingProof(challenge.id(), challenge.difficulty());

        assertThrows(BadRequestException.class, () -> service.verify(challenge.id(), wrongProof));
    }

    @Test
    void shouldRejectExpiredChallenge() {
        CommentCaptchaService service = service(SECRET);
        CommentCaptchaService.Challenge challenge = service.issue(now() - 600, 300, 12);

        assertThrows(BadRequestException.class, () -> service.verify(challenge.id(), "0"));
    }

    @Test
    void shouldRejectTamperedChallenge() {
        CommentCaptchaService service = service(SECRET);
        CommentCaptchaService.Challenge challenge = service.issue();
        String tampered = challenge.id().substring(0, challenge.id().length() - 1)
                + (challenge.id().endsWith("A") ? "B" : "A");

        assertThrows(BadRequestException.class, () -> service.verify(tampered, "0"));
    }

    @Test
    void shouldRejectChallengeSignedWithAnotherSecret() {
        CommentCaptchaService issuer = service(SECRET);
        CommentCaptchaService verifier = service("another-unit-test-secret-0123456789abcd");
        CommentCaptchaService.Challenge challenge = issuer.issue(now(), 300, 12);
        String proof = solve(challenge.id(), challenge.difficulty());

        assertThrows(BadRequestException.class, () -> verifier.verify(challenge.id(), proof));
    }

    @Test
    void shouldRejectMalformedId() {
        CommentCaptchaService service = service(SECRET);

        assertThrows(BadRequestException.class, () -> service.verify(null, "0"));
        assertThrows(BadRequestException.class, () -> service.verify("", "0"));
        assertThrows(BadRequestException.class, () -> service.verify("not-a-challenge", "0"));
        assertThrows(BadRequestException.class, () -> service.verify("v1.1.300.12.AAAA.BBBB", "0"));
    }

    @Test
    void shouldCountLeadingZeroBits() {
        byte[] digest = new byte[32];
        digest[0] = 0;
        digest[1] = 0;
        digest[2] = (byte) 0x80;

        assertTrue(CommentCaptchaService.hasLeadingZeroBits(digest, 16));
        assertFalse(CommentCaptchaService.hasLeadingZeroBits(digest, 17));

        digest[2] = 0x40;
        assertTrue(CommentCaptchaService.hasLeadingZeroBits(digest, 17));
        assertFalse(CommentCaptchaService.hasLeadingZeroBits(digest, 18));
    }

    private static CommentCaptchaService service(String secret) {
        CommentCaptchaService service = new CommentCaptchaService();
        service.configuredSecret = java.util.Optional.ofNullable(secret);
        service.eventHashSecret = "unused";
        service.configManager = null;
        service.redis = null;
        return service;
    }

    private static long now() {
        return Instant.now().getEpochSecond();
    }

    private static String solve(String id, int difficulty) {
        for (long nonce = 0; nonce < 10_000_000L; nonce = nonce + 1) {
            String proof = Long.toString(nonce);
            if (CommentCaptchaService.hasLeadingZeroBits(digestOf(id, proof), difficulty)) {
                return proof;
            }
        }
        throw new AssertionError("无法在测试预算内解出工作量证明");
    }

    private static String firstFailingProof(String id, int difficulty) {
        for (long nonce = 0; nonce < 10_000_000L; nonce = nonce + 1) {
            String proof = Long.toString(nonce);
            if (!CommentCaptchaService.hasLeadingZeroBits(digestOf(id, proof), difficulty)) {
                return proof;
            }
        }
        throw new AssertionError("所有测试 nonce 都满足难度");
    }

    private static byte[] digestOf(String id, String proof) {
        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest((id + ":" + proof).getBytes(StandardCharsets.UTF_8));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }
}
