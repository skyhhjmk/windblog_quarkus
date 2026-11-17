package com.biliwind.blog.service.security;

import com.biliwind.blog.service.settings.SettingKey;
import com.biliwind.blog.service.settings.SettingsReader;
import io.quarkus.qute.RawString;
import io.quarkus.qute.TemplateExtension;
import io.quarkus.redis.datasource.RedisDataSource;
import io.quarkus.redis.datasource.value.SetArgs;
import io.quarkus.runtime.annotations.RegisterForReflection;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.ServiceUnavailableException;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryUsage;
import java.lang.management.OperatingSystemMXBean;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Standalone, stateless Proof-of-Work (PoW) service shared across registration,
 * login, comments, reactions, and other public write endpoints.
 * <p>
 * Challenges are HMAC-SHA256 signed tokens carrying version, optional scope,
 * issue timestamp, TTL, and difficulty. Verification is stateless except for a
 * single-use Redis marker per solved challenge.
 * <p>
 * Difficulty scales dynamically when:
 * <ul>
 *     <li>The same IP floods endpoints, registration, login, or comments</li>
 *     <li>A suspected same actor (via header/session fingerprint or automated UA) bursts across requests</li>
 *     <li>System CPU / load average / JVM heap pressure is elevated</li>
 * </ul>
 */
@ApplicationScoped
public class ProofOfWorkService {

    private static final Logger LOG = Logger.getLogger(ProofOfWorkService.class);

    public static final String USED_KEY_PREFIX = "windblog:pow-used:";
    public static final String VERSION_V1 = "v1";
    public static final String VERSION_V2 = "v2";
    public static final int MIN_DIFFICULTY = 12;
    public static final int MAX_DIFFICULTY = 24;
    public static final int DEFAULT_DIFFICULTY = 16;
    public static final int MIN_TTL_SECONDS = 60;
    public static final int MAX_TTL_SECONDS = 1800;
    public static final int DEFAULT_TTL_SECONDS = 300;
    public static final long MAX_CLOCK_SKEW_SECONDS = 30;
    private static final int MAX_ID_LENGTH = 512;
    private static final int NONCE_BYTES = 16;
    private static final int DIGEST_BYTES = 32;
    private static final int MAX_PROOF_LENGTH = 16;
    private static final int STALE_UPGRADE_TOLERANCE = 4;

    private static final Duration GLOBAL_BURST_WINDOW = Duration.ofMinutes(5);
    private static final Duration FAILURE_WINDOW = Duration.ofMinutes(15);

    private final SecureRandom random = new SecureRandom();

    @Inject
    SettingsReader settings;

    @Inject
    RedisDataSource redis;

    @Inject
    SecurityRateLimitService rateLimitService;

    @Inject
    ClientIpResolver clientIpResolver;

    @ConfigProperty(name = "windblog.pow.secret")
    Optional<String> powSecret;

    @ConfigProperty(name = "windblog.comment-captcha.secret")
    Optional<String> legacyCaptchaSecret;

    @ConfigProperty(name = "security.event-hash-secret", defaultValue = "windblog-dev-event-hash-secret")
    String eventHashSecret;

    private volatile byte[] signingKey;
    private Supplier<SystemLoadSnapshot> loadProbe = ProofOfWorkService::readSystemLoad;

    public Challenge issue() {
        return issue(Instant.now().getEpochSecond(), ttlSeconds(), DEFAULT_DIFFICULTY);
    }

    public Challenge issue(int difficulty) {
        int boundedDifficulty = clampDifficulty(difficulty);
        return issue(Instant.now().getEpochSecond(), ttlSeconds(), boundedDifficulty);
    }

    public Challenge issue(PowScene scene, int difficulty) {
        int boundedDifficulty = clampDifficulty(difficulty);
        return issue(scene, Instant.now().getEpochSecond(), ttlSeconds(), boundedDifficulty);
    }

    public Challenge issueForRequest(PowScene scene, RoutingContext context) {
        PowScene effectiveScene = scene == null ? PowScene.GENERAL : scene;
        return issueForRequest(effectiveScene, effectiveScene.defaultBaseDifficulty(), context);
    }

