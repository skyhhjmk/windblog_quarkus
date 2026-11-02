package com.biliwind.blog.common.helper;

import com.biliwind.blog.common.constant.LanguageConstant;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 文章搜索权重计算工具类
 *
 * 权重计算规则：
 * 1. 文章基础搜索权重为 0；
 * 2. 标题中出现关键字的权重更高（每出现一次权重 +100）；
 * 3. 正文中首次出现关键词出现位置在正文中越靠前的权重越高：
 *    - 前 20% 出现：+40 权重
 *    - 前 40% 出现：+30 权重
 *    - 前 60% 出现：+20 权重
 *    - 前 80% 出现：+10 权重
 *    - 前 80% 都没有（>= 80% 或未出现）：不加权重（+0）
 * 4. 正文中出现关键词次数越多权重越高，出现一次关键字权重 +5；
 * 5. 位置计算与出现次数计算互不冲突，两者累加；
 * 6. 多语言与跨语言搜索支持：
 *    - 优先匹配当前语言：当前语言（如 en-US / zh-CN）命中时，额外赋予当前语言匹配加成 (+500)，
 *      确保当前语言相关的文章排在前面，避免搜英文标题搜不出来；
 *    - 支持跨语言检索：若当前语言未命中但其他语言版本命中，依然返回有效权重并保留被检索出的能力；
 *    - 当当前语言命中且其他语言也命中时，附加微量多语言加成（最高 +50），综合排序更优。
 */
public final class SearchWeightHelper {

    public static final int BASE_WEIGHT = 0;
    public static final int TITLE_OCCURRENCE_WEIGHT = 100;
    public static final int CONTENT_FIRST_OCCURRENCE_P20_WEIGHT = 40;
    public static final int CONTENT_FIRST_OCCURRENCE_P40_WEIGHT = 30;
    public static final int CONTENT_FIRST_OCCURRENCE_P60_WEIGHT = 20;
    public static final int CONTENT_FIRST_OCCURRENCE_P80_WEIGHT = 10;
    public static final int CONTENT_OCCURRENCE_WEIGHT = 5;

    /** 当前语言匹配额外提权加成 */
    public static final int CURRENT_LANG_MATCH_BOOST = 500;

    private SearchWeightHelper() {
    }

    /**
     * 计算文章针对多语言场景与当前语言上下文的综合搜索权重
     *
     * @param titles      文章各语言标题映射（如 zh-cn -> 中文标题, en-us -> 英文标题）
     * @param contents    文章各语言正文可搜索纯文本映射
     * @param keyword     搜索关键字
     * @param currentLang 当前请求上下文语言（如 zh-CN, en-US）
     * @return 最终综合搜索权重
     */
    public static int calculateMultilingualWeight(
            Map<String, String> titles,
            Map<String, String> contents,
            String keyword,
            String currentLang) {
        if (keyword == null || keyword.isBlank()) {
            return BASE_WEIGHT;
        }
        List<String> terms = extractTerms(keyword);
        return calculateMultilingualWeightByTerms(titles, contents, terms, currentLang);
    }

    /**
     * 计算文章针对多语言场景与分词项列表的综合搜索权重
     *
     * @param titles      文章各语言标题映射
     * @param contents    文章各语言正文纯文本映射
     * @param terms       分词后的搜索关键词列表
     * @param currentLang 当前上下文语言
     * @return 最终综合搜索权重
     */
    public static int calculateMultilingualWeightByTerms(
            Map<String, String> titles,
            Map<String, String> contents,
            List<String> terms,
            String currentLang) {
        if (terms == null || terms.isEmpty()) {
            return BASE_WEIGHT;
        }

        String normalizedCurrentLang = LanguageHelper.normalizeToSupportedLang(currentLang);
        if (normalizedCurrentLang == null) {
            normalizedCurrentLang = LanguageConstant.DEFAULT_LANG;
        }

        Set<String> allLangs = new LinkedHashSet<>();
        if (titles != null) {
            allLangs.addAll(titles.keySet());
        }
        if (contents != null) {
            allLangs.addAll(contents.keySet());
        }

        // 1. 严格计算当前语言下的匹配权重
        String currentTitle = titles != null ? findMatchingLanguageValue(titles, normalizedCurrentLang) : null;
        String currentContent = contents != null ? findMatchingLanguageValue(contents, normalizedCurrentLang) : null;
        int currentLangWeight = 0;
        if ((currentTitle != null && !currentTitle.isBlank()) || (currentContent != null && !currentContent.isBlank())) {
            currentLangWeight = calculateWeightByTerms(currentTitle, currentContent, terms);
        }

        // 2. 遍历计算其他语言版本的最高匹配权重
        int maxOtherLangWeight = 0;
        for (String langKey : allLangs) {
            if (langKey == null || langKey.isBlank()) {
                continue;
            }
            String normKey = LanguageHelper.normalizeToSupportedLang(langKey);
            if (normKey != null && normKey.equalsIgnoreCase(normalizedCurrentLang)) {
                continue;
            }
            if (langKey.equalsIgnoreCase(normalizedCurrentLang)) {
                continue;
            }

            String otherTitle = titles != null ? titles.get(langKey) : null;
            String otherContent = contents != null ? contents.get(langKey) : null;
            int otherWeight = calculateWeightByTerms(otherTitle, otherContent, terms);
            if (otherWeight > maxOtherLangWeight) {
                maxOtherLangWeight = otherWeight;
            }
        }

        // 3. 优先匹配当前语言：当前语言命中赋大加成；若只有其他语言命中，作为跨语言搜索保留其权重
        if (currentLangWeight > 0) {
            int otherBonus = maxOtherLangWeight > 0 ? Math.min(50, (int) (maxOtherLangWeight * 0.1)) : 0;
            return CURRENT_LANG_MATCH_BOOST + currentLangWeight + otherBonus;
        } else if (maxOtherLangWeight > 0) {
            return maxOtherLangWeight;
        } else {
            return BASE_WEIGHT;
        }
    }

