package com.biliwind.blog.service.og;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class OgImageRendererTest {

    @Test
    void renderSvgContainsEscapedMetadataAndDimensions() {
        String svg = OgImageRenderer.renderSvg(
                "Quarkus & Mandrel <Native> 实战指南：高性能博客架构",
                "这是一篇关于 Quarkus 原生镜像与 SEO 优化的深度实践总结。",
                "后端架构",
                "WindBlog",
                "WindBlog Tech",
                "2026-11-16",
                "quarkus-native:backend"
        );

        assertTrue(svg.contains("width=\"1200\" height=\"630\""));
        assertTrue(svg.contains("Quarkus &amp; Mandrel &lt;Native&gt;"));
        assertTrue(svg.contains("后端架构"));
        assertTrue(svg.contains("WindBlog Tech"));
        assertTrue(svg.contains("2026-11-16"));
    }

    @Test
    void wrapTextHandlesCjkAndTruncation() {
        List<String> lines = OgImageRenderer.wrapText(
                "这是一个非常非常长的中文标题用来测试自动换行以及在超过三行时是否会正确截断并添加省略号",
                20,
                3
        );
        assertEquals(3, lines.size());
        assertTrue(lines.get(2).endsWith("..."));
    }

    @Test
    void renderFallbackPngProducesValid1200x630PngHeader() {
        byte[] png = OgImageRenderer.renderFallbackPng(
                "WindBlog",
                "quarkus-native-seo",
                "2026-11-16",
                "quarkus-native-seo:tech"
        );

        assertTrue(png.length > 1000);
        byte[] magic = new byte[]{(byte) 137, 80, 78, 71, 13, 10, 26, 10};
        for (int i = 0; i < magic.length; i++) {
            assertEquals(magic[i], png[i]);
        }

        // IHDR chunk starts at byte 8: 4-byte length (13), "IHDR", 4-byte width, 4-byte height
        String ihdrType = new String(png, 12, 4, StandardCharsets.US_ASCII);
        assertEquals("IHDR", ihdrType);
        int width = ByteBuffer.wrap(png, 16, 4).getInt();
        int height = ByteBuffer.wrap(png, 20, 4).getInt();
        assertEquals(1200, width);
        assertEquals(630, height);
    }
}