    public Challenge issueForRequest(PowScene scene, int baseDifficulty, RoutingContext context) {
        PowScene effectiveScene = scene == null ? PowScene.GENERAL : scene;
        ActorContext actor = resolveActor(context);
        int difficulty = computeDifficulty(effectiveScene, baseDifficulty, actor, true);
        return issue(effectiveScene, Instant.now().getEpochSecond(), ttlSeconds(), difficulty);
    }

    public Challenge issue(long issuedAtEpochSeconds, int ttlSeconds, int difficulty) {
        byte[] nonce = new byte[NONCE_BYTES];
        random.nextBytes(nonce);
        int boundedDifficulty = clampDifficulty(difficulty);
        String payload = VERSION_V1 + "." + issuedAtEpochSeconds + "." + ttlSeconds + "."
                + boundedDifficulty + "." + encode(nonce);
        String id = payload + "." + encode(hmac(payload));
        return new Challenge(id, boundedDifficulty, issuedAtEpochSeconds + ttlSeconds);
    }

    public Challenge issue(PowScene scene, long issuedAtEpochSeconds, int ttlSeconds, int difficulty) {
        if (scene == null) {
            return issue(issuedAtEpochSeconds, ttlSeconds, difficulty);
        }
        byte[] nonce = new byte[NONCE_BYTES];
        random.nextBytes(nonce);
        int boundedDifficulty = clampDifficulty(difficulty);
        String payload = VERSION_V2 + "." + scene.scope() + "." + issuedAtEpochSeconds + "." + ttlSeconds + "."
                + boundedDifficulty + "." + encode(nonce);
        String id = payload + "." + encode(hmac(payload));
        return new Challenge(id, boundedDifficulty, issuedAtEpochSeconds + ttlSeconds);
    }

    public void verify(String id, String proof) {
        verify(id, proof, null);
    }

    public void verify(String id, String proof, PowScene expectedScene) {
        ChallengeParts parts = validateChallengeAndProof(id, proof, expectedScene);
        long now = Instant.now().getEpochSecond();
        markUsed(id, parts.expiresAt() - now);
    }

    public void verifyForRequest(String id, String proof, PowScene expectedScene, RoutingContext context) {
        PowScene effectiveScene = expectedScene == null ? PowScene.GENERAL : expectedScene;
        ActorContext actor = resolveActor(context);
        ChallengeParts parts;
        try {
            parts = validateChallengeAndProof(id, proof, effectiveScene);
        } catch (BadRequestException exception) {
            recordFailure(effectiveScene, actor);
            throw exception;
        }

        int currentRequired = computeDifficulty(effectiveScene, effectiveScene.defaultBaseDifficulty(), actor, false);
        if (currentRequired - parts.difficulty() >= STALE_UPGRADE_TOLERANCE) {
            recordFailure(effectiveScene, actor);
            throw new BadRequestException("安全校验强度已升级，请刷新校验后重试");
        }

        long now = Instant.now().getEpochSecond();
        try {
            markUsed(id, parts.expiresAt() - now);
        } catch (BadRequestException exception) {
            recordFailure(effectiveScene, actor);
            throw exception;
        }
        recordSubmission(effectiveScene, actor);
    }

    public void recordFailure(PowScene scene, RoutingContext context) {
        recordFailure(scene == null ? PowScene.GENERAL : scene, resolveActor(context));
    }

    void recordFailure(PowScene scene, ActorContext actor) {
        if (rateLimitService == null || actor == null) {
            return;
        }
        if (hasText(actor.clientIp())) {
            rateLimitService.incrementAndGet("pow:ip:fail:" + actor.clientIp(), FAILURE_WINDOW);
        }
        if (hasText(actor.actorFingerprint())) {
            rateLimitService.incrementAndGet("pow:actor:fail:" + actor.actorFingerprint(), FAILURE_WINDOW);
        }
        if (scene != null && hasText(actor.clientIp())) {
            rateLimitService.incrementAndGet("pow:ip:" + scene.scope() + ":" + actor.clientIp(), scene.burstWindow());
        }
    }

