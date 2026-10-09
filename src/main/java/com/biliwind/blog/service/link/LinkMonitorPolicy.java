package com.biliwind.blog.service.link;

import com.biliwind.blog.model.BlogRegion;
import com.biliwind.blog.model.Link;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Shared defaults and visibility rules for per-link monitoring settings. */
public final class LinkMonitorPolicy {

    public static final int DEFAULT_INTERVAL_MINUTES = 60;
    public static final int MAX_INTERVAL_MINUTES = 10_080;
    public static final int DEFAULT_GRACE_DAYS = 7;
    public static final int MAX_GRACE_DAYS = 365;
    public static final int REQUIRED_CONSECUTIVE_FAILURES = 3;

    public static final String BACKLINK_MISSING = "backlinkMissing";
    public static final String OFFLINE = "offline";
    public static final String KEYWORD_FRAUD = "keywordFraud";

    private LinkMonitorPolicy() {
    }

    public static boolean isMonitoringEnabled(Link link) {
        return readBoolean(link, "monitoringEnabled", true);
    }

    public static int intervalMinutes(Link link) {
        return readInt(link, "monitoringIntervalMinutes", DEFAULT_INTERVAL_MINUTES, 1, MAX_INTERVAL_MINUTES);
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

    public static boolean shouldNotify(Link link, String condition) {
        return readBoolean(link, notifySettingKey(condition), false);
    }

    public static int graceDays(Link link, String condition) {
        return readInt(link, graceSettingKey(condition), DEFAULT_GRACE_DAYS, 0, MAX_GRACE_DAYS);
    }

    public static String monitoringKeywords(Link link) {
        Object value = setting(link, "monitoringKeywords");
        return value == null ? "" : value.toString();
    }

    public static List<String> backlinkCheckUrls(Link link) {
        Object value = setting(link, "backlinkCheckUrls");
        if (value instanceof List<?> list) {
            return list.stream().filter(String.class::isInstance).map(String.class::cast)
                    .map(String::trim).filter(item -> !item.isBlank()).toList();
        }
        if (value instanceof String text && !text.isBlank()) {
            return List.of(text.split("[,，;；\\r\\n]+"));
        }
        return List.of();
    }

    public static BlogRegion displayRegion(Link link) {
        Object value = setting(link, "displayRegion");
        return value == null ? BlogRegion.GLOBAL : BlogRegion.fromCode(value.toString());
    }

    public static boolean isVisibleOnPublicPage(Link link) {
        if (shouldHideWhenOffline(link) && isHidden(link, OFFLINE)) {
            return false;
        }
        if (shouldHideWhenBacklinkMissing(link) && isHidden(link, BACKLINK_MISSING)) {
            return false;
        }
        return !shouldHideWhenKeywordFraudDetected(link) || !isHidden(link, KEYWORD_FRAUD);
    }

    public static boolean isVisibleInRegion(Link link, BlogRegion currentRegion) {
        BlogRegion assignedRegion = displayRegion(link);
        return assignedRegion == BlogRegion.GLOBAL || assignedRegion == currentRegion;
    }

    public static String publicMonitorLabel(Link link) {
        if (shouldHideWhenOffline(link) && isBuffering(link, OFFLINE)) {
            return "无法访问";
        }
        if ((shouldHideWhenBacklinkMissing(link) && isBuffering(link, BACKLINK_MISSING))
                || (shouldHideWhenKeywordFraudDetected(link) && isBuffering(link, KEYWORD_FRAUD))) {
            return "异常";
        }
        return "";
    }

    public static String autoHideMessage(Link link) {
        for (String condition : List.of(OFFLINE, BACKLINK_MISSING, KEYWORD_FRAUD)) {
            if (!isHideEnabled(link, condition) || !isBuffering(link, condition)) {
                continue;
            }
            long days = remainingDays(lifecycleState(link, condition));
            String reason = switch (condition) {
                case OFFLINE -> "无法访问";
                case BACKLINK_MISSING -> "未检测到反链";
                default -> "检测到关键词欺诈";
            };
            return reason + " - " + (days <= 0 ? "即将" : days + " 天后") + "自动隐藏";
        }
        return "";
    }

    public static boolean isHideEnabled(Link link, String condition) {
        return switch (condition) {
            case OFFLINE -> shouldHideWhenOffline(link);
            case BACKLINK_MISSING -> shouldHideWhenBacklinkMissing(link);
            case KEYWORD_FRAUD -> shouldHideWhenKeywordFraudDetected(link);
            default -> false;
        };
    }

    public static Map<String, Object> lifecycleState(Link link, String condition) {
        Object lifecycle = setting(link, "autoHideLifecycle");
        if (!(lifecycle instanceof Map<?, ?> lifecycleMap)) {
            return new HashMap<>();
        }
        Object rawState = lifecycleMap.get(condition);
        if (!(rawState instanceof Map<?, ?> stateMap)) {
            return new HashMap<>();
        }
        Map<String, Object> result = new HashMap<>();
        stateMap.forEach((key, value) -> {
            if (key instanceof String stringKey) {
                result.put(stringKey, value);
            }
        });
        return result;
    }

    public static void updateLifecycleState(Link link, String condition, Map<String, Object> state) {
        Map<String, Object> settings = link.settings == null ? new HashMap<>() : new HashMap<>(link.settings);
        Map<String, Object> lifecycle = new HashMap<>();
        Object oldLifecycle = settings.get("autoHideLifecycle");
        if (oldLifecycle instanceof Map<?, ?> values) {
            values.forEach((key, value) -> {
                if (key instanceof String stringKey) {
                    lifecycle.put(stringKey, value);
                }
            });
        }
        lifecycle.put(condition, new HashMap<>(state));
        settings.put("autoHideLifecycle", lifecycle);
        link.settings = settings;
    }

    public static OffsetDateTime graceEndsAt(Map<String, Object> state) {
        Object value = state.get("graceEndsAt");
        if (value == null) {
            return null;
        }
        try {
            return OffsetDateTime.parse(value.toString());
        } catch (Exception ignored) {
            return null;
        }
    }

    private static boolean isHidden(Link link, String condition) {
        Map<String, Object> state = lifecycleState(link, condition);
        if ("HIDDEN".equals(state.get("phase"))) {
            return true;
        }
        OffsetDateTime endsAt = graceEndsAt(state);
        return "BUFFERING".equals(state.get("phase")) && endsAt != null
                && !endsAt.isAfter(OffsetDateTime.now(ZoneOffset.UTC));
    }

    private static boolean isBuffering(Link link, String condition) {
        Map<String, Object> state = lifecycleState(link, condition);
        OffsetDateTime endsAt = graceEndsAt(state);
        return "BUFFERING".equals(state.get("phase")) && endsAt != null
                && endsAt.isAfter(OffsetDateTime.now(ZoneOffset.UTC));
    }

    private static long remainingDays(Map<String, Object> state) {
        OffsetDateTime endsAt = graceEndsAt(state);
        if (endsAt == null) {
            return 0;
        }
        long remainingSeconds = java.time.Duration.between(OffsetDateTime.now(ZoneOffset.UTC), endsAt).getSeconds();
        return remainingSeconds <= 0 ? 0 : (remainingSeconds + 86_399) / 86_400;
    }

    private static String notifySettingKey(String condition) {
        return switch (condition) {
            case OFFLINE -> "notifyOnOffline";
            case BACKLINK_MISSING -> "notifyOnBacklinkMissing";
            case KEYWORD_FRAUD -> "notifyOnKeywordFraud";
            default -> "";
        };
    }

    private static String graceSettingKey(String condition) {
        return switch (condition) {
            case OFFLINE -> "offlineGraceDays";
            case BACKLINK_MISSING -> "backlinkMissingGraceDays";
            case KEYWORD_FRAUD -> "keywordFraudGraceDays";
            default -> "";
        };
    }

    private static int readInt(Link link, String key, int defaultValue, int min, int max) {
        Object value = setting(link, key);
        int parsed;
        if (value instanceof Number number) {
            parsed = number.intValue();
        } else if (value != null) {
            try {
                parsed = Integer.parseInt(value.toString());
            } catch (NumberFormatException ignored) {
                return defaultValue;
            }
        } else {
            return defaultValue;
        }
        return parsed < min || parsed > max ? defaultValue : parsed;
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
