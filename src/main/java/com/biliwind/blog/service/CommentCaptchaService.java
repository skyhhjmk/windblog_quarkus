package com.biliwind.blog.service;

import io.quarkus.redis.datasource.RedisDataSource;
import io.quarkus.redis.datasource.value.SetArgs;
import io.quarkus.runtime.annotations.RegisterForReflection;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.ServiceUnavailableException;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;

/**
 * Stateless, invisible comment verification shared by every application instance.
 * <p>
 * A challenge carries its issue time, TTL and proof-of-work difficulty inside an HMAC-signed
 * opaque id, so issuing one never touches Redis and never allocates server-side state.
 * Verification recomputes the HMAC and checks the client proof-of-work nonce. Redis is only
 * touched once per successful submission to keep a challenge single-use; when Redis is
 * unavailable the challenge still verifies (the submission rate limit remains in effect).
 */
@ApplicationScoped
public class CommentCaptchaService {

    private static final Logger LOG = Logger.getLogger(CommentCaptchaService.class);

    static final String CONFIG_KEY = "comment_security";
    static final String USED_KEY_PREFIX = "windblog:comment-captcha-used:";
    static final String CHALLENGE_VERSION = "v1";
    static final int MIN_DIFFICULTY = 12;
    static final int MAX_DIFFICULTY = 24;
    static final int DEFAULT_DIFFICULTY = 16;
    static final int MIN_TTL_SECONDS = 60;
    static final int MAX_TTL_SECONDS = 1800;
    static final int DEFAULT_TTL_SECONDS = 300;
    static final long MAX_CLOCK_SKEW_SECONDS = 30;
    private static final int MAX_ID_LENGTH = 512;
    private static final int NONCE_BYTES = 16;
    private static final int DIGEST_BYTES = 32;
    private static final int MAX_PROOF_LENGTH = 16;

    private final SecureRandom random = new SecureRandom();

    @Inject
    ConfigManager configManager;

    @Inject
    RedisDataSource redis;

    @ConfigProperty(name = "windblog.comment-captcha.secret")
    Optional<String> configuredSecret;

    @ConfigProperty(name = "security.event-hash-secret", defaultValue = "windblog-dev-event-hash-secret")
    String eventHashSecret;

    private volatile byte[] signingKey;

    public Challenge issue() {
        return issue(Instant.now().getEpochSecond(), ttlSeconds(), difficulty());
    }

    public Challenge issue(int difficulty) {
        int boundedDifficulty = Math.clamp(difficulty, MIN_DIFFICULTY, MAX_DIFFICULTY);
        return issue(Instant.now().getEpochSecond(), ttlSeconds(), boundedDifficulty);
    }

    Challenge issue(long issuedAtEpochSeconds, int ttlSeconds, int difficulty) {
        byte[] nonce = new byte[NONCE_BYTES];
        random.nextBytes(nonce);
        String payload = CHALLENGE_VERSION + "." + issuedAtEpochSeconds + "." + ttlSeconds + "."
                + difficulty + "." + encode(nonce);
        String id = payload + "." + encode(hmac(payload));
        return new Challenge(id, difficulty, issuedAtEpochSeconds + ttlSeconds);
    }

    public void verify(String id, String proof) {
        ChallengeParts parts = parse(id);
        if (parts == null) {
            throw new BadRequestException("请先获取验证码");
        }
        long now = Instant.now().getEpochSecond();
        if (parts.issuedAt() > now + MAX_CLOCK_SKEW_SECONDS || now > parts.expiresAt()) {
            throw new BadRequestException("验证码错误或已过期，请重新提交");
        }
        if (!validProof(id, proof, parts.difficulty())) {
            throw new BadRequestException("验证码错误或已过期，请重新提交");
        }
        markUsed(id, parts.expiresAt() - now);
    }

    private ChallengeParts parse(String id) {
        if (id == null || id.isEmpty() || id.length() > MAX_ID_LENGTH) {
            return null;
        }
        String[] parts = id.split("\\.", -1);
        if (parts.length != 6 || !CHALLENGE_VERSION.equals(parts[0])) {
            return null;
        }
        long issuedAt;
        int ttlSeconds;
        int difficulty;
        try {
            issuedAt = Long.parseLong(parts[1]);
            ttlSeconds = Integer.parseInt(parts[2]);
            difficulty = Integer.parseInt(parts[3]);
        } catch (NumberFormatException exception) {
            return null;
        }
        if (issuedAt <= 0 || ttlSeconds < MIN_TTL_SECONDS || ttlSeconds > MAX_TTL_SECONDS
                || difficulty < MIN_DIFFICULTY || difficulty > MAX_DIFFICULTY) {
            return null;
        }
        byte[] nonce = decode(parts[4]);
        if (nonce == null || nonce.length != NONCE_BYTES) {
            return null;
        }
        byte[] signature = decode(parts[5]);
        if (signature == null || signature.length != DIGEST_BYTES) {
            return null;
        }
        String payload = String.join(".", parts[0], parts[1], parts[2], parts[3], parts[4]);
        if (!MessageDigest.isEqual(signature, hmac(payload))) {
            return null;
        }
        return new ChallengeParts(issuedAt, difficulty, issuedAt + ttlSeconds);
    }