    private void recordSubmission(PowScene scene, ActorContext actor) {
        if (rateLimitService == null || actor == null) {
            return;
        }
        if (hasText(actor.clientIp())) {
            rateLimitService.incrementAndGet("pow:ip:global:" + actor.clientIp(), GLOBAL_BURST_WINDOW);
            rateLimitService.incrementAndGet("pow:ip:" + scene.scope() + ":" + actor.clientIp(), scene.burstWindow());
        }
        if (hasText(actor.actorFingerprint())) {
            rateLimitService.incrementAndGet("pow:actor:global:" + actor.actorFingerprint(), GLOBAL_BURST_WINDOW);
            rateLimitService.incrementAndGet("pow:actor:" + scene.scope() + ":" + actor.actorFingerprint(), scene.burstWindow());
        }
        if (hasText(actor.sessionFingerprint())) {
            rateLimitService.incrementAndGet("pow:session:" + scene.scope() + ":" + actor.sessionFingerprint(), scene.burstWindow());
        }
    }

    public int currentDifficulty(PowScene scene, int baseDifficulty, RoutingContext context) {
        PowScene effectiveScene = scene == null ? PowScene.GENERAL : scene;
        return computeDifficulty(effectiveScene, baseDifficulty, resolveActor(context), false);
    }

    int computeDifficulty(PowScene scene, int baseDifficulty, ActorContext actor, boolean recordAttempt) {
        int clampedBase = clampDifficulty(baseDifficulty);
        int ipBoost = computeIpBoost(scene, actor, recordAttempt);
        int actorBoost = computeActorBoost(scene, actor, recordAttempt);
        int abuseBoost = Math.min(8, Math.max(ipBoost, actorBoost) + (ipBoost > 0 && actorBoost > 0 ? 1 : 0));
        int loadBoost = computeSystemLoadBoost(loadProbe.get());
        return clampDifficulty(clampedBase + abuseBoost + loadBoost);
    }

    private int computeIpBoost(PowScene scene, ActorContext actor, boolean recordAttempt) {
        if (rateLimitService == null || actor == null || !hasText(actor.clientIp())) {
            return 0;
        }
        String ip = actor.clientIp();
        String globalKey = "pow:ip:global:" + ip;
        String sceneKey = "pow:ip:" + scene.scope() + ":" + ip;
        String failKey = "pow:ip:fail:" + ip;

        long globalCount = recordAttempt
                ? rateLimitService.incrementAndGet(globalKey, GLOBAL_BURST_WINDOW)
                : rateLimitService.currentCount(globalKey, GLOBAL_BURST_WINDOW);
        long sceneCount = recordAttempt
                ? rateLimitService.incrementAndGet(sceneKey, scene.burstWindow())
                : rateLimitService.currentCount(sceneKey, scene.burstWindow());
        long failCount = rateLimitService.currentCount(failKey, FAILURE_WINDOW);

        int globalBoost = globalCount > 60 ? 6 : (globalCount > 30 ? 4 : (globalCount > 15 ? 2 : 0));
        int sceneBoost = sceneIpBoost(scene, sceneCount);
        int failBoost = failCount > 12 ? 6 : (failCount > 6 ? 4 : (failCount > 2 ? 2 : 0));
        return Math.max(globalBoost, Math.max(sceneBoost, failBoost));
    }

    private int computeActorBoost(PowScene scene, ActorContext actor, boolean recordAttempt) {
        if (actor == null) {
            return 0;
        }
        int uaBoost = actor.suspiciousUserAgent() ? 2 : 0;
        if (rateLimitService == null) {
            return uaBoost;
        }

        int fingerprintBoost = 0;
        if (hasText(actor.actorFingerprint())) {
            String fp = actor.actorFingerprint();
            String globalKey = "pow:actor:global:" + fp;
            String sceneKey = "pow:actor:" + scene.scope() + ":" + fp;
            String failKey = "pow:actor:fail:" + fp;

            long globalCount = recordAttempt
                    ? rateLimitService.incrementAndGet(globalKey, GLOBAL_BURST_WINDOW)
                    : rateLimitService.currentCount(globalKey, GLOBAL_BURST_WINDOW);
            long sceneCount = recordAttempt
                    ? rateLimitService.incrementAndGet(sceneKey, scene.burstWindow())
                    : rateLimitService.currentCount(sceneKey, scene.burstWindow());
            long failCount = rateLimitService.currentCount(failKey, FAILURE_WINDOW);

            int gBoost = globalCount > 100 ? 6 : (globalCount > 50 ? 4 : (globalCount > 25 ? 2 : 0));
            int sBoost = sceneActorBoost(scene, sceneCount);
            int fBoost = failCount > 15 ? 4 : (failCount > 5 ? 2 : 0);
            fingerprintBoost = Math.max(gBoost, Math.max(sBoost, fBoost));
        }

        int sessionBoost = 0;
        if (hasText(actor.sessionFingerprint())) {
            String sessionKey = "pow:session:" + scene.scope() + ":" + actor.sessionFingerprint();
            long sessionCount = recordAttempt
                    ? rateLimitService.incrementAndGet(sessionKey, scene.burstWindow())
                    : rateLimitService.currentCount(sessionKey, scene.burstWindow());
            sessionBoost = sceneSessionBoost(scene, sessionCount);
        }

        return Math.min(6, Math.max(fingerprintBoost, sessionBoost) + uaBoost);
    }

