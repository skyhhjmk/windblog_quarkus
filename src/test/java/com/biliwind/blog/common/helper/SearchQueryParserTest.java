package com.biliwind.blog.common.helper;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SearchQueryParserTest {

    @Test
    @DisplayName("测试普通自由文本查询无高级过滤器")
    void testPlainSearchQuery() {
        SearchQueryParser parsed = SearchQueryParser.parse("hello world");
        assertFalse(parsed.hasFilters());
        assertEquals("hello world", parsed.freeText());
        assertNull(parsed.title());
        assertNull(parsed.author());
        assertNull(parsed.content());
        assertNull(parsed.user());
    }

    @Test
    @DisplayName("测试单字段与多字段混合指定条件解析")
    void testStructuredFilters() {
        SearchQueryParser parsed = SearchQueryParser.parse("title:Quarkus author:sky hello world");
        assertTrue(parsed.hasFilters());
        assertEquals("Quarkus", parsed.title());
        assertEquals("sky", parsed.author());
        assertEquals("hello world", parsed.freeText());
        assertNull(parsed.content());
        assertNull(parsed.user());
    }

    @Test
    @DisplayName("测试带双引号的混合短语解析")
    void testQuotedFilters() {
        SearchQueryParser parsed = SearchQueryParser.parse("content:\"reactive programming\" user:hhjmk");
        assertTrue(parsed.hasFilters());
        assertEquals("reactive programming", parsed.content());
        assertEquals("hhjmk", parsed.user());
        assertEquals("", parsed.freeText());
    }
}
