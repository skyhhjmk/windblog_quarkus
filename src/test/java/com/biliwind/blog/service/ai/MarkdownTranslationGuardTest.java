package com.biliwind.blog.service.ai;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MarkdownTranslationGuardTest {

    private static final String SOURCE = """
            ## 安装步骤

            运行 `npm install`，然后访问 [文档](https://example.com/docs)。

            ```bash
            npm install
            ```

            [hide-text id="secret"]付费内容[/hide-text]

            ![截图](https://example.com/a.png)
            """;

    @Test
    void acceptsStructurePreservingTranslation() {
        String translated = """
                ## Installation

                Run `npm install`, then open [documentation](https://example.com/docs).

                ```bash
                npm install
                ```

                [hide-text id="secret"]Paid content[/hide-text]

                ![screenshot](https://example.com/a.png)
                """;
        assertFalse(MarkdownTranslationGuard.verify(SOURCE, translated).stream()
                .anyMatch(issue -> issue.contains("代码块") || issue.contains("URL")
                        || issue.contains("短代码") || issue.contains("标题层级")));
    }

    @Test
    void rejectsDroppedCodeBlockAndUrl() {
        String translated = """
                ## Installation

                Run the command, then open the documentation.

                [hide-text id="secret"]Paid content[/hide-text]
                """;
        List<String> issues = MarkdownTranslationGuard.verify(SOURCE, translated);
        assertTrue(issues.stream().anyMatch(issue -> issue.contains("代码块")));
        assertTrue(issues.stream().anyMatch(issue -> issue.contains("URL")));
        assertTrue(issues.stream().anyMatch(issue -> issue.contains("图片")));
    }

    @Test
    void rejectsEmptyAndUnchangedTranslation() {
        assertTrue(MarkdownTranslationGuard.verify(SOURCE, "").contains("译文为空"));
        assertTrue(MarkdownTranslationGuard.verify(SOURCE, SOURCE)
                .contains("译文与原文相同"));
    }
}