    private static int sceneIpBoost(PowScene scene, long count) {
        return switch (scene) {
            case REGISTER -> count > 8 ? 6 : (count > 4 ? 4 : (count > 2 ? 2 : 0));
            case LOGIN -> count > 20 ? 6 : (count > 10 ? 4 : (count > 5 ? 2 : 0));
            case COMMENT -> count > 25 ? 6 : (count > 12 ? 4 : (count > 5 ? 2 : 0));
            case REACTION -> count > 80 ? 6 : (count > 40 ? 4 : (count > 20 ? 2 : 0));
            case GENERAL -> count > 25 ? 6 : (count > 12 ? 4 : (count > 5 ? 2 : 0));
        };
    }

    private static int sceneActorBoost(PowScene scene, long count) {
        return switch (scene) {
            case REGISTER -> count > 10 ? 4 : (count > 4 ? 2 : 0);
            case LOGIN -> count > 25 ? 4 : (count > 10 ? 2 : 0);
            case COMMENT -> count > 20 ? 4 : (count > 8 ? 2 : 0);
            case REACTION -> count > 80 ? 4 : (count > 40 ? 2 : 0);
            case GENERAL -> count > 20 ? 4 : (count > 8 ? 2 : 0);
        };
    }

    private static int sceneSessionBoost(PowScene scene, long count) {
        return switch (scene) {
            case REGISTER -> count > 6 ? 4 : (count > 3 ? 2 : 0);
            case LOGIN -> count > 16 ? 4 : (count > 8 ? 2 : 0);
            case COMMENT -> count > 12 ? 4 : (count > 6 ? 2 : 0);
            case REACTION -> count > 60 ? 4 : (count > 30 ? 2 : 0);
            case GENERAL -> count > 15 ? 4 : (count > 7 ? 2 : 0);
        };
    }

    static int computeSystemLoadBoost(SystemLoadSnapshot snapshot) {
        if (snapshot == null) {
            return 0;
        }
        int cpus = Math.max(1, snapshot.availableProcessors());
        double loadRatio = snapshot.systemLoadAverage() >= 0.0
                ? snapshot.systemLoadAverage() / cpus
                : -1.0;
        double cpuLoad = snapshot.cpuLoad();
        double heapRatio = snapshot.heapUsageRatio();

        if (loadRatio >= 2.0 || cpuLoad >= 0.97) {
            return 6;
        }
        if (loadRatio >= 1.2 || cpuLoad >= 0.90 || heapRatio >= 0.92) {
            return 4;
        }
        if (loadRatio >= 0.8 || cpuLoad >= 0.75 || heapRatio >= 0.85) {
            return 2;
        }
        return 0;
    }

    static SystemLoadSnapshot readSystemLoad() {
        OperatingSystemMXBean osBean = ManagementFactory.getOperatingSystemMXBean();
        int cpus = Math.max(1, osBean.getAvailableProcessors());
        double loadAvg = osBean.getSystemLoadAverage();
        double cpuLoad = -1.0;
        if (osBean instanceof com.sun.management.OperatingSystemMXBean sunOsBean) {
            double systemCpu = sunOsBean.getCpuLoad();
            if (systemCpu >= 0.0) {
                cpuLoad = systemCpu;
            } else {
                double processCpu = sunOsBean.getProcessCpuLoad();
                if (processCpu >= 0.0) {
                    cpuLoad = processCpu;
                }
            }
        }
        MemoryUsage heap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
        double heapRatio = heap.getMax() > 0 ? (double) heap.getUsed() / heap.getMax() : 0.0;
        return new SystemLoadSnapshot(loadAvg, cpus, cpuLoad, heapRatio);
    }

