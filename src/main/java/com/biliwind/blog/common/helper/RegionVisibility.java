package com.biliwind.blog.common.helper;

import com.biliwind.blog.common.exception.BadRequestException;
import com.biliwind.blog.model.BlogRegion;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 文章/媒体读者可见性规则。存储值为 null、空数组或包含 global 时全区域可见，
 * 否则仅列表中列出的区域可见。所有读取路径都使用严格小写的区域 code。
 */
public final class RegionVisibility {

    private static final String REGION_PARAMETER = "regionPattern";

    private RegionVisibility() {
    }

    public static boolean isVisible(List<String> visibilityRegions, BlogRegion currentRegion) {
        if (visibilityRegions == null || visibilityRegions.isEmpty()) {
            return true;
        }
        String regionCode = regionCode(currentRegion);
        for (String stored : visibilityRegions) {
            if (stored == null) {
                continue;
            }
            if (BlogRegion.GLOBAL.getCode().equals(stored) || regionCode.equals(stored)) {
                return true;
            }
        }
        return false;
    }

    public static NamedFilter jpqlFilter(BlogRegion currentRegion) {
        return new NamedFilter(jpqlFragment(":" + REGION_PARAMETER), regionPattern(currentRegion));
    }

    public static PositionalFilter jpqlFilter(int parameterIndex, BlogRegion currentRegion) {
        return new PositionalFilter(jpqlFragment("?" + parameterIndex), regionPattern(currentRegion));
    }

    public static NamedFilter nativeSqlFilter(String columnExpression, BlogRegion currentRegion) {
        String textColumn = columnExpression + "::text";
        String fragment = "(" + columnExpression + " is null or " + textColumn + " = '[]' or "
                + textColumn + " like :" + REGION_PARAMETER + " or "
                + textColumn + " like '%\"global\"%')";
        return new NamedFilter(fragment, regionPattern(currentRegion));
    }

    public static String elasticsearchFilter(BlogRegion currentRegion) {
        return "{\"bool\":{\"minimum_should_match\":1,\"should\":["
                + "{\"bool\":{\"must_not\":{\"exists\":{\"field\":\"visibilityRegions\"}}}},"
                + "{\"term\":{\"visibilityRegions\":\"" + escapeJson(regionCode(currentRegion)) + "\"}},"
                + "{\"term\":{\"visibilityRegions\":\"global\"}}]}}";
    }

    public static List<String> normalizeForWrite(List<String> regions) {
        if (regions == null || regions.isEmpty()) {
            return null;
        }
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String raw : regions) {
            if (raw == null || raw.isBlank()) {
                continue;
            }
            String code = knownCode(raw);
            if (code == null) {
                throw new BadRequestException("未知的区域代码：" + raw.trim()
                        + "，可选值：global、cn、us、eu、jp、hk、tw");
            }
            normalized.add(code);
        }
        if (normalized.isEmpty() || normalized.contains(BlogRegion.GLOBAL.getCode())) {
            return null;
        }
        return List.copyOf(normalized);
    }

    public static boolean isKnownRegion(String regionCode) {
        return regionCode != null && !regionCode.isBlank() && knownCode(regionCode) != null;
    }

    private static String jpqlFragment(String regionParameter) {
        return "(visibilityRegions is null or cast(visibilityRegions as String) = '[]' or "
                + "cast(visibilityRegions as String) like " + regionParameter + " or "
                + "cast(visibilityRegions as String) like '%\"global\"%')";
    }

    private static String regionPattern(BlogRegion currentRegion) {
        return "%\"" + regionCode(currentRegion) + "\"%";
    }

    private static String regionCode(BlogRegion currentRegion) {
        return currentRegion == null ? BlogRegion.GLOBAL.getCode() : currentRegion.getCode();
    }

    private static String knownCode(String raw) {
        String candidate = raw.trim().toLowerCase(Locale.ROOT);
        if ("china".equals(candidate)) {
            return BlogRegion.CN.getCode();
        }
        for (BlogRegion region : BlogRegion.values()) {
            if (region.getCode().equals(candidate)) {
                return region.getCode();
            }
        }
        return null;
    }

    private static String escapeJson(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    public record NamedFilter(String fragment, String regionPattern) {
        public Map<String, Object> parameters() {
            return Map.of(REGION_PARAMETER, regionPattern);
        }
    }

    public record PositionalFilter(String fragment, String regionPattern) {
    }
}
