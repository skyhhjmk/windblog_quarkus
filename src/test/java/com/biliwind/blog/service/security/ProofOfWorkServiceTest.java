package com.biliwind.blog.service.security;

import com.biliwind.blog.service.security.ProofOfWorkService.ActorContext;
import com.biliwind.blog.service.security.ProofOfWorkService.Challenge;
import com.biliwind.blog.service.security.ProofOfWorkService.PowScene;
import com.biliwind.blog.service.security.ProofOfWorkService.SystemLoadSnapshot;
import jakarta.ws.rs.BadRequestException;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProofOfWorkServiceTest {

    private static final String SECRET = "unit-test-pow-secret-0123456789abcdef";

    @Test
    void shouldIssueAndVerifyScopedChallenge() {
        ProofOfWorkService service = createService();
        Challenge challenge = service.issue(PowScene.REGISTER, 12);
        assertTrue(challenge.id().startsWith("v2.register."));

        String proof = solve(challenge.id(), challenge.difficulty());
        assertDoesNotThrow(() -> service.verify(challenge.id(), proof, PowScene.REGISTER));
    }

    @Test
    void shouldRejectCrossScopeChallengeReuse() {
        ProofOfWorkService service = createService();
        Challenge reactionChallenge = service.issue(PowScene.REACTION, 12);
        String proof = solve(reactionChallenge.id(), reactionChallenge.difficulty());

        assertThrows(BadRequestException.class,
                () -> service.verify(reactionChallenge.id(), proof, PowScene.REGISTER));
        assertThrows(BadRequestException.class,
                () -> service.verify(reactionChallenge.id(), proof, PowScene.LOGIN));
    }

    @Test
    void shouldEscalateDifficultyOnSameIpBurst() {
        ProofOfWorkService service = createService();
        ActorContext normalActor = new ActorContext("198.51.100.10", "fp-normal-1", "sess-1", false);

        int initial = service.computeDifficulty(PowScene.REGISTER, PowScene.REGISTER.defaultBaseDifficulty(), normalActor, true);
        assertEquals(16, initial);

        // Simulate repeated register challenges from the same IP
        for (int i = 0; i < 5; i++) {
            service.computeDifficulty(PowScene.REGISTER, PowScene.REGISTER.defaultBaseDifficulty(), normalActor, true);
        }
        int escalated = service.computeDifficulty(PowScene.REGISTER, PowScene.REGISTER.defaultBaseDifficulty(), normalActor, true);
        assertTrue(escalated > initial, "Expected difficulty to escalate on same-IP register burst, got " + escalated);
    }

    @Test
    void shouldEscalateDifficultyForSuspectedSameActorAcrossIps() {
        ProofOfWorkService service = createService();
        String sharedFp = "fp-bot-cluster";
        String sharedSession = "sess-reused";

        // Different IPs, same actor fingerprint + same session + suspicious UA
        int first = service.computeDifficulty(PowScene.COMMENT, 16,
                new ActorContext("198.51.100.1", sharedFp, sharedSession, true), true);
        assertTrue(first >= 18, "Suspicious UA should raise initial difficulty");

        for (int i = 0; i < 15; i++) {
            service.computeDifficulty(PowScene.COMMENT, 16,
                    new ActorContext("198.51.100." + (i + 2), sharedFp, sharedSession, true), true);
        }
        int escalated = service.computeDifficulty(PowScene.COMMENT, 16,
                new ActorContext("198.51.100.99", sharedFp, sharedSession, true), true);
        assertTrue(escalated > first, "Expected difficulty to escalate for suspected same actor across IPs");
    }

    @Test
    void shouldEscalateDifficultyWhenSystemLoadIsHigh() {
        ProofOfWorkService service = createService();
        ActorContext actor = new ActorContext("203.0.113.5", "fp-1", null, false);

        service.setLoadProbe(() -> new SystemLoadSnapshot(0.5, 4, 0.20, 0.40));
        int normalDiff = service.computeDifficulty(PowScene.LOGIN, 14, actor, false);
        assertEquals(14, normalDiff);

        service.setLoadProbe(() -> new SystemLoadSnapshot(5.2, 4, 0.92, 0.60));
        int highLoadDiff = service.computeDifficulty(PowScene.LOGIN, 14, actor, false);
        assertEquals(18, highLoadDiff);

        service.setLoadProbe(() -> new SystemLoadSnapshot(9.0, 4, 0.98, 0.95));
        int extremeLoadDiff = service.computeDifficulty(PowScene.LOGIN, 14, actor, false);
        assertEquals(20, extremeLoadDiff);
    }

    @Test
    void shouldRejectStockpiledLowDifficultyChallengeWhenRequiredDifficultySpikes() {
        ProofOfWorkService service = createService();
        service.setLoadProbe(() -> new SystemLoadSnapshot(0.2, 4, 0.10, 0.30));

        Challenge lowChallenge = service.issue(PowScene.LOGIN, Instant.now().getEpochSecond(), 300, 14);
        String proof = solve(lowChallenge.id(), lowChallenge.difficulty());

        // Suddenly system load spikes by +6 (14 -> 20, diff >= 4)
        service.setLoadProbe(() -> new SystemLoadSnapshot(9.0, 4, 0.98, 0.95));
        assertThrows(BadRequestException.class,
                () -> service.verifyForRequest(lowChallenge.id(), proof, PowScene.LOGIN, null));
    }

    @Test
    void submitButtonTemplateEscapesAndIncludesVisualMarkup() {
        String html = ProofOfWorkService.submitButton(
                "registerSubmit",
                "注册 <script>",
                "btn-minimal",
                "registration_submit",
                "data-shortcut=\"enter\""
        ).getValue();

        assertTrue(html.contains("id=\"registerSubmit\""));
        assertTrue(html.contains("data-seeray-goal=\"registration_submit\""));
        assertTrue(html.contains("data-shortcut=\"enter\""));
        assertTrue(html.contains("data-pow-visual-button=\"true\""));
        assertTrue(html.contains("data-pow-state=\"idle\""));
        assertTrue(html.contains("class=\"pow-submit-btn btn-minimal\""));
        assertTrue(html.contains("<span class=\"pow-btn-progress\""));
        assertTrue(html.contains("<span class=\"pow-btn-hash\">"));
        assertTrue(html.contains("<span class=\"btn-text\">注册 &lt;script&gt;</span>"));
        assertTrue(html.contains("<span class=\"spinner hidden\"></span>"));
        assertTrue(html.contains("disabled>"));
    }

    private static ProofOfWorkService createService() {
        ProofOfWorkService service = new ProofOfWorkService();
        setField(service, "powSecret", Optional.of(SECRET));
        setField(service, "legacyCaptchaSecret", Optional.empty());
        setField(service, "eventHashSecret", "unused");
        setField(service, "rateLimitService", new SecurityRateLimitService());
        setField(service, "settings", null);
        setField(service, "redis", null);
        service.setLoadProbe(() -> new SystemLoadSnapshot(0.2, 4, 0.10, 0.30));
        return service;
    }

    private static void setField(Object target, String name, Object value) {
        try {
            Field field = ProofOfWorkService.class.getDeclaredField(name);
            field.setAccessible(true);
            field.set(target, value);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static String solve(String id, int difficulty) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (long nonce = 0; ; nonce++) {
                String proof = Long.toString(nonce);
                byte[] hash = digest.digest((id + ":" + proof).getBytes(StandardCharsets.UTF_8));
                if (ProofOfWorkService.hasLeadingZeroBits(hash, difficulty)) {
                    return proof;
                }
            }
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }
}
