package com.biliwind.blog.common.helper;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PostHelperTest {

    @Test
    @DisplayName("测试中英混排与 Markdown 内容的字数统计")
    void testCalculateWordCount() {
        assertEquals(0, PostHelper.calculateWordCount(null));
        assertEquals(0, PostHelper.calculateWordCount("   "));

        // 纯中文：12 个汉字 + 标点
        assertEquals(12, PostHelper.calculateWordCount("这是一个非常棒的博客系统。"));

        // 纯英文
        assertEquals(5, PostHelper.calculateWordCount("Hello world, this is WindBlog!"));

        // 中英混排 + Markdown 标记
        String markdown = "# 标题\n\n这是 **加粗文字** 以及 `inline code`。\n\n```java\nSystem.out.println(\"hello\");\n```\n\nWindBlog Quarkus rocks!";
        int count = PostHelper.calculateWordCount(markdown);
        assertTrue(count > 10, "字数应统计中文字符和英文单词并忽略 Markdown 语法标记");
    }

    @Test
    @DisplayName("测试根据字数估算阅读时长（分钟）")
    void testCalculateReadingTimeMinutes() {
        assertEquals(1, PostHelper.calculateReadingTimeMinutes(0));
        assertEquals(1, PostHelper.calculateReadingTimeMinutes(150));
        assertEquals(1, PostHelper.calculateReadingTimeMinutes(300));
        assertEquals(2, PostHelper.calculateReadingTimeMinutes(301));
        assertEquals(4, PostHelper.calculateReadingTimeMinutes(1000));
    }
}
