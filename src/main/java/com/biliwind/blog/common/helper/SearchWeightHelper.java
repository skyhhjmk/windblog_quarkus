package com.biliwind.blog.common.helper;

import java.util.List;
import java.util.Locale;

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
 * 5. 位置计算与出现次数计算互不冲突，两者累加。
 */
public final class SearchWeightHelper {

    public static final int BASE_WEIGHT = 0;
    public static final int TITLE_OCCURRENCE_WEIGHT = 100;
    public static final int CONTENT_FIRST_OCCURRENCE_P20_WEIGHT = 40;
    public static final int CONTENT_FIRST_OCCURRENCE_P40_WEIGHT = 30;
    public static final int CONTENT_FIRST_OCCURRENCE_P60_WEIGHT = 20;
    public static final int CONTENT_FIRST_OCCURRENCE_P80_WEIGHT = 10;
    public static final int CONTENT_OCCURRENCE_WEIGHT = 5;

    private SearchWeightHelper() {
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