    /**
     * 严格按目标语言（及其变体/别名）从多语言 Map 中查找对应内容，不 fallback 到其他语言
     */
    public static String findMatchingLanguageValue(Map<String, String> localized, String language) {
        if (localized == null || localized.isEmpty() || language == null) {
            return null;
        }
        String direct = LanguageHelper.lookupIgnoreCase(localized, language);
        if (direct != null && !direct.isBlank()) {
            return direct;
        }
        String normalized = LanguageHelper.normalizeToSupportedLang(language);
        if (normalized != null && !normalized.equalsIgnoreCase(language)) {
            direct = LanguageHelper.lookupIgnoreCase(localized, normalized);
            if (direct != null && !direct.isBlank()) {
                return direct;
            }
        }
        if (normalized != null && normalized.contains("-")) {
            String shortAlias = normalized.substring(0, normalized.indexOf('-'));
            direct = LanguageHelper.lookupIgnoreCase(localized, shortAlias);
            if (direct != null && !direct.isBlank()) {
                return direct;
            }
        }
        for (Map.Entry<String, String> entry : localized.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null || entry.getValue().isBlank()) {
                continue;
            }
            String keyNorm = LanguageHelper.normalizeToSupportedLang(entry.getKey());
            if (keyNorm != null && normalized != null && keyNorm.equalsIgnoreCase(normalized)) {
                return entry.getValue();
            }
        }
        return null;
    }

    /**
     * 计算文章针对给定关键字的完整搜索权重
     *
     * @param title   文章标题
     * @param content 文章正文（建议传入提取后的可搜索纯文本）
     * @param keyword 搜索关键字
     * @return 最终搜索权重
     */
    public static int calculateWeight(String title, String content, String keyword) {
        if (keyword == null || keyword.isBlank()) {
            return BASE_WEIGHT;
        }
        List<String> terms = extractTerms(keyword);
        return calculateWeightByTerms(title, content, terms);
    }

    /**
     * 计算文章针对搜索词列表的完整搜索权重
     *
     * @param title   文章标题
     * @param content 文章正文
     * @param terms   分词后的搜索关键词列表
     * @return 最终搜索权重
     */
    public static int calculateWeightByTerms(String title, String content, List<String> terms) {
        if (terms == null || terms.isEmpty()) {
            return BASE_WEIGHT;
        }

        int titleWeight = calculateTitleWeightByTerms(title, terms);
        int contentPositionWeight = calculateContentPositionWeightByTerms(content, terms);
        int contentOccurrenceWeight = calculateContentOccurrenceWeightByTerms(content, terms);

        return BASE_WEIGHT + titleWeight + contentPositionWeight + contentOccurrenceWeight;
    }

    /**
     * 计算标题中出现关键字的权重（每出现一次权重 +100）
     */
    public static int calculateTitleWeight(String title, String keyword) {
        if (title == null || title.isBlank() || keyword == null || keyword.isBlank()) {
            return 0;
        }
        return calculateTitleWeightByTerms(title, extractTerms(keyword));
    }

    /**
     * 计算标题中出现各关键词的权重（每出现一次权重 +100）
     */
    public static int calculateTitleWeightByTerms(String title, List<String> terms) {
        if (title == null || title.isBlank() || terms == null || terms.isEmpty()) {
            return 0;
        }
        int totalOccurrences = 0;
        for (String term : terms) {
            totalOccurrences += countOccurrences(title, term);
        }
        return totalOccurrences * TITLE_OCCURRENCE_WEIGHT;
    }

    /**
     * 计算正文中关键词首次出现位置的权重：
     * 前 20% 出现：+40
     * 前 40% 出现：+30
     * 前 60% 出现：+20
     * 前 80% 出现：+10
     * 前 80% 都没有（或未出现）：+0
     */
    public static int calculateContentPositionWeight(String content, String keyword) {
        if (content == null || content.isBlank() || keyword == null || keyword.isBlank()) {
            return 0;
        }
        return calculateContentPositionWeightByTerms(content, extractTerms(keyword));
    }

    /**
     * 计算正文中搜索词首次出现位置的权重
     */
    public static int calculateContentPositionWeightByTerms(String content, List<String> terms) {
        if (content == null || content.isEmpty() || terms == null || terms.isEmpty()) {
            return 0;
        }

        int firstIndex = findFirstOccurrenceIndex(content, terms);
        if (firstIndex < 0) {
            return 0;
        }

        double ratio = (double) firstIndex / content.length();
        if (ratio < 0.2) {
            return CONTENT_FIRST_OCCURRENCE_P20_WEIGHT;
        } else if (ratio < 0.4) {
            return CONTENT_FIRST_OCCURRENCE_P40_WEIGHT;
        } else if (ratio < 0.6) {
            return CONTENT_FIRST_OCCURRENCE_P60_WEIGHT;
        } else if (ratio < 0.8) {
            return CONTENT_FIRST_OCCURRENCE_P80_WEIGHT;
        } else {
            return 0;
        }
    }

    /**
     * 计算正文中出现关键字次数的权重（每出现一次权重 +5）
     */
    public static int calculateContentOccurrenceWeight(String content, String keyword) {
        if (content == null || content.isBlank() || keyword == null || keyword.isBlank()) {
            return 0;
        }
        return calculateContentOccurrenceWeightByTerms(content, extractTerms(keyword));
    }

    /**
     * 计算正文中各搜索词出现次数的权重（每出现一次权重 +5）
     */
    public static int calculateContentOccurrenceWeightByTerms(String content, List<String> terms) {
        if (content == null || content.isEmpty() || terms == null || terms.isEmpty()) {
            return 0;
        }
        int totalOccurrences = 0;
        for (String term : terms) {
            totalOccurrences += countOccurrences(content, term);
        }
        return totalOccurrences * CONTENT_OCCURRENCE_WEIGHT;
    }

    /**
     * 查找各搜索词在文本中首次出现的最小下标
     */
    public static int findFirstOccurrenceIndex(String text, List<String> terms) {
        if (text == null || text.isEmpty() || terms == null || terms.isEmpty()) {
            return -1;
        }
        String foldedText = text.toLowerCase(Locale.ROOT);
        int earliestIndex = -1;
        for (String term : terms) {
            if (term == null || term.isEmpty()) {
                continue;
            }
            int idx = foldedText.indexOf(term.toLowerCase(Locale.ROOT));
            if (idx >= 0 && (earliestIndex < 0 || idx < earliestIndex)) {
                earliestIndex = idx;
            }
        }
        return earliestIndex;
    }

    /**
     * 统计指定词在文本中不重叠出现的总次数（忽略大小写）
     */
    public static int countOccurrences(String text, String term) {
        if (text == null || term == null || text.isEmpty() || term.isEmpty()) {
            return 0;
        }
        String foldedText = text.toLowerCase(Locale.ROOT);
        String foldedTerm = term.toLowerCase(Locale.ROOT);
        int count = 0;
        int cursor = 0;
        while (cursor <= foldedText.length() - foldedTerm.length()) {
            int idx = foldedText.indexOf(foldedTerm, cursor);
            if (idx < 0) {
                break;
            }
            count++;
            cursor = idx + foldedTerm.length();
        }
        return count;
    }

    /**
     * 从关键词字符串中提取搜索项（按空格切分、去除空串、去重、上限 8 个）
     */
    public static List<String> extractTerms(String keyword) {
        if (keyword == null || keyword.isBlank()) {
            return List.of();
        }
        return java.util.Arrays.stream(keyword.trim().split("\\s+"))
                .filter(term -> !term.isBlank())
                .distinct()
                .limit(8)
                .toList();
    }
}
