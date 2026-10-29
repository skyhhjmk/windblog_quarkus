package com.biliwind.blog.service.ai;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Structural check for AI translations. The goal is not linguistic quality but
 * catching responses that dropped a code block, rewrote a URL or collapsed the
 * article structure. A translation that fails these checks must not overwrite
 * a published revision.
 */
public final class MarkdownTranslationGuard {

    private static final Pattern FENCED_CODE = Pattern.compile("(?s)```[^\\n]*\\n(.*?)```");
    private static final Pattern INLINE_CODE = Pattern.compile("`[^`\\n]+`");
    private static final Pattern LINK_URL = Pattern.compile("\\]\\(([^)\\s]+)(?:\\s+\"[^\"]*\")?\\)");
    private static final Pattern BARE_URL = Pattern.compile("https?://[^\\s<>()\\[\\]{}\"'`]+");
    private static final Pattern HTML_TAG = Pattern.compile("</?[a-zA-Z][^>]*>");
    private static final Pattern SHORTCODE = Pattern.compile("\\[/?\\s*(?:hide-text|hide-attachment)[^\\]]*\\]",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern HEADING = Pattern.compile("(?m)^(#{1,6})\\s+\\S.*$");
    private static final Pattern IMAGE = Pattern.compile("!\\[[^\\]]*\\]\\([^)]*\\)");

    private MarkdownTranslationGuard() {
    }

    public static List<String> verify(String source, String translated) {
        List<String> issues = new ArrayList<>();
        if (source == null || source.isBlank()) {
            return issues;
        }
        if (translated == null || translated.isBlank()) {
            issues.add("译文为空");
            return issues;
        }

        List<String> sourceCode = captures(FENCED_CODE, source, 1);
        List<String> translatedCode = captures(FENCED_CODE, translated, 1);
        if (sourceCode.size() != translatedCode.size()) {
            issues.add("代码块数量不一致：原文 " + sourceCode.size() + "，译文 " + translatedCode.size());
        } else {
            for (int index = 0; index < sourceCode.size(); index++) {
                if (!sourceCode.get(index).equals(translatedCode.get(index))) {
                    issues.add("第 " + (index + 1) + " 个代码块内容被改动");
                    break;
                }
            }
        }

        if (!captures(INLINE_CODE, source, 0).equals(captures(INLINE_CODE, translated, 0))) {
            issues.add("行内代码被改动");
        }
        if (!linkUrls(source).equals(linkUrls(translated))) {
            issues.add("链接或图片 URL 被改动");
        }
        if (!captures(SHORTCODE, source, 0).equals(captures(SHORTCODE, translated, 0))) {
            issues.add("隐藏内容短代码被改动");
        }
        if (!headingLevels(source).equals(headingLevels(translated))) {
            issues.add("标题层级结构被改动");
        }
        if (count(IMAGE, source) != count(IMAGE, translated)) {
            issues.add("图片数量被改动");
        }
        if (count(HTML_TAG, source) != count(HTML_TAG, translated)) {
            issues.add("HTML 标签数量被改动");
        }
        if (source.trim().equals(translated.trim())) {
            issues.add("译文与原文相同");
        }
        return issues;
    }

    private static List<String> captures(Pattern pattern, String value, int group) {
        List<String> result = new ArrayList<>();
        Matcher matcher = pattern.matcher(value == null ? "" : value);
        while (matcher.find()) {
            result.add(group == 0 ? matcher.group() : matcher.group(group));
        }
        return result;
    }

    private static List<String> linkUrls(String value) {
        List<String> urls = new ArrayList<>(captures(LINK_URL, value, 1));
        urls.addAll(captures(BARE_URL, value, 0));
        urls.sort(String::compareTo);
        return urls;
    }

    private static List<Integer> headingLevels(String value) {
        List<Integer> levels = new ArrayList<>();
        Matcher matcher = HEADING.matcher(value == null ? "" : value);
        while (matcher.find()) {
            levels.add(matcher.group(1).length());
        }
        return levels;
    }

    private static int count(Pattern pattern, String value) {
        int total = 0;
        Matcher matcher = pattern.matcher(value == null ? "" : value);
        while (matcher.find()) {
            total++;
        }
        return total;
    }
}
