package com.biliwind.blog.common.helper;

import com.biliwind.blog.model.Post;
import com.biliwind.blog.model.PostRevision;
import com.biliwind.blog.model.translation.ParagraphBlockItem;
import com.biliwind.blog.model.translation.ParagraphTranslationMeta;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class MarkdownParagraphHelperTest {

    @Test
    void testSplitBlocks() {
        String md = """
                # 标题一
                
                这是第一段自然语言段落。
                
                ```java
                public class Main {
                    public static void main(String[] args) {}
                }
                ```
                
                这是第二段自然语言段落。
                """;

        List<MarkdownParagraphHelper.SourceBlock> blocks = MarkdownParagraphHelper.splitBlocks(md);
        assertEquals(4, blocks.size());
        assertEquals("# 标题一", blocks.get(0).rawText());
        assertEquals("这是第一段自然语言段落。", blocks.get(1).rawText());
        assertTrue(blocks.get(2).rawText().startsWith("```java"));
        assertEquals("这是第二段自然语言段落。", blocks.get(3).rawText());
        assertFalse(blocks.get(0).hash().isBlank());
    }

    @Test
    void testSyncOnSourceUpdate_detectsOutdatedAndKeepsUnchanged() {
        String oldSource = """
                # 标题一
                
                第一段。
                
                第二段。
                """;

        List<MarkdownParagraphHelper.SourceBlock> oldBlocks = MarkdownParagraphHelper.splitBlocks(oldSource);
        PostRevision prevRevision = new PostRevision();
        prevRevision.contentMarkdown = Map.of(
                "zh-cn", oldSource,
                "en-US", "# Title One\n\nFirst paragraph.\n\nSecond paragraph."
        );
        ParagraphTranslationMeta oldMeta = new ParagraphTranslationMeta(
                "zh-cn",
                false,
                0,
                3,
                MarkdownParagraphHelper.hash("文章标题"),
                "",
                "",
                "",
                "",
                List.of(
                        new ParagraphBlockItem(0, oldBlocks.get(0).hash(), "# Title One", false),
                        new ParagraphBlockItem(1, oldBlocks.get(1).hash(), "First paragraph.", false),
                        new ParagraphBlockItem(2, oldBlocks.get(2).hash(), "Second paragraph.", false)
                )
        );
        prevRevision.paragraphTranslations = Map.of("en-US", oldMeta);

        // Now modify second paragraph in source, keeping title and first paragraph unchanged
        String newSource = """
                # 标题一
                
                第一段。
                
                第二段已经被作者修改了内容。
                """;

        Map<String, String> nextContent = Map.of("zh-cn", newSource, "en-US", prevRevision.contentMarkdown.get("en-US"));
        MarkdownParagraphHelper.SyncResult result = MarkdownParagraphHelper.syncOnSourceUpdate(
                new Post(),
                prevRevision,
                "zh-cn",
                newSource,
                Map.of("zh-cn", "文章标题"),
                Map.of(),
                Map.of(),
                Map.of(),
                Map.of(),
                nextContent
        );

        ParagraphTranslationMeta updatedEnMeta = result.paragraphTranslations().get("en-US");
        assertNotNull(updatedEnMeta);
        assertTrue(updatedEnMeta.outdated(), "Should be marked as outdated because paragraph 2 changed");
        assertEquals(1, updatedEnMeta.outdatedParagraphs());
        assertEquals(3, updatedEnMeta.totalParagraphs());

        List<ParagraphBlockItem> paragraphs = updatedEnMeta.paragraphs();
        assertEquals("# Title One", paragraphs.get(0).targetText());
        assertFalse(paragraphs.get(0).outdated());

        assertEquals("First paragraph.", paragraphs.get(1).targetText());
        assertFalse(paragraphs.get(1).outdated());

        // Paragraph 2 should fall back to new source text and be marked outdated
        assertEquals("第二段已经被作者修改了内容。", paragraphs.get(2).targetText());
        assertTrue(paragraphs.get(2).outdated());

        // Assembled content should contain the fallback
        String assembledEn = result.contentMarkdown().get("en-US");
        assertTrue(assembledEn.contains("# Title One"));
        assertTrue(assembledEn.contains("First paragraph."));
        assertTrue(assembledEn.contains("第二段已经被作者修改了内容。"));
    }

    @Test
    void testIncrementalPrepResult_onlyTranslatesOutdated() {
        String sourceContent = """
                # 标题一
                
                第一段。
                
                新更新的第二段。
                """;
        List<MarkdownParagraphHelper.SourceBlock> blocks = MarkdownParagraphHelper.splitBlocks(sourceContent);

        ParagraphTranslationMeta existingMeta = new ParagraphTranslationMeta(
                "zh-cn",
                true,
                1,
                3,
                MarkdownParagraphHelper.hash("文章标题"),
                "",
                "",
                "",
                "",
                List.of(
                        new ParagraphBlockItem(0, blocks.get(0).hash(), "# Title One", false),
                        new ParagraphBlockItem(1, blocks.get(1).hash(), "First paragraph.", false),
                        new ParagraphBlockItem(2, "old_hash", "新更新的第二段。", true)
                )
        );

        MarkdownParagraphHelper.IncrementalPrepResult prep = MarkdownParagraphHelper.prepareTranslationFields(
                "zh-cn", "en-US", "文章标题", "", sourceContent, "", "", "",
                existingMeta, false
        );

        assertFalse(prep.isFullRetranslation());
        // Only p_2 should be in fields, title and p_0/p_1 are unchanged!
        assertFalse(prep.fieldsToTranslate().containsKey("p_0"));
        assertFalse(prep.fieldsToTranslate().containsKey("p_1"));
        assertTrue(prep.fieldsToTranslate().containsKey("p_2"));
        assertEquals("新更新的第二段。", prep.fieldsToTranslate().get("p_2"));
    }

    @Test
    void testRenderHtmlWithOutdatedMarkers() {
        ParagraphTranslationMeta meta = new ParagraphTranslationMeta(
                "zh-cn",
                true,
                1,
                2,
                "", "", "", "", "",
                List.of(
                        new ParagraphBlockItem(0, "h1", "First paragraph in English.", false),
                        new ParagraphBlockItem(1, "h2", "第二段中文原文未翻译。", true)
                )
        );

        String html = MarkdownParagraphHelper.renderHtmlWithOutdatedMarkers(
                "First paragraph in English.\n\n第二段中文原文未翻译。",
                meta,
                "原文（未同步）"
        );

        assertTrue(html.contains("First paragraph in English."));
        assertTrue(html.contains("translation-outdated-block"));
        assertTrue(html.contains("原文（未同步）"));
        assertTrue(html.contains("第二段中文原文未翻译。"));
    }
}