    ActorContext resolveActor(RoutingContext context) {
        String ip = "unknown";
        if (clientIpResolver != null && context != null) {
            ip = clientIpResolver.resolve(context).clientIp();
        }
        if (context == null || context.request() == null) {
            return new ActorContext(ip, null, null, false);
        }
        String ua = safeHeader(context, "User-Agent");
        String lang = safeHeader(context, "Accept-Language");
        String secChUa = safeHeader(context, "Sec-CH-UA");
        String secChPlatform = safeHeader(context, "Sec-CH-UA-Platform");

        String rawActor = (ua + "|" + lang + "|" + secChUa + "|" + secChPlatform).toLowerCase(Locale.ROOT);
        String actorFp = rawActor.equals("|||") ? "anon" : shortHash(rawActor);

        String sessionToken = null;
        if (context.request().getCookie("XSRF-TOKEN") != null) {
            sessionToken = context.request().getCookie("XSRF-TOKEN").getValue();
        } else if (context.request().getCookie("user_token") != null) {
            sessionToken = context.request().getCookie("user_token").getValue();
        }
        String sessionFp = hasText(sessionToken) ? shortHash(sessionToken) : null;
        return new ActorContext(ip, actorFp, sessionFp, isSuspiciousUserAgent(ua));
    }

    static boolean isSuspiciousUserAgent(String userAgent) {
        if (userAgent == null || userAgent.isBlank()) {
            return true;
        }
        String lower = userAgent.toLowerCase(Locale.ROOT);
        return lower.contains("curl/")
                || lower.contains("wget/")
                || lower.contains("python-requests")
                || lower.contains("python-urllib")
                || lower.contains("aiohttp")
                || lower.contains("httpx")
                || lower.contains("go-http-client")
                || lower.contains("okhttp")
                || lower.contains("libwww-perl")
                || lower.contains("headlesschrome");
    }

    private static String safeHeader(RoutingContext context, String name) {
        String value = context.request().getHeader(name);
        return value == null ? "" : value.trim();
    }

