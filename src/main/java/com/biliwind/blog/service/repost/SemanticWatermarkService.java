package com.biliwind.blog.service.repost;

import jakarta.enterprise.context.ApplicationScoped;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Map;

/**
 * 第一版语义水印只生成稳定 slot 配置，不做 AI 改写。
 */
@ApplicationScoped
public class SemanticWatermarkService {

    public Map<String, Object> buildWatermarkConfig(Long userId, Long postId, String allowedDomain) {
        String seedText = String.valueOf(userId) + ":" + String.valueOf(postId) + ":" + allowedDomain;
        String fingerprint = sha256(seedText);
        Map<String, Object> config = new HashMap<>();
        config.put("fingerprint", fingerprint.substring(0, 16));
        config.put("argumentOrderSlot", resolveSlot(fingerprint, 0, 3));
        config.put("exampleVariantSlot", resolveSlot(fingerprint, 2, 4));
        config.put("phraseVariantSlot", resolveSlot(fingerprint, 4, 5));
        return config;
    }

    private int resolveSlot(String fingerprint, int startIndex, int modulo) {
        String hexPart = fingerprint.substring(startIndex, startIndex + 2);
        int number = Integer.parseInt(hexPart, 16);
        return number % modulo;
    }

    private String sha256(String text) {
        try {
            MessageDigest messageDigest = MessageDigest.getInstance("SHA-256");
            byte[] digestBytes = messageDigest.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder();
            for (byte digestByte : digestBytes) {
                builder.append(String.format("%02x", digestByte));
            }
            return builder.toString();
        } catch (Exception exception) {
            return "0000000000000000000000000000000000000000000000000000000000000000";
        }
    }
}
