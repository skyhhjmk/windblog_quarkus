package com.biliwind.blog.service;

import com.biliwind.blog.model.Media;
import com.biliwind.blog.model.User;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Locale;
import java.util.regex.Pattern;

/** Decodes image data URLs used by legacy content and stores them as managed media. */
@ApplicationScoped
public class EmbeddedDataImageService {
    public static final long MAX_BYTES = 16L * 1024L * 1024L;
    public static final Pattern DATA_IMAGE_PATTERN = Pattern.compile(
            "(?i)data:image/[a-z0-9.+-]+(?:;[a-z0-9=._+-]+)*,[^\\s\"'<>)]*");

    @Inject
    MediaManagementService mediaService;

    public Media importImage(String dataUrl, User operator) throws IOException {
        ParsedImage image = parse(dataUrl);
        return mediaService.storeUploadedMedia(operator,
                new java.io.ByteArrayInputStream(image.bytes()), image.fileName(), image.mimeType(), image.bytes().length);
    }

    public static ParsedImage parse(String dataUrl) {
        if (dataUrl == null || !dataUrl.regionMatches(true, 0, "data:", 0, 5)) {
            throw new IllegalArgumentException("不是 Data URL");
        }
        int comma = dataUrl.indexOf(',');
        if (comma <= 5) throw new IllegalArgumentException("Data URL 缺少数据内容");
        String metadata = dataUrl.substring(5, comma);
        String payload = dataUrl.substring(comma + 1);
        String[] parts = metadata.split(";");
        String mime = parts[0].trim().toLowerCase(Locale.ROOT);
        if (!mime.startsWith("image/") || mime.length() > 100) {
            throw new IllegalArgumentException("仅支持 image/* Data URL");
        }
        boolean base64 = false;
        for (int i = 1; i < parts.length; i++) {
            if ("base64".equalsIgnoreCase(parts[i].trim())) base64 = true;
        }
        byte[] bytes;
        try {
            if (base64) {
                bytes = Base64.getMimeDecoder().decode(payload);
            } else {
                // URLDecoder treats '+' as a form-space; Data URLs must preserve it.
                bytes = URLDecoder.decode(payload.replace("+", "%2B"), StandardCharsets.UTF_8)
                        .getBytes(StandardCharsets.UTF_8);
            }
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Data URL 编码无效");
        }
        if (bytes.length == 0) throw new IllegalArgumentException("Data URL 内容为空");
        if (bytes.length > MAX_BYTES) throw new IllegalArgumentException("单个内嵌图片超过 16 MB 限制");
        if ("image/svg+xml".equals(mime) || "image/svg".equals(mime)) {
            bytes = sanitizeSvg(bytes);
            mime = "image/svg+xml";
        }
        String extension = extensionFor(mime);
        return new ParsedImage(mime, extension, bytes, sha256(bytes));
    }

    private static byte[] sanitizeSvg(byte[] source) {
        String svg = new String(source, StandardCharsets.UTF_8);
        if (!svg.trim().startsWith("<")) throw new IllegalArgumentException("SVG 内容无效");
        svg = svg.replaceAll("(?is)<\\s*script[^>]*>.*?<\\s*/\\s*script\\s*>", "")
                .replaceAll("(?is)\\s+on[a-z0-9_-]+\\s*=\\s*(?:\"[^\"]*\"|'[^']*'|[^\\s>]+)", "")
                .replaceAll("(?is)(?:href|xlink:href)\\s*=\\s*(?:\"|')\\s*javascript:[^\"']*(?:\"|')", "")
                .replaceAll("(?is)<!DOCTYPE[^>]*>", "")
                .replaceAll("(?is)<\\?xml[^>]*\\?>", "");
        byte[] result = svg.getBytes(StandardCharsets.UTF_8);
        if (result.length > MAX_BYTES) throw new IllegalArgumentException("SVG 清理后超过 16 MB 限制");
        return result;
    }

    private static String extensionFor(String mime) {
        return switch (mime) {
            case "image/jpeg", "image/jpg" -> "jpg";
            case "image/png" -> "png";
            case "image/gif" -> "gif";
            case "image/webp" -> "webp";
            case "image/svg+xml", "image/svg" -> "svg";
            case "image/bmp" -> "bmp";
            case "image/tiff" -> "tiff";
            case "image/avif" -> "avif";
            case "image/x-icon", "image/vnd.microsoft.icon" -> "ico";
            default -> mime.substring("image/".length()).replaceAll("[^a-z0-9]+", "");
        };
    }

    private static String sha256(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder result = new StringBuilder(digest.length * 2);
            for (byte value : digest) result.append(String.format("%02x", value));
            return result.toString();
        } catch (Exception exception) {
            throw new IllegalStateException("无法计算内嵌图片摘要", exception);
        }
    }

    public record ParsedImage(String mimeType, String extension, byte[] bytes, String sha256) {
        public String fileName() { return "import-embedded-" + sha256 + "." + extension; }
    }
}
