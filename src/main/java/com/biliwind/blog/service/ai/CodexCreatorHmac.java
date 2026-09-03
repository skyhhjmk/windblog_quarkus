package com.biliwind.blog.service.ai;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

/** Shared HMAC primitives for the private Codex Creator inbound boundary. */
public final class CodexCreatorHmac {
    public static final String CLIENT_ID = "codex-creator";

    private CodexCreatorHmac() {
    }

    public static String bodyDigest(String body) {
        return HexFormat.of().formatHex(sha256(body == null ? "" : body));
    }

    public static String sign(String secret, String clientId, String timestamp,
                              String nonce, String bodyDigest) {
        String canonical = timestamp + "\n" + nonce + "\n" + bodyDigest + "\n" + clientId;
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("无法生成 Codex Creator 请求签名", exception);
        }
    }

    public static boolean constantTimeEquals(String expected, String actual) {
        if (expected == null || actual == null) {
            return false;
        }
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII),
                actual.getBytes(StandardCharsets.US_ASCII));
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (Exception exception) {
            throw new IllegalStateException("无法计算 Codex Creator 请求摘要", exception);
        }
    }
}
