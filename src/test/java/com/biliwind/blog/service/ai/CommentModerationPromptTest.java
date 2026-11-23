package com.biliwind.blog.service.ai;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommentModerationPromptTest {

    @Test
    void returnsTrimmedContentWhenQuoteTextIsAbsent() {
        assertEquals("写得很好", CommentModerationPrompt.buildModerationInput(" 写得很好 ", null, null));
        assertEquals("写得很好", CommentModerationPrompt.buildModerationInput("写得很好", "QUOTE", "   "));
    }

    @Test
    void includesQuoteTextInModerationInputForQuoteComments() {
        String input = CommentModerationPrompt.buildModerationInput(
                "赞同这个观点",
                "QUOTE",
                "<img src=x onerror=alert(1)>");

        assertTrue(input.contains("[引用原文]"));
        assertTrue(input.contains("<img src=x onerror=alert(1)>"));
        assertTrue(input.contains("[评论正文]"));
        assertTrue(input.contains("赞同这个观点"));
    }

    @Test
    void labelsCorrectionQuoteTextDistinctly() {
        String input = CommentModerationPrompt.buildModerationInput(
                "这里笔误了",
                "CORRECTION",
                "原文片段");

        assertTrue(input.contains("[纠错引用原文]"));
        assertTrue(input.contains("原文片段"));
        assertTrue(input.contains("这里笔误了"));
    }
}