    private static String shortHash(String input) {
        byte[] digest = sha256(input.getBytes(StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder(16);
        for (int i = 0; i < 8; i++) {
            sb.append(String.format("%02x", digest[i] & 0xff));
        }
        return sb.toString();
    }

    private ChallengeParts validateChallengeAndProof(String id, String proof, PowScene expectedScene) {
        ChallengeParts parts = parse(id);
        if (parts == null) {
            throw new BadRequestException("请先完成安全校验");
        }
        if (expectedScene != null && !expectedScene.scope().equals(parts.scope())) {
            throw new BadRequestException("安全校验场景不匹配，请重试");
        }
        long now = Instant.now().getEpochSecond();
        if (parts.issuedAt() > now + MAX_CLOCK_SKEW_SECONDS || now > parts.expiresAt()) {
            throw new BadRequestException("安全校验已过期，请重新提交");
        }
        if (!validProof(id, proof, parts.difficulty())) {
            throw new BadRequestException("安全校验未通过，请重试");
        }
        return parts;
    }

    private ChallengeParts parse(String id) {
        if (id == null || id.isEmpty() || id.length() > MAX_ID_LENGTH) {
            return null;
        }
        String[] parts = id.split("\\.", -1);
        if (parts.length == 6 && VERSION_V1.equals(parts[0])) {
            return parsePayload(null, parts[1], parts[2], parts[3], parts[4], parts[5],
                    String.join(".", parts[0], parts[1], parts[2], parts[3], parts[4]));
        }
        if (parts.length == 7 && VERSION_V2.equals(parts[0])) {
            if (!hasText(parts[1]) || parts[1].length() > 32) {
                return null;
            }
            return parsePayload(parts[1], parts[2], parts[3], parts[4], parts[5], parts[6],
                    String.join(".", parts[0], parts[1], parts[2], parts[3], parts[4], parts[5]));
        }
        return null;
    }

    private ChallengeParts parsePayload(String scope, String issuedAtRaw, String ttlRaw,
                                        String difficultyRaw, String nonceRaw, String signatureRaw,
                                        String payload) {
        long issuedAt;
        int ttlSeconds;
        int difficulty;
        try {
            issuedAt = Long.parseLong(issuedAtRaw);
            ttlSeconds = Integer.parseInt(ttlRaw);
            difficulty = Integer.parseInt(difficultyRaw);
        } catch (NumberFormatException exception) {
            return null;
        }
        if (issuedAt <= 0 || ttlSeconds < MIN_TTL_SECONDS || ttlSeconds > MAX_TTL_SECONDS
                || difficulty < MIN_DIFFICULTY || difficulty > MAX_DIFFICULTY) {
            return null;
        }
        byte[] nonce = decode(nonceRaw);
        if (nonce == null || nonce.length != NONCE_BYTES) {
            return null;
        }
        byte[] signature = decode(signatureRaw);
        if (signature == null || signature.length != DIGEST_BYTES) {
            return null;
        }
        if (!MessageDigest.isEqual(signature, hmac(payload))) {
            return null;
        }
        return new ChallengeParts(scope, issuedAt, difficulty, issuedAt + ttlSeconds);
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
                throw new BadRequestException("安全校验已失效，请重新提交");
            }
        } catch (BadRequestException exception) {
            throw exception;
        } catch (Exception exception) {
            LOG.debugf("PoW 单次使用标记不可用，降级放行: %s", exception.getMessage());
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

    public static int clampDifficulty(int difficulty) {
        return Math.max(MIN_DIFFICULTY, Math.min(MAX_DIFFICULTY, difficulty));
    }

    public int ttlSeconds() {
        int value = settings == null ? DEFAULT_TTL_SECONDS
                : settings.number(SettingKey.COMMENT_SECURITY, "challenge_ttl_seconds");
        return Math.max(MIN_TTL_SECONDS, Math.min(MAX_TTL_SECONDS, value));
    }

    void setLoadProbe(Supplier<SystemLoadSnapshot> loadProbe) {
        this.loadProbe = loadProbe == null ? ProofOfWorkService::readSystemLoad : loadProbe;
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
            String base = firstValidSecret(powSecret, legacyCaptchaSecret);
            if (base.isEmpty()) {
                base = eventHashSecret == null ? "" : eventHashSecret;
            }
            if (base.isEmpty()) {
                throw new ServiceUnavailableException("安全校验暂时不可用，请稍后重试");
            }
            byte[] derived = hmac(base.getBytes(StandardCharsets.UTF_8),
                    "windblog/comment-captcha/v1".getBytes(StandardCharsets.UTF_8));
            signingKey = derived;
            return derived;
        }
    }

    private String firstValidSecret(Optional<String> primary, Optional<String> secondary) {
        String p = primary == null ? "" : primary.map(String::trim).orElse("");
        if (p.length() >= 32) {
            return p;
        }
        if (!p.isEmpty()) {
            LOG.warn("WINDBLOG_POW_SECRET 长度不足 32，已尝试回退密钥");
        }
        String s = secondary == null ? "" : secondary.map(String::trim).orElse("");
        if (s.length() >= 32) {
            return s;
        }
        if (!s.isEmpty()) {
            LOG.warn("WINDBLOG_COMMENT_CAPTCHA_SECRET 长度不足 32，已回退到 SECURITY_EVENT_HASH_SECRET 派生签名密钥");
        }
        return "";
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
            throw new IllegalStateException("无法计算 PoW 签名", exception);
        }
    }

