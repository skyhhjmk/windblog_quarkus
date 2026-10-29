package com.biliwind.blog.service.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

class TranslationPromptTest {

    @Test
    void defaultPromptStatesTheMarkdownContract() {
        assertTrue(TranslationPrompt.DEFAULT_PROMPT.contains("Markdown"));
        assertTrue(TranslationPrompt.DEFAULT_PROMPT.contains("代码块"));
        assertTrue(TranslationPrompt.DEFAULT_PROMPT.contains("data-block-id"));
        assertTrue(TranslationPrompt.DEFAULT_PROMPT.contains("只输出一个 JSON 对象"));
    }

    @Test
    void buildIncludesLanguagesAndFields() throws Exception {
        String prompt = TranslationPrompt.build("zh-cn", "en-US", Map.of(
                "title", "标题",
                "summary", "摘要",
                "contentMarkdown", "正文"), new ObjectMapper());

        assertTrue(prompt.contains("\"sourceLanguage\":\"zh-cn\""));
        assertTrue(prompt.contains("\"targetLanguage\":\"en-US\""));
        assertTrue(prompt.contains("\"contentMarkdown\":\"正文\""));
    }

    @Test
    void buildUsesCustomTemplate() throws Exception {
        String prompt = TranslationPrompt.build("CUSTOM ", "zh-cn", "en-US",
                Map.of("title", "标题"), new ObjectMapper());
        assertTrue(prompt.startsWith("CUSTOM "));
    }
}
