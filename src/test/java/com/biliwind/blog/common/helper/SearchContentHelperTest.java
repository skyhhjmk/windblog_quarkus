package com.biliwind.blog.common.helper;

import com.biliwind.blog.model.PostRenderType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SearchContentHelperTest {

    @Test
    void shouldExtractVisibleTextFromMarkdown() {
        String content = "# 标题\n\n这是 **可搜索正文**。";

        String searchableText = SearchContentHelper.toSearchableText(content, PostRenderType.MARKDOWN);

        assertTrue(searchableText.contains("标题"));
        assertTrue(searchableText.contains("可搜索正文"));
        assertFalse(searchableText.contains("**"));
    }

    @Test
    void shouldExtractVisibleTextFromHtml() {
        String content = "<section><h2>富文本标题</h2><p>正文内容</p></section>";

        String searchableText = SearchContentHelper.toSearchableText(content, PostRenderType.HTML);

        assertTrue(searchableText.contains("富文本标题"));
        assertTrue(searchableText.contains("正文内容"));
        assertFalse(searchableText.contains("<section>"));
    }

    @Test
    void shouldExtractTextFromQuillJson() {
        String content = """
                {
                  "ops": [
                    {"insert": "第一段正文"},
                    {"insert": "第二段正文", "attributes": {"bold": true}}
                  ]
                }
                """;

        String searchableText = SearchContentHelper.toSearchableText(content, PostRenderType.FLUTTER_QUILL);

        assertTrue(searchableText.contains("第一段正文"));
        assertTrue(searchableText.contains("第二段正文"));
        assertFalse(searchableText.contains("bold"));
    }
}
