package com.biliwind.blog.common.helper;

import java.util.HashMap;
import java.util.Map;

/**
 * Media security helper for Magic Number (File Header) validation
 */
public class MediaSecurityHelper {

    private static final Map<String, String[]> MAGIC_NUMBERS = new HashMap<>();

    static {
        // JPEG: FF D8 FF
        MAGIC_NUMBERS.put("image/jpeg", new String[]{"FFD8FF"});
        // PNG: 89 50 4E 47 0D 0A 1A 0A
        MAGIC_NUMBERS.put("image/png", new String[]{"89504E470D0A1A0A"});
        // GIF: 47 49 46 38
        MAGIC_NUMBERS.put("image/gif", new String[]{"47494638"});
        // WEBP: 52 49 46 46 (RIFF) ... 57 45 42 50 (WEBP)
        MAGIC_NUMBERS.put("image/webp", new String[]{"52494646"});
        // MP4: 00 00 00 .. 66 74 79 70 69 73 6F 6D
        MAGIC_NUMBERS.put("video/mp4", new String[]{"000000"});
        // PDF: 25 50 44 46
        MAGIC_NUMBERS.put("application/pdf", new String[]{"25504446"});
        // ZIP: 50 4B 03 04
        MAGIC_NUMBERS.put("application/zip", new String[]{"504B0304"});
    }

    /**
     * Validate file header against expected MIME type
     *
     * @param head     First few bytes of the file
     * @param mimeType Expected MIME type
     * @return true if valid or unknown MIME type
     */
    public static boolean validateMagicNumber(byte[] head, String mimeType) {
        if (head == null || head.length == 0 || mimeType == null) {
            return true;
        }

        String[] expectedHexes = MAGIC_NUMBERS.get(mimeType.toLowerCase());
        if (expectedHexes == null) {
            // Unknown MIME type, skip validation for now or implement stricter policy
            return true;
        }

        String actualHex = bytesToHex(head).toUpperCase();
        for (String expected : expectedHexes) {
            if (actualHex.startsWith(expected.toUpperCase())) {
                return true;
            }
        }

        return false;
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02X", b));
        }
        return sb.toString();
    }

    /**
     * 检查 URL 是否指向私有 IP 或本地环回地址（防止 SSRF 漏洞）
     *
     * @param urlString 目标 URL
     * @return 如果是私有或本地环回地址返回 true，否则返回 false
     */
    public static boolean isPrivateOrLoopbackAddress(String urlString) {
        if (urlString == null || urlString.isBlank()) {
            return true;
        }
        try {
            java.net.URI uri = java.net.URI.create(urlString);
            String host = uri.getHost();
            if (host == null || host.isBlank()) {
                return true;
            }
            if ("localhost".equalsIgnoreCase(host.trim())) {
                return true;
            }
            java.net.InetAddress[] addresses = java.net.InetAddress.getAllByName(host);
            for (java.net.InetAddress address : addresses) {
                if (address.isLoopbackAddress()) {
                    return true;
                }
                if (address.isSiteLocalAddress()) {
                    return true;
                }
                if (address.isLinkLocalAddress()) {
                    return true;
                }
                byte[] addressBytes = address.getAddress();
                if (addressBytes.length == 4) {
                    int firstOctet = addressBytes[0] & 0xFF;
                    if (firstOctet == 0) {
                        return true;
                    }
                }
            }
            return false;
        } catch (Exception exception) {
            return true;
        }
    }
}
