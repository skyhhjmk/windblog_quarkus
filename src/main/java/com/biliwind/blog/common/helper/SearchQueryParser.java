package com.biliwind.blog.common.helper;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 解析带有高级检索过滤器的查询字符串（类似 GitHub 搜索语法）。
 * 支持形如 title:xxx、content:xxx、author:xxx、user:xxx 及混合自由文本。
 */
public record SearchQueryParser(
        String rawQuery,
        String freeText,
        String title,
        String content,
        String author,
        String user
) {
    private static final Pattern FILTER_PATTERN =
            Pattern.compile("(?i)(title|content|author|user):(\"[^\"]+\"|'[^']+'|\\S+)");

    public static SearchQueryParser parse(String input) {
        if (input == null || input.isBlank()) {
            return new SearchQueryParser("", "", null, null, null, null);
        }
        String title = null;
        String content = null;
        String author = null;
        String user = null;
        List<String> remainingTokens = new ArrayList<>();

        Matcher matcher = FILTER_PATTERN.matcher(input);
        int lastEnd = 0;
        while (matcher.find()) {
            if (matcher.start() > lastEnd) {
                String segment = input.substring(lastEnd, matcher.start()).trim();
                if (!segment.isEmpty()) {
                    remainingTokens.add(segment);
                }
            }
            String key = matcher.group(1).toLowerCase(Locale.ROOT);
            String val = matcher.group(2).trim();
            if ((val.startsWith("\"") && val.endsWith("\"")) || (val.startsWith("'") && val.endsWith("'"))) {
                val = val.substring(1, val.length() - 1);
            }
            switch (key) {
                case "title" -> title = val.trim();
                case "content" -> content = val.trim();
                case "author" -> author = val.trim();
                case "user" -> user = val.trim();
            }
            lastEnd = matcher.end();
        }
        if (lastEnd < input.length()) {
            String tail = input.substring(lastEnd).trim();
            if (!tail.isEmpty()) {
                remainingTokens.add(tail);
            }
        }

        String freeText = String.join(" ", remainingTokens).trim();
        return new SearchQueryParser(input, freeText, title, content, author, user);
    }

    public boolean hasFilters() {
        return title != null || content != null || author != null || user != null;
    }
}
