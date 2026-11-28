package com.biliwind.blog.common.helper;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SearchDictionaryCatalogTest {

    @Test
    void segmentsCustomChineseAndTechTermsWhileFilteringStopwords() {
        assertEquals(List.of("我的世界"), SearchDictionaryCatalog.segmentQuery("我的世界"));
        assertEquals(List.of("我的世界", "红石电路"),
                SearchDictionaryCatalog.segmentQuery("我的世界 的 红石电路"));
        assertEquals(List.of("Spring Boot", "Kubernetes", "部署"),
                SearchDictionaryCatalog.segmentQuery("如何使用 Spring Boot 和 Kubernetes 进行 部署"));
    }

    @Test
    void containsComprehensiveChineseAndEnglishStopwords() {
        assertTrue(SearchDictionaryCatalog.isStopword("的"));
        assertTrue(SearchDictionaryCatalog.isStopword("我"));
        assertTrue(SearchDictionaryCatalog.isStopword("the"));
        assertTrue(SearchDictionaryCatalog.isStopword("and"));
        assertFalse(SearchDictionaryCatalog.isStopword("Quarkus"));
        assertFalse(SearchDictionaryCatalog.isStopword("我的世界"));
    }

    @Test
    void fallsBackToOriginalTermsWhenQueryContainsOnlyStopwords() {
        List<String> terms = SearchDictionaryCatalog.segmentQuery("the and");
        assertEquals(List.of("the", "and"), terms);
    }
}
