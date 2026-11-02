package com.biliwind.blog.controller;

import com.biliwind.blog.model.Media;
import com.biliwind.blog.model.Post;
import com.biliwind.blog.model.PostMedia;
import com.biliwind.blog.model.PostMediaId;
import com.biliwind.blog.model.PostRenderType;
import com.biliwind.blog.model.PostRevision;
import com.biliwind.blog.model.PostStatus;
import com.biliwind.blog.model.User;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;

@QuarkusTest
class PostAttachmentPageTest {

    @Test
    void anonymousPageShowsLockedAttachmentWithoutTheRawUploadUrl() {
        String[] data = QuarkusTransaction.requiringNew().call(this::createPost);
        String slug = data[0];
        String storageKey = data[1];

        given()
                .when().get("/post/" + slug)
                .then()
                .statusCode(200)
                .body(containsString("data-attachments-section=\"true\""))
                .body(containsString("ruler.zip"))
                .body(containsString("登录后查看"))
                .body(containsString("data-login-required=\"true\""))
                .body(not(containsString("/uploads/" + storageKey)));
    }

    @Test
    void anonymousPageKeepsTheUnlockedBlockShell() {
        String slug = QuarkusTransaction.requiringNew().call(this::createShellPost);

        given()
                .when().get("/post/" + slug)
                .then()
                .statusCode(200)
                .body(containsString("hide-block-shell"))
                .body(containsString("hide-block-header"))
                .body(containsString("bg-amber-500/80"))
                .body(containsString("解锁说明"))
                .body(containsString("<strong>bold</strong>"));
    }

    private String createShellPost() {
        String suffix = Long.toString(System.nanoTime());
        User author = new User();
        author.username = "shell-author-" + suffix;
        author.email = "shell-author-" + suffix + "@example.com";
        author.password = "test-password-hash";
        author.status = 1;
        author.persist();

        OffsetDateTime now = OffsetDateTime.now();
        Post post = new Post();
        post.slug = "shell-post-" + suffix;
        post.title = Map.of("zh-cn", "外壳文章");
        post.status = PostStatus.PUBLISHED;
        post.visibility = 0;
        post.renderType = PostRenderType.MARKDOWN;
        post.user = author;
        post.publishedAt = now;
        post.createdAt = now;
        post.updatedAt = now;
        post.persist();

        PostRevision revision = new PostRevision();
        revision.post = post;
        revision.title = post.title;
        revision.contentMarkdown = Map.of("zh-cn",
                "[hide-text id=\"shell-page-block\" show=\"解锁说明\"]**bold** content[/hide-text]");
        revision.editorType = 6;
        revision.revisionNumber = 1;
        revision.createdBy = author;
        revision.createdAt = now;
        revision.persist();

        post.currentRevision = revision;
        post.publishedRevision = revision;
        return post.slug;
    }

    private String[] createPost() {
        String suffix = Long.toString(System.nanoTime());
        User author = new User();
        author.username = "page-author-" + suffix;
        author.email = "page-author-" + suffix + "@example.com";
        author.password = "test-password-hash";
        author.status = 1;
        author.persist();

        String storageKey = "page-attachment-" + suffix + ".zip";
        Media media = new Media();
        media.storageKey = storageKey;
        media.url = "/uploads/" + storageKey;
        media.fileName = "ruler.zip";
        media.mediaType = 2;
        media.mimeType = "application/zip";
        media.size = 2048L;
        media.storageClasses = Map.of("local", Map.of("status", "READY"));
        media.version = 0;
        media.createdAt = OffsetDateTime.now();
        media.updatedAt = media.createdAt;
        media.persist();

        OffsetDateTime now = OffsetDateTime.now();
        Post post = new Post();
        post.slug = "page-attachment-post-" + suffix;
        post.title = Map.of("zh-cn", "附件文章");
        post.status = PostStatus.PUBLISHED;
        post.visibility = 0;
        post.renderType = PostRenderType.MARKDOWN;
        post.user = author;
        post.publishedAt = now;
        post.createdAt = now;
        post.updatedAt = now;
        post.persist();

        PostRevision revision = new PostRevision();
        revision.post = post;
        revision.title = post.title;
        revision.contentMarkdown = Map.of("zh-cn",
                "[hide-attachment id=\"page-free-attachment\" show=\"ruler.zip\"]"
                        + "[ruler.zip](/uploads/" + storageKey + ")"
                        + "[/hide-attachment]");
        revision.editorType = 6;
        revision.revisionNumber = 1;
        revision.createdBy = author;
        revision.createdAt = now;
        revision.persist();

        post.currentRevision = revision;
        post.publishedRevision = revision;

        PostMedia relation = new PostMedia();
        relation.id = new PostMediaId(post.id, media.id);
        relation.post = post;
        relation.media = media;
        relation.usageType = 0;
        relation.persist();

        return new String[]{post.slug, storageKey};
    }
}
