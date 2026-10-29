package com.biliwind.blog.service.ai;

import com.biliwind.blog.common.exception.BadRequestException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiArticleResponseParserTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void parsesPlainJson() {
        AiArticleResponseParser.Article article = AiArticleResponseParser.parse(mapper,
                "{\"title\":\"Quarkus 原生镜像\",\"summary\":\"摘要\","
                        + "\"contentMarkdown\":\"## 正文\\n\\n内容\"}");
        assertEquals("Quarkus 原生镜像", article.title());
        assertEquals("摘要", article.summary());
        assertTrue(article.contentMarkdown().startsWith("## 正文"));
    }

    @Test
    void stripsMarkdownFenceAndSurroundingText() {
        AiArticleResponseParser.Article article = AiArticleResponseParser.parse(mapper,
                "结果如下：\n```json\n{\"title\":\"标题\",\"contentMarkdown\":\"正文\"}\n```\n以上。");
        assertEquals("标题", article.title());
        assertEquals("正文", article.summary());
    }

    @Test
    void derivesSummaryWhenMissing() {
        AiArticleResponseParser.Article article = AiArticleResponseParser.parse(mapper,
                "{\"title\":\"标题\",\"contentMarkdown\":\"## 小节\\n\\n**正文**内容\"}");
        assertTrue(article.summary().contains("正文"));
        assertTrue(!article.summary().contains("*"));
    }

    @Test
    void rejectsMissingFieldsAndInvalidJson() {
        assertThrows(BadRequestException.class,
                () -> AiArticleResponseParser.parse(mapper, "{\"title\":\"标题\"}"));
        assertThrows(BadRequestException.class,
                () -> AiArticleResponseParser.parse(mapper, "not json"));
        assertThrows(BadRequestException.class,
                () -> AiArticleResponseParser.parse(mapper, ""));
    }
}
