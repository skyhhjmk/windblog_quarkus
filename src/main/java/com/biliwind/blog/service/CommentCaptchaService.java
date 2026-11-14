package com.biliwind.blog.service;

import com.biliwind.blog.service.security.ProofOfWorkService;
import com.biliwind.blog.service.security.ProofOfWorkService.PowScene;
import com.biliwind.blog.service.settings.SettingKey;
import com.biliwind.blog.service.settings.SettingsReader;
import io.quarkus.redis.datasource.RedisDataSource;
import io.quarkus.runtime.annotations.RegisterForReflection;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.lang.reflect.Field;
import java.util.Optional;

/**
 * Comment-specific facade delegating Proof-of-Work challenge issuance and
 * verification to {@link ProofOfWorkService}.
 */
@ApplicationScoped
public class CommentCaptchaService {

    static final int MIN_DIFFICULTY = ProofOfWorkService.MIN_DIFFICULTY;
    static final int MAX_DIFFICULTY = ProofOfWorkService.MAX_DIFFICULTY;
    static final int DEFAULT_DIFFICULTY = ProofOfWorkService.DEFAULT_DIFFICULTY;
    static final int MIN_TTL_SECONDS = ProofOfWorkService.MIN_TTL_SECONDS;
    static final int MAX_TTL_SECONDS = ProofOfWorkService.MAX_TTL_SECONDS;
    static final int DEFAULT_TTL_SECONDS = ProofOfWorkService.DEFAULT_TTL_SECONDS;

    @Inject
    ProofOfWorkService powService;

    @Inject
    SettingsReader settings;

    @Inject
    RedisDataSource redis;

    @ConfigProperty(name = "windblog.comment-captcha.secret")
    Optional<String> configuredSecret;

    @ConfigProperty(name = "security.event-hash-secret", defaultValue = "windblog-dev-event-hash-secret")
    String eventHashSecret;

    private volatile ProofOfWorkService fallbackPowService;

    public Challenge issue() {
        return toChallenge(delegate().issue(difficulty()));
    }

    public Challenge issue(int difficulty) {
        return toChallenge(delegate().issue(difficulty));
    }

    public Challenge issueForRequest(RoutingContext context) {
        return toChallenge(delegate().issueForRequest(PowScene.COMMENT, difficulty(), context));
    }

    Challenge issue(long issuedAtEpochSeconds, int ttlSeconds, int difficulty) {
        return toChallenge(delegate().issue(issuedAtEpochSeconds, ttlSeconds, difficulty));
    }

    public void verify(String id, String proof) {
        delegate().verify(id, proof);
    }

    public void verifyForRequest(String id, String proof, RoutingContext context) {
        delegate().verifyForRequest(id, proof, PowScene.COMMENT, context);
    }

    public static boolean hasLeadingZeroBits(byte[] digest, int bits) {
        return ProofOfWorkService.hasLeadingZeroBits(digest, bits);
    }

    private int difficulty() {
        int value = settings == null ? DEFAULT_DIFFICULTY
                : settings.number(SettingKey.COMMENT_SECURITY, "pow_difficulty");
        return Math.max(MIN_DIFFICULTY, Math.min(MAX_DIFFICULTY, value));
    }

    private ProofOfWorkService delegate() {
        if (powService != null) {
            return powService;
        }
        ProofOfWorkService fallback = fallbackPowService;
        if (fallback != null) {
            return fallback;
        }
        synchronized (this) {
            if (fallbackPowService == null) {
                ProofOfWorkService created = new ProofOfWorkService();
                copyField(created, "settings", settings);
                copyField(created, "redis", redis);
                copyField(created, "powSecret", Optional.empty());
                copyField(created, "legacyCaptchaSecret", configuredSecret == null ? Optional.empty() : configuredSecret);
                copyField(created, "eventHashSecret", eventHashSecret);
                fallbackPowService = created;
            }
            return fallbackPowService;
        }
    }

    private static void copyField(Object target, String name, Object value) {
        try {
            Field field = ProofOfWorkService.class.getDeclaredField(name);
            field.setAccessible(true);
            field.set(target, value);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Failed to initialize ProofOfWorkService delegate", exception);
        }
    }

    private static Challenge toChallenge(ProofOfWorkService.Challenge challenge) {
        return new Challenge(challenge.id(), challenge.difficulty(), challenge.expiresAt());
    }

    @RegisterForReflection
    public record Challenge(String id, int difficulty, long expiresAt) {
    }
}