    private static byte[] sha256(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(data);
        } catch (Exception exception) {
            throw new IllegalStateException("无法计算 PoW 摘要", exception);
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

    /**
     * Generates a Qute-ready visual PoW submit button template.
     *
     * @param id       optional button DOM id
     * @param label    button label shown once PoW completes and transitions to clickable state
     * @param cssClass additional CSS classes for the button
     * @return unescaped HTML fragment for Qute templates
     */
    @TemplateExtension(namespace = "pow")
    public static RawString submitButton(String id, String label, String cssClass) {
        return submitButton(id, label, cssClass, null, null);
    }

    /**
     * Generates a Qute-ready visual PoW submit button template with optional tracking and extra attributes.
     *
     * @param id         optional button DOM id
     * @param label      button label shown once PoW completes and transitions to clickable state
     * @param cssClass   additional CSS classes for the button
     * @param seerayGoal optional {@code data-seeray-goal} attribute value
     * @param extraAttrs optional extra HTML attributes (e.g. {@code data-shortcut="enter" aria-keyshortcuts="Enter"})
     * @return unescaped HTML fragment for Qute templates
     */
    @TemplateExtension(namespace = "pow")
    public static RawString submitButton(String id, String label, String cssClass, String seerayGoal, String extraAttrs) {
        return new RawString(buildSubmitButtonHtml(id, label, cssClass, seerayGoal, extraAttrs));
    }

    public static String buildSubmitButtonHtml(String id, String label, String cssClass, String seerayGoal, String extraAttrs) {
        String safeLabel = escapeHtml(hasText(label) ? label : "提交");
        String combinedClass = hasText(cssClass)
                ? "pow-submit-btn " + cssClass.trim()
                : "pow-submit-btn";

        StringBuilder html = new StringBuilder(256);
        html.append("<button type=\"submit\"");
        if (hasText(id)) {
            html.append(" id=\"").append(escapeHtml(id.trim())).append("\"");
        }
        if (hasText(seerayGoal)) {
            html.append(" data-seeray-goal=\"").append(escapeHtml(seerayGoal.trim())).append("\"");
        }
        if (hasText(extraAttrs)) {
            html.append(' ').append(extraAttrs.trim());
        }
        html.append(" class=\"").append(escapeHtml(combinedClass)).append("\"");
        html.append(" data-pow-visual-button=\"true\" data-pow-state=\"idle\" disabled>");
        html.append("<span class=\"pow-btn-progress\" aria-hidden=\"true\"></span>");
        html.append("<span class=\"pow-btn-visual\" aria-live=\"polite\">");
        html.append("<span class=\"pow-btn-badge\">PoW 验证中</span>");
        html.append("<span class=\"pow-btn-difficulty hidden\"></span>");
        html.append("<span class=\"pow-btn-hash\">00000000…0000</span>");
        html.append("</span>");
        html.append("<span class=\"btn-text\">").append(safeLabel).append("</span>");
        html.append("<span class=\"spinner hidden\"></span>");
        html.append("</button>");
        return html.toString();
    }

    private static String escapeHtml(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder(value.length() + 8);
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            switch (ch) {
                case '&' -> out.append("&amp;");
                case '<' -> out.append("&lt;");
                case '>' -> out.append("&gt;");
                case '"' -> out.append("&quot;");
                case '\'' -> out.append("&#39;");
                default -> out.append(ch);
            }
        }
        return out.toString();
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private record ChallengeParts(String scope, long issuedAt, int difficulty, long expiresAt) {
    }

    @RegisterForReflection
    public record Challenge(String id, int difficulty, long expiresAt) {
    }

    public record SystemLoadSnapshot(double systemLoadAverage, int availableProcessors,
                                     double cpuLoad, double heapUsageRatio) {
    }

    public record ActorContext(String clientIp, String actorFingerprint,
                               String sessionFingerprint, boolean suspiciousUserAgent) {
    }

    public enum PowScene {
        REGISTER("register", 16, Duration.ofHours(1)),
        LOGIN("login", 14, Duration.ofMinutes(15)),
        COMMENT("comment", 16, Duration.ofMinutes(10)),
        REACTION("reaction", 12, Duration.ofMinutes(5)),
        GENERAL("general", 16, Duration.ofMinutes(10));

        private final String scope;
        private final int defaultBaseDifficulty;
        private final Duration burstWindow;

        PowScene(String scope, int defaultBaseDifficulty, Duration burstWindow) {
            this.scope = scope;
            this.defaultBaseDifficulty = defaultBaseDifficulty;
            this.burstWindow = burstWindow;
        }

        public String scope() {
            return scope;
        }

        public int defaultBaseDifficulty() {
            return defaultBaseDifficulty;
        }

        public Duration burstWindow() {
            return burstWindow;
        }
    }
}
