package com.biliwind.blog.service;

import com.biliwind.blog.common.constant.LanguageConstant;
import com.biliwind.blog.controller.api.admin.dto.AdminPostDtos.EmailDispatchRequest;
import com.biliwind.blog.model.Post;
import com.biliwind.blog.model.PostRenderType;
import com.biliwind.blog.model.PostRevision;
import com.biliwind.blog.model.PostStatus;
import com.biliwind.blog.model.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ArticleEmailNotificationServiceTest {

    private ArticleEmailNotificationService service;

    @BeforeEach
    void setUp() {
        PublicUrlService publicUrlService = new PublicUrlService();
        publicUrlService.configuredPublicUrl = "https://blog.example.com";

        PublicContentSanitizer sanitizer = new PublicContentSanitizer();
        sanitizer.publicUrlService = publicUrlService;

        service = new ArticleEmailNotificationService();
        service.emailTemplateRenderer = new EmailTemplateRenderer();
        service.publicUrlService = publicUrlService;
        service.publicContentSanitizer = sanitizer;
    }

    @Test
    void shouldRenderFullMarkdownArticleAsRichHtmlEmail() {
        Post post = createPost(
                "rich-article",
                "富文本邮件测试文章",
                "这是一段文章摘要。",
                PostRenderType.MARKDOWN,
                """
                ## 第一节

                正文包含 **加粗**、`行内代码` 和 [站内链接](/post/other-article)。

                > 引用内容

                ```java
                System.out.println("hello");
                ```

                | 列1 | 列2 |
                | --- | --- |
                | A | B |

                ![配图](/uploads/demo.png)

                <script>alert('xss')</script>
                """);

        String html = service.renderArticleEmail(post, LanguageConstant.LANG_ZH_CN, "富文本邮件测试文章");

        assertTrue(html.contains("<!doctype html>"));
        assertTrue(html.contains("富文本邮件测试文章"));
        assertTrue(html.contains("这是一段文章摘要。"));
        assertTrue(html.contains("作者：测试作者"));
        assertTrue(html.contains("<h2 style=\"margin:26px 0 12px"));
        assertTrue(html.contains("<strong>加粗</strong>"));
        assertTrue(html.contains("<code style=\"padding:2px 6px"));
        assertTrue(html.contains("<pre style=\"margin:16px 0"));
        assertTrue(html.contains("<table style=\"width:100%"));
        assertTrue(html.contains("href=\"https://blog.example.com/post/other-article\""));
        assertTrue(html.contains("src=\"https://blog.example.com/uploads/demo.png\""));
        assertTrue(html.contains("href=\"https://blog.example.com/post/rich-article\""));
        assertTrue(html.contains("在浏览器中阅读原文"));
        assertFalse(html.contains("<script>"));
        assertFalse(html.contains("alert('xss')"));
    }

    @Test
    void shouldRenderHtmlPostAndEscapeUntrustedTitle() {
        Post htmlPost = createPost(
                "html-article",
                "HTML <原生> 文章",
                null,
                PostRenderType.HTML,
                "<p>原生段落</p><blockquote>引用</blockquote><img src=\"/uploads/pic.webp\" loading=\"lazy\">");

        String html = service.renderArticleEmail(htmlPost, LanguageConstant.LANG_ZH_CN, "HTML <原生> 文章");

        assertTrue(html.contains("HTML &lt;原生&gt; 文章"));
        assertTrue(html.contains("<p style=\"margin:0 0 16px;line-height:1.75;\">原生段落</p>"));
        assertTrue(html.contains("<blockquote style=\"margin:16px 0"));
        assertTrue(html.contains("src=\"https://blog.example.com/uploads/pic.webp\""));
        assertFalse(html.contains("loading=\"lazy\""));
    }

    @Test
    void shouldRespectGuardClausesInQueueArticleUpdate() {
        Post privatePost = createPost("private-post", "私密", null, PostRenderType.MARKDOWN, "body");
        privatePost.visibility = 1;

        assertDoesNotThrow(() -> service.queueArticleUpdate(privatePost, new EmailDispatchRequest(true, null, null)));
        assertDoesNotThrow(() -> service.queueArticleUpdate(privatePost, null));

        Post publicPost = createPost("public-post", "公开", null, PostRenderType.MARKDOWN, "body");
        assertDoesNotThrow(() -> service.queueArticleUpdate(publicPost, new EmailDispatchRequest(false, null, null)));
        assertThrows(IllegalArgumentException.class,
                () -> service.queueArticleUpdate(publicPost, new EmailDispatchRequest(true, 1L, 2L)));
    }

    private Post createPost(String slug,
                            String title,
                            String summary,
                            PostRenderType renderType,
                            String content) {
        User author = new User();
        author.username = "author";
        author.nickname = "测试作者";

        Post post = new Post();
        post.slug = slug;
        post.title = Map.of("zh-cn", title);
        if (summary != null) {
            post.summary = Map.of("zh-cn", summary);
        }
        post.status = PostStatus.PUBLISHED;
        post.visibility = 0;
        post.renderType = renderType;
        post.user = author;
        post.authorName = "测试作者";
        post.publishedAt = OffsetDateTime.now();

        PostRevision revision = new PostRevision();
        revision.post = post;
        revision.title = Map.of("zh-cn", title);
        revision.contentMarkdown = Map.of("zh-cn", content);
        revision.editorType = (short) (renderType == PostRenderType.HTML ? 1 : 0);
        revision.revisionNumber = 1;
        revision.createdBy = author;
        revision.createdAt = OffsetDateTime.now();

        post.currentRevision = revision;
        post.publishedRevision = revision;
        return post;
    }
}
