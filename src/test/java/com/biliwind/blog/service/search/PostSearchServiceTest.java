package com.biliwind.blog.service.search;

import com.biliwind.blog.model.BlogRegion;
import com.biliwind.blog.model.Post;
import com.biliwind.blog.model.PostRenderType;
import com.biliwind.blog.model.PostRevision;
import com.biliwind.blog.model.PostStatus;
import com.biliwind.blog.model.Tag;
import com.biliwind.blog.model.User;
import com.biliwind.blog.service.elasticsearch.ElasticsearchPostSearchService;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class PostSearchServiceTest {

    @Inject
    PostSearchService searchService;

    @Inject
    ElasticsearchPostSearchService elasticsearchPostSearchService;

    @Test
    void databaseAdapterAppliesSortAndDateBeforePaging() {
        Seed seed = QuarkusTransaction.requiringNew().call(() -> seed(12));

        PostSearchResult latestPage1 = search(seed.token(), "post", "latest", "", 1, 5, false);
        assertEquals(12, latestPage1.totalCount());
        assertEquals(seed.slugs().get(0), latestPage1.hits().get(0).slug());
        assertEquals(seed.slugs().get(4), latestPage1.hits().get(4).slug());

        PostSearchResult latestPage2 = search(seed.token(), "post", "latest", "", 2, 5, false);
        assertEquals(seed.slugs().get(5), latestPage2.hits().get(0).slug());

        PostSearchResult sevenDays = search(seed.token(), "post", "latest", "7d", 1, 5, false);
        assertEquals(7, sevenDays.totalCount());
        assertEquals(seed.slugs().get(0), sevenDays.hits().get(0).slug());
        assertFalse(sevenDays.hits().stream().anyMatch(hit -> seed.oldSlugs().contains(hit.slug())));

        PostSearchResult hot = search(seed.token(), "post", "hot", "", 1, 3, false);
        assertEquals(seed.slugs().get(0), hot.hits().get(0).slug());
    }

    @Test
    void databaseAdapterAppliesParsedFiltersAndMergesTaxonomy() {
        Seed seed = QuarkusTransaction.requiringNew().call(() -> seed(3));

        assertEquals(3, search(seed.token(), "post", "latest", "", 1, 10, false).totalCount());
        assertEquals(3, search("title:" + seed.token(), "post", "latest", "", 1, 10, false).totalCount());
        assertEquals(0, search("author:nobody-" + seed.token(), "post", "latest", "", 1, 10, false).totalCount());

        PostSearchResult all = search(seed.token(), "all", "latest", "", 1, 20, false);
        assertEquals(4, all.totalCount());
        assertTrue(all.hits().stream().anyMatch(hit -> "tag".equals(hit.type())));
    }

    @Test
    void elasticsearchAdapterPushesSortAndDateBeforePaging() throws Exception {
        Assumptions.assumeTrue(elasticsearchPostSearchService.isAvailable(),
                "Elasticsearch is not running; the query-body unit test covers pushdown");
        elasticsearchPostSearchService.ensurePostIndexReady();

        Seed seed = QuarkusTransaction.requiringNew().call(() -> {
            Seed created = seed(12);
            for (Long id : created.ids()) {
                Post post = Post.findById(id);
                try {
                    elasticsearchPostSearchService.indexPost(post);
                } catch (Exception exception) {
                    throw new IllegalStateException(exception);
                }
            }
            return created;
        });
        elasticsearchPostSearchService.refreshPostIndex("windblog-posts");

        PostSearchResult latestPage1 = search(seed.token(), "post", "latest", "", 1, 5, true);
        assertTrue(latestPage1.usedElasticsearch(), "the ES adapter must serve this query");
        assertEquals(12, latestPage1.totalCount());
        assertEquals(seed.slugs().get(0), latestPage1.hits().get(0).slug());

        PostSearchResult latestPage2 = search(seed.token(), "post", "latest", "", 2, 5, true);
        assertEquals(seed.slugs().get(5), latestPage2.hits().get(0).slug(),
                "page 2 must continue the globally sorted result, not re-sort a truncated page");

        PostSearchResult sevenDays = search(seed.token(), "post", "latest", "7d", 1, 5, true);
        assertEquals(7, sevenDays.totalCount(),
                "the date range must narrow totalCount, proving it ran inside the ES query");
        assertFalse(sevenDays.hits().stream().anyMatch(hit -> seed.oldSlugs().contains(hit.slug())));

        PostSearchResult hot = search(seed.token(), "post", "hot", "", 1, 3, true);
        assertEquals(seed.slugs().get(0), hot.hits().get(0).slug(),
                "hot ordering must come from the pushed viewCount sort, not the fetched page");

        assertEquals(12, search("title:" + seed.token(), "post", "latest", "", 1, 20, true).totalCount());
        assertEquals(12, search("author:" + seed.authorName(), "post", "latest", "", 1, 20, true).totalCount());

        PostSearchResult all = search(seed.token(), "all", "latest", "", 1, 20, true);
        assertEquals(13, all.totalCount(), "type=all must include the same taxonomy rows as the DB adapter");
        assertTrue(all.hits().stream().anyMatch(hit -> "tag".equals(hit.type())));
    }

    @Test
    void searchPageRendersTheModuleResultAsDocumentAndPjaxFragment() {
        Seed seed = QuarkusTransaction.requiringNew().call(() -> seed(3));

        given()
                .when().get("/search?q=" + seed.token() + "&type=post&sort=latest&use-es=false")
                .then()
                .statusCode(200)
                .body(containsString(seed.slugs().get(0)))
                .body(containsString(seed.slugs().get(2)));

        given()
                .header("X-PJAX", "true")
                .when().get("/search?q=" + seed.token() + "&type=post&sort=latest&use-es=false")
                .then()
                .statusCode(200)
                .body(containsString("pjax-head-metadata"))
                .body(containsString(seed.slugs().get(0)));
    }

    private PostSearchResult search(String keyword, String type, String sort, String date,
                                    int page, int pageSize, boolean elasticsearchAllowed) {
        return searchService.search(PostSearchQuery.of(
                keyword, type, sort, date, "zh-cn", BlogRegion.GLOBAL, page, pageSize, elasticsearchAllowed));
    }

    private Seed seed(int count) {
        String token = "srch" + UUID.randomUUID().toString().replaceAll("[^a-f]", "");
        OffsetDateTime now = OffsetDateTime.now();

        User author = new User();
        author.username = "srch-author-" + token;
        author.email = token + "@example.com";
        author.password = "test-password-hash";
        author.status = 1;
        author.persist();

        List<Long> ids = new ArrayList<>();
        List<String> slugs = new ArrayList<>();
        List<String> oldSlugs = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            OffsetDateTime publishedAt = now.minusDays(i);
            Post post = new Post();
            post.slug = token + "-post-" + i;
            post.title = Map.of("zh-cn", token + " 第" + i + "篇");
            post.summary = Map.of("zh-cn", token + " 摘要 " + i);
            post.status = PostStatus.PUBLISHED;
            post.visibility = 0;
            post.renderType = PostRenderType.MARKDOWN;
            post.user = author;
            post.authorName = author.username;
            post.publishedAt = publishedAt;
            post.createdAt = publishedAt;
            post.updatedAt = publishedAt;
            post.viewCount = (long) (count - i) * 10;
            post.persist();

            PostRevision revision = new PostRevision();
            revision.post = post;
            revision.title = post.title;
            revision.contentMarkdown = Map.of("zh-cn", token + " 正文内容 " + i);
            revision.editorType = 0;
            revision.revisionNumber = 1;
            revision.createdBy = author;
            revision.createdAt = publishedAt;
            revision.persist();

            post.currentRevision = revision;
            post.publishedRevision = revision;
            ids.add(post.id);
            slugs.add(post.slug);
            if (i >= 7) {
                oldSlugs.add(post.slug);
            }
        }

        Tag tag = new Tag();
        tag.slug = token + "-tag";
        tag.name = Map.of("zh-cn", token + " 标签");
        tag.description = Map.of("zh-cn", token + " 标签描述");
        tag.persist();

        return new Seed(token, author.username, ids, slugs, oldSlugs);
    }

    private record Seed(String token, String authorName, List<Long> ids, List<String> slugs,
                        List<String> oldSlugs) {
    }
}
