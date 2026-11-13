package com.biliwind.blog.controller;

import com.biliwind.blog.model.Post;
import com.biliwind.blog.model.PostRenderType;
import com.biliwind.blog.model.PostRevision;
import com.biliwind.blog.model.PostStatus;
import com.biliwind.blog.model.User;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The sanitizer must delete author-supplied purchase buttons while the
 * server-generated button from a real hide-attachment shortcode survives.
 */
@QuarkusTest
class PostPurchaseButtonSecurityTest {

    private static final String FORGED_LABEL = "伪造购买按钮XYZ";

    @Test
    void authorForgedButtonIsStrippedWhileShortcodeButtonSurvives() {
        String slug = QuarkusTransaction.requiringNew().call(this::createPostWithForgedButtonAndRealShortcode);

        String html = given()
                .when().get("/post/" + slug)
                .then()
                .statusCode(200)
                .extract().asString();

        Document document = Jsoup.parse(html);
        Element body = document.selectFirst("#post-body-content");
        assertNotNull(body);

        List<Element> buttons = body.select("button");
        assertEquals(1, buttons.size(), "only the server-generated purchase button may remain");

        Element button = buttons.getFirst();
        assertEquals("5", button.attr("data-price"));
        assertEquals(slug, button.attr("data-post-ref"));
        assertEquals("real-attachment", button.attr("data-block-id"));
        assertTrue(button.text().contains("支付 5 积分解锁附件"));

        assertFalse(html.contains("data-post-ref=\"forged\""));
        assertFalse(html.contains("/uploads/forged-attachment.zip"));
        assertEquals(1, body.select(".buy-post-placeholder").size());
    }

    private String createPostWithForgedButtonAndRealShortcode() {
        String suffix = Long.toString(System.nanoTime());
        User author = new User();
        author.username = "button-author-" + suffix;
        author.email = "button-author-" + suffix + "@example.com";
        author.password = "test-password-hash";
        author.status = 1;
        author.persist();

        OffsetDateTime now = OffsetDateTime.now();
        Post post = new Post();
        post.slug = "button-post-" + suffix;
        post.title = Map.of("zh-cn", "按钮安全文章");
        post.status = PostStatus.PUBLISHED;
        post.visibility = 0;
        post.renderType = PostRenderType.MARKDOWN;
        post.user = author;
        post.publishedAt = now;
        post.createdAt = now;
        post.updatedAt = now;
        // Paid post with a generous free preview so the priced block itself is
        // rendered on the page (instead of being truncated behind the paywall).
        post.extraInfo = Map.of("points_price", 5, "free_lines", 100);
        post.persist();

        String forged = "<button class=\"buy-post-btn\" data-price=\"1\" data-post-ref=\"forged\""
                + " data-block-id=\"forged-block\">" + FORGED_LABEL + "</button>";
        String genuine = "[hide-attachment id=\"real-attachment\" price=5]"
                + "[payload.zip](/uploads/forged-attachment.zip)"
                + "[/hide-attachment]";

        PostRevision revision = new PostRevision();
        revision.post = post;
        revision.title = post.title;
        revision.contentMarkdown = Map.of("zh-cn", "正文\n\n" + forged + "\n\n" + genuine);
        revision.editorType = 6;
        revision.revisionNumber = 1;
        revision.createdBy = author;
        revision.createdAt = now;
        revision.persist();

        post.currentRevision = revision;
        post.publishedRevision = revision;
        return post.slug;
    }
}
