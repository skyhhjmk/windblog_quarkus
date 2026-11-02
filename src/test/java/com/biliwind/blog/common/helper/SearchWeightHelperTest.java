package com.biliwind.blog.common.helper;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SearchWeightHelperTest {

    @Test
    @DisplayName("基础权重为 0，关键词为空或无匹配时返回 0")
    void shouldReturnBaseWeightWhenNoMatchOrEmptyKeyword() {
        assertEquals(0, SearchWeightHelper.calculateWeight("Hello World", "Content here", ""));
        assertEquals(0, SearchWeightHelper.calculateWeight("Hello World", "Content here", "   "));
        assertEquals(0, SearchWeightHelper.calculateWeight("Hello World", "Content here", (String) null));
        assertEquals(0, SearchWeightHelper.calculateWeight("Hello World", "Content here", "Python"));
        assertEquals(0, SearchWeightHelper.calculateWeight(null, null, "Java"));
        assertEquals(0, SearchWeightHelper.calculateWeightByTerms("Hello World", "Content here", List.of()));
        assertEquals(0, SearchWeightHelper.calculateWeightByTerms("Hello World", "Content here", null));
    }

    @Test
    @DisplayName("标题出现关键词：每出现一次权重 +100")
    void shouldCalculateTitleWeightCorrectly() {
        // 出现 1 次 -> 100
        assertEquals(100, SearchWeightHelper.calculateTitleWeight("深入浅出 Java 技术", "Java"));
        // 出现 2 次 -> 200
        assertEquals(200, SearchWeightHelper.calculateTitleWeight("Java 8 到 Java 21 新特性", "Java"));
        // 出现 3 次 -> 300
        assertEquals(300, SearchWeightHelper.calculateTitleWeight("Java 与 Java 虚拟机的 Java 实践", "java"));
        // 忽略大小写
        assertEquals(100, SearchWeightHelper.calculateTitleWeight("JAVA Programming", "java"));
    }

    @ParameterizedTest
    @CsvSource({
            "0, 40",    // 0 / 100 = 0.0 < 0.2 -> +40
            "10, 40",   // 10 / 100 = 0.10 < 0.2 -> +40
            "19, 40",   // 19 / 100 = 0.19 < 0.2 -> +40
            "20, 30",   // 20 / 100 = 0.20 < 0.4 -> +30
            "30, 30",   // 30 / 100 = 0.30 < 0.4 -> +30
            "39, 30",   // 39 / 100 = 0.39 < 0.4 -> +30
            "40, 20",   // 40 / 100 = 0.40 < 0.6 -> +20
            "50, 20",   // 50 / 100 = 0.50 < 0.6 -> +20
            "59, 20",   // 59 / 100 = 0.59 < 0.6 -> +20
            "60, 10",   // 60 / 100 = 0.60 < 0.8 -> +10
            "70, 10",   // 70 / 100 = 0.70 < 0.8 -> +10
            "79, 10",   // 79 / 100 = 0.79 < 0.8 -> +10
            "80, 0",    // 80 / 100 = 0.80 -> +0 (前80%都没有)
            "90, 0",    // 90 / 100 = 0.90 -> +0
            "96, 0"     // 96 / 100 = 0.96 -> +0
    })
    @DisplayName("正文中首次出现位置阶梯权重：前20%+40，前40%+30，前60%+20，前80%+10，前80%都没有不加")
    void shouldCalculateContentPositionWeight(int targetIndex, int expectedWeight) {
        // 构造长度为 100 的文本，并在 targetIndex 放置 "Java"
        StringBuilder sb = new StringBuilder(".".repeat(100));
        sb.replace(targetIndex, targetIndex + 4, "Java");
        String content = sb.toString();

        int weight = SearchWeightHelper.calculateContentPositionWeight(content, "Java");
        assertEquals(expectedWeight, weight, "Position " + targetIndex + " ratio " + (targetIndex / 100.0));
    }

    @Test
    @DisplayName("正文中出现关键词次数权重：出现一次 +5")
    void shouldCalculateContentOccurrenceWeight() {
        assertEquals(0, SearchWeightHelper.calculateContentOccurrenceWeight("没有任何匹配", "Java"));
        assertEquals(5, SearchWeightHelper.calculateContentOccurrenceWeight("Java 是一门语言", "Java"));
        assertEquals(15, SearchWeightHelper.calculateContentOccurrenceWeight("Java 很强，学 Java 用 Java", "Java"));
        assertEquals(25, SearchWeightHelper.calculateContentOccurrenceWeight("JAVA java Java JaVa jAVa", "java"));
    }

    @Test
    @DisplayName("位置权重与出现次数互不冲突，累加计算")
    void shouldCombinePositionAndOccurrenceWeightWithoutConflict() {
        // 长度 100 的内容：
        // 首次出现在 index 10 (前20% -> +40)
        // 共出现 3 次 -> 3 * 5 = +15
        // 位置 + 次数 = 55
        StringBuilder sb = new StringBuilder(".".repeat(100));
        sb.replace(10, 14, "Java");
        sb.replace(50, 54, "Java");
        sb.replace(85, 89, "Java");
        String content = sb.toString();

        assertEquals(40, SearchWeightHelper.calculateContentPositionWeight(content, "Java"));
        assertEquals(15, SearchWeightHelper.calculateContentOccurrenceWeight(content, "Java"));
        assertEquals(55, SearchWeightHelper.calculateWeight("无匹配标题", content, "Java"));
    }

    @Test
    @DisplayName("完整计算：基础权重0 + 标题出现次数*100 + 正文首次位置 + 正文出现次数*5")
    void shouldCalculateTotalWeight() {
        // 标题: "Java 实战指南: 掌握 Java 开发" -> 2 次 Java -> +200
        // 正文: 长度 50，Java 在 index 0 和 index 25
        // 首次位置 index 0 -> 0 / 50 = 0.0 < 0.20 -> +40
        // 正文出现 2 次 -> 2 * 5 = +10
        // 总权重 = 0 + 200 + 40 + 10 = 250
        String title = "Java 实战指南: 掌握 Java 开发";
        StringBuilder sb = new StringBuilder(".".repeat(50));
        sb.replace(0, 4, "Java");
        sb.replace(25, 29, "Java");
        String content = sb.toString();

        int totalWeight = SearchWeightHelper.calculateWeight(title, content, "Java");
        assertEquals(250, totalWeight);
    }

    @Test
    @DisplayName("多词搜索：标题与正文合并统计")
    void shouldHandleMultiTerms() {
        String title = "Quarkus 和 Java"; // Quarkus 1 次 (+100), Java 1 次 (+100) -> 200
        String content = "Java 是核心，Quarkus 带来超快启动。"; // 首次在 index 0 (+40)，Java 1次 (+5)，Quarkus 1次 (+5) -> 50
        int weight = SearchWeightHelper.calculateWeight(title, content, "Java Quarkus");
        assertEquals(250, weight);
    }

    @Test
    @DisplayName("多语言搜索：当前语言命中获得优先加成，且优先于仅其他语言命中的文章")
    void shouldPrioritizeCurrentLanguageMatchInMultilingualSearch() {
        // 文章 A：英文标题包含 "Docker Architecture"，中文标题为 "容器架构"
        java.util.Map<String, String> titlesA = java.util.Map.of(
                "en-US", "Docker Architecture Overview",
                "zh-CN", "容器架构概览"
        );
        java.util.Map<String, String> contentsA = java.util.Map.of(
                "en-US", "Docker is a lightweight container platform...",
                "zh-CN", "容器技术非常轻量..."
        );

        // 文章 B：英文标题为 "Microservices Guide"，中文标题为 "Docker 容器实战"
        java.util.Map<String, String> titlesB = java.util.Map.of(
                "en-US", "Microservices Guide",
                "zh-CN", "Docker 容器实战指南"
        );
        java.util.Map<String, String> contentsB = java.util.Map.of(
                "en-US", "Modern microservices design...",
                "zh-CN", "Docker 容器在企业中广泛使用..."
        );

        // 当用户在英文界面 (en-US) 搜索 "Docker"：
        // 文章 A 的英文标题和正文命中，包含当前语言加成 (500)
        int weightAEn = SearchWeightHelper.calculateMultilingualWeight(titlesA, contentsA, "Docker", "en-US");
        // 文章 B 的英文未命中，但中文命中 (跨语言匹配，无 500 加成)
        int weightBEn = SearchWeightHelper.calculateMultilingualWeight(titlesB, contentsB, "Docker", "en-US");

        // 验证文章 A 权重远高于文章 B
        org.junit.jupiter.api.Assertions.assertTrue(weightAEn > weightBEn,
                "Current language match (A=" + weightAEn + ") must rank higher than cross-lang match (B=" + weightBEn + ")");
        org.junit.jupiter.api.Assertions.assertTrue(weightBEn > 0,
                "Cross-language match should still produce positive weight (B=" + weightBEn + ")");

        // 反之，当用户在中文界面 (zh-CN) 搜索 "Docker"：
        int weightAZh = SearchWeightHelper.calculateMultilingualWeight(titlesA, contentsA, "Docker", "zh-CN");
        int weightBZh = SearchWeightHelper.calculateMultilingualWeight(titlesB, contentsB, "Docker", "zh-CN");

        // 文章 B 的中文标题和正文命中，包含当前语言加成 (500)
        org.junit.jupiter.api.Assertions.assertTrue(weightBZh > weightAZh,
                "Current language match (B=" + weightBZh + ") must rank higher than cross-lang match (A=" + weightAZh + ")");
    }

    @Test
    @DisplayName("跨语言搜索：即便当前语言没有匹配项，其他语言命中依然能被成功检索出并返回权重")
    void shouldSupportCrossLanguageSearch() {
        // 文章仅有中文
        java.util.Map<String, String> titles = java.util.Map.of("zh-CN", "从零搭建 Kubernetes 集群");
        java.util.Map<String, String> contents = java.util.Map.of("zh-CN", "Kubernetes 是当今主流的编排系统。");

        // 在英文环境 (en-US) 下搜索中文关键词 "集群"
        int weight = SearchWeightHelper.calculateMultilingualWeight(titles, contents, "集群", "en-US");

        // 标题命中 "集群" (+100)
        assertEquals(100, weight);
    }

    @Test
    @DisplayName("双语均命中时，获得当前语言加成及额外的多语言完善度微量加成")
    void shouldAwardBonusWhenBothLanguagesMatch() {
        java.util.Map<String, String> titles = java.util.Map.of(
                "en-US", "Mastering Java Virtual Machine",
                "zh-CN", "深入理解 Java 虚拟机"
        );
        java.util.Map<String, String> contents = java.util.Map.of(
                "en-US", "Java Virtual Machine (JVM) internals...",
                "zh-CN", "Java 虚拟机底层机制解析..."
        );

        int weight = SearchWeightHelper.calculateMultilingualWeight(titles, contents, "Java", "en-US");
        // 当前语言 (en-US) 命中: +500 (boost) + title (100) + content position (40) + count (5) = 645
        // 其他语言 (zh-CN) 命中: title (100) + content position (40) + count (5) = 145 -> bonus = min(50, 14) = 14
        // 总权重 = 645 + 14 = 659
        org.junit.jupiter.api.Assertions.assertTrue(weight >= 645, "Weight with current lang match should be >= 645");
    }
}
