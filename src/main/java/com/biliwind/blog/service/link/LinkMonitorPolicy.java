package com.biliwind.blog.service.link;

import com.biliwind.blog.model.Link;

/** Shared defaults and visibility rules for per-link monitoring settings. */
public final class LinkMonitorPolicy {

    public static final int DEFAULT_INTERVAL_MINUTES = 60;
    public static final int MAX_INTERVAL_MINUTES = 10_080;

    private LinkMonitorPolicy() {
    }

    public static boolean isMonitoringEnabled(Link link) {
        return readBoolean(link, "monitoringEnabled", true);
    }

    public static int intervalMinutes(Link link) {
        Object value = setting(link, "monitoringIntervalMinutes");
        if (value instanceof Number number) {
            return validInterval(number.intValue());
        }
        if (value != null) {
            try {
                return validInterval(Integer.parseInt(value.toString()));
            } catch (NumberFormatException ignored) {
                // Use the default for legacy or malformed settings.
            }
        }
        return DEFAULT_INTERVAL_MINUTES;
    }

    public static boolean shouldHideWhenBacklinkMissing(Link link) {
        return readBoolean(link, "hideWhenBacklinkMissing", false);
    }

    public static boolean shouldHideWhenOffline(Link link) {
        return readBoolean(link, "hideWhenOffline", false);
    }

    public static boolean shouldHideWhenKeywordFraudDetected(Link link) {
        return readBoolean(link, "hideWhenKeywordFraudDetected", false);
    }

    public static String monitoringKeywords(Link link) {
        Object value = setting(link, "monitoringKeywords");
        return value == null ? "" : value.toString();
    }

    public static boolean isVisibleOnPublicPage(Link link) {
        if (shouldHideWhenOffline(link) && "OFFLINE".equals(link.availabilityStatus)) {
            return false;
        }
        if (shouldHideWhenBacklinkMissing(link) && "MISSING".equals(link.backlinkStatus)) {
            return false;
        }
        return !shouldHideWhenKeywordFraudDetected(link) || !"DETECTED".equals(link.keywordFraudStatus);
    }

    public static int validInterval(int minutes) {
        if (minutes < 1 || minutes > MAX_INTERVAL_MINUTES) {
            return DEFAULT_INTERVAL_MINUTES;
        }
        return minutes;
    }

    private static boolean readBoolean(Link link, String key, boolean defaultValue) {
        Object value = setting(link, key);
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value != null) {
            return Boolean.parseBoolean(value.toString());
        }
        return defaultValue;
    }

    private static Object setting(Link link, String key) {
        return link == null || link.settings == null ? null : link.settings.get(key);
    }
}
