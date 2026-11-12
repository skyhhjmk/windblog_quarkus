package com.biliwind.blog.controller;

import com.biliwind.blog.service.CommentCaptchaService;
import com.biliwind.blog.service.ReactionService;
import com.biliwind.blog.service.security.ClientIpResolver;
import com.biliwind.blog.service.security.SecurityRateLimitService;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReactionApiControllerTest {

    private static final String SECRET = "unit-test-reaction-pow-secret-9876543210";

    private ReactionApiController controller;
    private CommentCaptchaService captchaService;
    private ReactionService reactionService;

    @BeforeEach
    void setup() throws Exception {
        controller = new ReactionApiController();

        captchaService = new CommentCaptchaService();
        setField(captchaService, "configuredSecret", Optional.of(SECRET));
        setField(captchaService, "eventHashSecret", "unused");
        setField(captchaService, "settings", null);
        setField(captchaService, "redis", null);

        reactionService = new ReactionService();

        setField(controller, "captchaService", captchaService);
        setField(controller, "reactionService", reactionService);
        setField(controller, "rateLimitService", new SecurityRateLimitService() {
            @Override
            public boolean tryAcquire(String key, int limit, java.time.Duration window) {
                return true;
            }
        });
        setField(controller, "clientIpResolver", new ClientIpResolver() {
            @Override
            public ClientIpResolution resolve(io.vertx.ext.web.RoutingContext context) {
                return new ClientIpResolution("127.0.0.1", "127.0.0.1", null, false, null);
            }
        });
    }

    private void setField(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    @Test
    void shouldIssueLightweightPoWChallenge() {
        Response response = controller.challenge(null);
        assertEquals(200, response.getStatus());

        @SuppressWarnings("unchecked")
        Map<String, Object> entity = (Map<String, Object>) response.getEntity();
        assertTrue((Boolean) entity.get("success"));

        CommentCaptchaService.Challenge ch = (CommentCaptchaService.Challenge) entity.get("data");
        assertNotNull(ch);
        assertEquals(ReactionApiController.REACTION_POW_DIFFICULTY, ch.difficulty());
        assertTrue(ch.id().startsWith("v1."));
    }

    @Test
    void shouldRejectReactionWithoutPoWProof() {
        ReactionApiController.ReactionRequest req = new ReactionApiController.ReactionRequest(
                "like", "react", null, null
        );

        assertThrows(BadRequestException.class, () ->
                controller.react("test-slug", req, null));
    }

    @Test
    void shouldRejectReactionWithInvalidPoWProof() {
        CommentCaptchaService.Challenge ch = captchaService.issue(12);
        ReactionApiController.ReactionRequest req = new ReactionApiController.ReactionRequest(
                "like", "react", ch.id(), "wrong-nonce-999"
        );

        assertThrows(BadRequestException.class, () ->
                controller.react("test-slug", req, null));
    }

    @Test
    void shouldAcceptReactionWithValidPoWProof() {
        CommentCaptchaService.Challenge ch = captchaService.issue(12);
        String validNonce = solve(ch.id(), ch.difficulty());

        ReactionApiController.ReactionRequest req = new ReactionApiController.ReactionRequest(
                "heart", "react", ch.id(), validNonce
        );

        Response response = controller.react("test-slug", req, null);
        assertEquals(200, response.getStatus());

        @SuppressWarnings("unchecked")
        Map<String, Object> entity = (Map<String, Object>) response.getEntity();
        assertTrue((Boolean) entity.get("success"));

        @SuppressWarnings("unchecked")
        Map<String, Long> counts = (Map<String, Long>) entity.get("data");
        assertNotNull(counts);
        assertTrue(counts.get("heart") > 0);
    }

    @Test
    void shouldAllowUnreactWithoutPoWProof() {
        ReactionApiController.ReactionRequest unreactReq = new ReactionApiController.ReactionRequest(
                "like", "unreact", null, null
        );

        Response response = controller.react("test-slug", unreactReq, null);
        assertEquals(200, response.getStatus());
    }

    private static String solve(String id, int difficulty) {
        for (long nonce = 0; nonce < 10_000_000L; nonce++) {
            String proof = Long.toString(nonce);
            if (CommentCaptchaService.hasLeadingZeroBits(digestOf(id, proof), difficulty)) {
                return proof;
            }
        }
        throw new AssertionError("无法解出工作量证明");
    }

    private static byte[] digestOf(String id, String proof) {
        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest((id + ":" + proof).getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