    private boolean validProof(String id, String proof, int difficulty) {
        if (proof == null || proof.isEmpty() || proof.length() > MAX_PROOF_LENGTH) {
            return false;
        }
        for (int index = 0; index < proof.length(); index = index + 1) {
            char current = proof.charAt(index);
            if (current < '0' || current > '9') {
                return false;
            }
        }
        byte[] digest = sha256((id + ":" + proof).getBytes(StandardCharsets.UTF_8));
        return hasLeadingZeroBits(digest, difficulty);
    }

    private void markUsed(String id, long remainingSeconds) {
        if (redis == null || remainingSeconds <= 0) {
            return;
        }
        String key = USED_KEY_PREFIX + encode(sha256(id.getBytes(StandardCharsets.UTF_8)));
        try {
            boolean fresh = redis.value(String.class)
                    .setAndChanged(key, "1", new SetArgs().nx().ex(remainingSeconds));
            if (!fresh) {
                throw new BadRequestException("验证码错误或已过期，请重新提交");
            }
        } catch (BadRequestException exception) {
            throw exception;
        } catch (Exception exception) {
            LOG.debugf("评论验证码单次使用标记不可用，降级放行: %s", exception.getMessage());
        }
    }

    public static boolean hasLeadingZeroBits(byte[] digest, int bits) {
        if (digest == null || bits < 0 || digest.length * 8 < bits) {
            return false;
        }
        int fullBytes = bits / 8;
        for (int index = 0; index < fullBytes; index = index + 1) {
            if (digest[index] != 0) {
                return false;
            }
        }
        int remainingBits = bits % 8;
        if (remainingBits == 0) {
            return true;
        }
        int mask = 0xFF << (8 - remainingBits);
        return (digest[fullBytes] & 0xFF & mask) == 0;
    }

    private int difficulty() {
        Integer configured = configManager == null ? null
                : configManager.getInt(CONFIG_KEY, "pow_difficulty", DEFAULT_DIFFICULTY);
        int value = configured == null ? DEFAULT_DIFFICULTY : configured;
        return Math.max(MIN_DIFFICULTY, Math.min(MAX_DIFFICULTY, value));
    }

    private int ttlSeconds() {
        Integer configured = configManager == null ? null
                : configManager.getInt(CONFIG_KEY, "challenge_ttl_seconds", DEFAULT_TTL_SECONDS);
        int value = configured == null ? DEFAULT_TTL_SECONDS : configured;
        return Math.max(MIN_TTL_SECONDS, Math.min(MAX_TTL_SECONDS, value));
    }

    private byte[] signingKey() {
        byte[] key = signingKey;
        if (key != null) {
            return key;
        }
        synchronized (this) {
            if (signingKey != null) {
                return signingKey;
            }
            String base = configuredSecret.map(String::trim).orElse("");
            if (!base.isEmpty() && base.length() < 32) {
                LOG.warn("WINDBLOG_COMMENT_CAPTCHA_SECRET 长度不足 32，已回退到 SECURITY_EVENT_HASH_SECRET 派生签名密钥");
                base = "";
            }
            if (base.isEmpty()) {
                base = eventHashSecret == null ? "" : eventHashSecret;
            }
            if (base.isEmpty()) {
                throw new ServiceUnavailableException("验证码暂时不可用，请稍后重试");
            }
            byte[] derived = hmac(base.getBytes(StandardCharsets.UTF_8),
                    "windblog/comment-captcha/v1".getBytes(StandardCharsets.UTF_8));
            signingKey = derived;
            return derived;
        }
    }

    private byte[] hmac(String payload) {
        return hmac(signingKey(), payload.getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] hmac(byte[] key, byte[] data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(data);
        } catch (Exception exception) {
            throw new IllegalStateException("无法计算评论验证码签名", exception);
        }
    }

    private static byte[] sha256(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(data);
        } catch (Exception exception) {
            throw new IllegalStateException("无法计算评论工作量证明", exception);
        }
    }

    private static String encode(byte[] value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }

    private static byte[] decode(String value) {
        try {
            return Base64.getUrlDecoder().decode(value);
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private record ChallengeParts(long issuedAt, int difficulty, long expiresAt) {
    }

    @RegisterForReflection
    public record Challenge(String id, int difficulty, long expiresAt) {
    }
}
