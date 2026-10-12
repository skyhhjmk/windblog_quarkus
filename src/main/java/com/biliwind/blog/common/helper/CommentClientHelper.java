package com.biliwind.blog.common.helper;

import java.util.Locale;

/** Coarse client labels only; the raw User-Agent is neither stored nor exposed. */
public final class CommentClientHelper {
    private CommentClientHelper() {}

    public static String operatingSystem(String userAgent) {
        String ua = normalized(userAgent);
        if (ua.contains("windows")) return "Windows";
        if (ua.contains("android")) return "Android";
        if (ua.contains("iphone") || ua.contains("ipad") || ua.contains("ipod")) return "iOS";
        if (ua.contains("macintosh") || ua.contains("mac os x")) return "macOS";
        if (ua.contains("cros")) return "ChromeOS";
        if (ua.contains("linux")) return "Linux";
        return "Unknown";
    }

    public static String browser(String userAgent) {
        String ua = normalized(userAgent);
        if (ua.contains("edg/") || ua.contains("edga/") || ua.contains("edgios/") || ua.contains("edge/")) return "Edge";
        if (ua.contains("opr/") || ua.contains("opera") || ua.contains("opios/")) return "Opera";
        if (ua.contains("firefox/") || ua.contains("fxios/")) return "Firefox";
        if (ua.contains("chrome/") || ua.contains("crios/")) return "Chrome";
        if (ua.contains("safari/")) return "Safari";
        if (ua.contains("msie") || ua.contains("trident/")) return "Internet Explorer";
        return "Unknown";
    }

    private static String normalized(String userAgent) {
        return userAgent == null ? "" : userAgent.toLowerCase(Locale.ROOT);
    }
}
