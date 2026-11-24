package com.biliwind.blog.controller.api;

import com.biliwind.blog.common.security.UserTokenVerifier;
import com.biliwind.blog.model.UserCheckIn;
import com.biliwind.blog.model.UserWallet;
import com.biliwind.blog.service.CheckInService;
import com.biliwind.blog.service.WalletService;
import com.biliwind.blog.service.security.ClientIpResolver;
import com.biliwind.blog.service.security.ProofOfWorkService;
import com.biliwind.blog.service.security.ProofOfWorkService.Challenge;
import com.biliwind.blog.service.security.ProofOfWorkService.PowScene;
import com.biliwind.blog.service.security.ProofOfWorkService.SystemLoadSnapshot;
import com.biliwind.blog.service.security.SecurityRateLimitService;
import jakarta.ws.rs.core.Cookie;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UserWalletApiControllerTest {

    private static final String SECRET = "unit-test-checkin-pow-secret-0123456789abcdef";

    private UserWalletApiController controller;
    private ProofOfWorkService proofOfWorkService;
    private AtomicBoolean allowChallengeRateLimit;
    private AtomicInteger checkInInvocations;

    @BeforeEach
    void setUp() throws Exception {
        controller = new UserWalletApiController();
        allowChallengeRateLimit = new AtomicBoolean(true);
        checkInInvocations = new AtomicInteger(0);

        proofOfWorkService = new ProofOfWorkService();
        setField(proofOfWorkService, "powSecret", Optional.of(SECRET));
        setField(proofOfWorkService, "legacyCaptchaSecret", Optional.empty());
        setField(proofOfWorkService, "eventHashSecret", "unused");
        setField(proofOfWorkService, "rateLimitService", new SecurityRateLimitService());
        setField(proofOfWorkService, "settings", null);
        setField(proofOfWorkService, "redis", null);
        setLoadProbe(proofOfWorkService, new SystemLoadSnapshot(0.2, 4, 0.10, 0.30));

        UserTokenVerifier verifier = new UserTokenVerifier() {
            @Override
            public VerifiedToken verify(String token) {
                if ("valid-user-token".equals(token)) {
                    return new VerifiedToken(42L, "alice", "USER");
                }
                return null;
            }
        };

        CheckInService checkInService = new CheckInService() {
            @Override
            public UserCheckIn doCheckIn(Long userId) {
                checkInInvocations.incrementAndGet();
                UserCheckIn record = new UserCheckIn();
                record.userId = userId;
                record.checkInDate = LocalDate.now();
                return record;
            }
        };

        WalletService walletService = new WalletService() {
            @Override
            public UserWallet getWalletByUserId(Long userId) {
                UserWallet wallet = new UserWallet();
                wallet.userId = userId;
                wallet.pointsBalance = 120L;
                return wallet;
            }
        };

        SecurityRateLimitService rateLimitService = new SecurityRateLimitService() {
            @Override
            public boolean tryAcquire(String key, int limit, Duration window) {
                return allowChallengeRateLimit.get();
            }
        };

        ClientIpResolver clientIpResolver = new ClientIpResolver() {
            @Override
            public ClientIpResolution resolve(io.vertx.ext.web.RoutingContext context) {
                return new ClientIpResolution("127.0.0.1", "127.0.0.1", null, false, null);
            }
        };

        setField(controller, "tokenVerifier", verifier);
        setField(controller, "checkInService", checkInService);
        setField(controller, "walletService", walletService);
        setField(controller, "proofOfWorkService", proofOfWorkService);
        setField(controller, "securityRateLimitService", rateLimitService);
        setField(controller, "clientIpResolver", clientIpResolver);
    }

    @Test
    void shouldRejectUnauthenticatedChallengeRequest() {
        Response response = controller.checkInChallenge(headersWithCookie(null));
        assertEquals(401, response.getStatus());
    }

    @Test
    void shouldIssueCheckInPowChallengeForAuthenticatedUser() {
        Response response = controller.checkInChallenge(headersWithCookie("valid-user-token"));
        assertEquals(200, response.getStatus());
        assertEquals("no-store", response.getHeaderString("Cache-Control"));

        @SuppressWarnings("unchecked")
        Map<String, Object> entity = (Map<String, Object>) response.getEntity();
        assertTrue((Boolean) entity.get("success"));
        Challenge challenge = (Challenge) entity.get("data");
        assertNotNull(challenge);
        assertTrue(challenge.id().startsWith("v2.check_in."));
        assertEquals(PowScene.CHECK_IN.defaultBaseDifficulty(), challenge.difficulty());
    }

    @Test
    void shouldRateLimitChallengeRequests() {
        allowChallengeRateLimit.set(false);
        Response response = controller.checkInChallenge(headersWithCookie("valid-user-token"));
        assertEquals(429, response.getStatus());
    }

    @Test
    void shouldRejectCheckInWithoutPowProof() {
        Response nullBody = controller.checkIn(headersWithCookie("valid-user-token"), null);
        assertEquals(400, nullBody.getStatus());
        assertEquals(0, checkInInvocations.get());

        Response blankProof = controller.checkIn(
                headersWithCookie("valid-user-token"),
                new UserWalletApiController.CheckInRequest("", "")
        );
        assertEquals(400, blankProof.getStatus());
        assertEquals(0, checkInInvocations.get());
    }

    @Test
    void shouldRejectCheckInWithInvalidOrCrossScopePowProof() {
        Challenge checkInChallenge = proofOfWorkService.issue(PowScene.CHECK_IN, 12);
        Response wrongNonce = controller.checkIn(
                headersWithCookie("valid-user-token"),
                new UserWalletApiController.CheckInRequest(checkInChallenge.id(), "999999")
        );
        assertEquals(400, wrongNonce.getStatus());

        Challenge loginChallenge = proofOfWorkService.issue(PowScene.LOGIN, 12);
        String loginProof = solve(loginChallenge.id(), loginChallenge.difficulty());
        Response crossScope = controller.checkIn(
                headersWithCookie("valid-user-token"),
                new UserWalletApiController.CheckInRequest(loginChallenge.id(), loginProof)
        );
        assertEquals(400, crossScope.getStatus());
        assertEquals(0, checkInInvocations.get());
    }

    @Test
    void shouldAcceptCheckInWithValidPowProof() {
        Challenge challenge = proofOfWorkService.issue(PowScene.CHECK_IN, 16);
        String proof = solve(challenge.id(), challenge.difficulty());

        Response response = controller.checkIn(
                headersWithCookie("valid-user-token"),
                new UserWalletApiController.CheckInRequest(challenge.id(), proof)
        );
        assertEquals(200, response.getStatus());
        assertEquals(1, checkInInvocations.get());

        @SuppressWarnings("unchecked")
        Map<String, Object> entity = (Map<String, Object>) response.getEntity();
        assertTrue((Boolean) entity.get("success"));
        assertEquals(120L, entity.get("newBalance"));
    }

    @Test
    void shouldRejectUnauthenticatedCheckInEvenWithValidPow() {
        Challenge challenge = proofOfWorkService.issue(PowScene.CHECK_IN, 16);
        String proof = solve(challenge.id(), challenge.difficulty());

        Response response = controller.checkIn(
                headersWithCookie(null),
                new UserWalletApiController.CheckInRequest(challenge.id(), proof)
        );
        assertEquals(401, response.getStatus());
        assertEquals(0, checkInInvocations.get());
        @SuppressWarnings("unchecked")
        Map<String, Object> entity = (Map<String, Object>) response.getEntity();
        assertFalse((Boolean) entity.get("success"));
    }

    private static HttpHeaders headersWithCookie(String token) {
        Map<String, Cookie> cookies = token == null
                ? Map.of()
                : Map.of("user_token", new Cookie.Builder("user_token").value(token).build());
        return (HttpHeaders) Proxy.newProxyInstance(
                HttpHeaders.class.getClassLoader(),
                new Class<?>[]{HttpHeaders.class},
                (proxy, method, args) -> {
                    if ("getCookies".equals(method.getName())) {
                        return cookies;
                    }
                    return null;
                }
        );
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static void setLoadProbe(ProofOfWorkService service, SystemLoadSnapshot snapshot) throws Exception {
        Field field = ProofOfWorkService.class.getDeclaredField("loadProbe");
        field.setAccessible(true);
        field.set(service, (java.util.function.Supplier<SystemLoadSnapshot>) () -> snapshot);
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
