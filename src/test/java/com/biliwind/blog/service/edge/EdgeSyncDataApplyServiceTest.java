package com.biliwind.blog.service.edge;

import com.biliwind.blog.edge.EdgeServiceProto.SyncDataRequest;
import com.biliwind.blog.model.Category;
import com.biliwind.blog.model.Media;
import com.biliwind.blog.model.Post;
import com.biliwind.blog.model.PostRevision;
import com.biliwind.blog.model.SystemSetting;
import com.biliwind.blog.model.Tag;
import com.biliwind.blog.model.User;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class EdgeSyncDataApplyServiceTest {

    @Inject
    EdgeSyncDataApplyService syncDataApplyService;

    @Inject
    EdgeDataSyncService edgeDataSyncService;

    @Inject
    ObjectMapper objectMapper;

    @Inject
    EntityManager entityManager;

    @Test
    @TestTransaction
    void shouldRejectPrivateEntitiesBeforeDeserializingPayload() {
        String settingKey = "edge-sync-private-test-" + UUID.randomUUID();
        SyncDataRequest request = SyncDataRequest.newBuilder()
                .setAction("UPSERT")
                .setEntityType("SYSTEM_SETTING")
                .setEntityId(1L)
                .setPayload("{\"configKey\":\"" + settingKey
                        + "\",\"configValue\":{\"apiSecret\":\"must-not-sync\"}}")
                .build();

        syncDataApplyService.apply(request);

        assertNull(SystemSetting.findByKey(settingKey));
    }

    @Test
    @TestTransaction
    void shouldUsePublicWhitelistForEveryFullSyncPayload() throws Exception {
        for (EdgeDataSyncService.FullSyncItem item : edgeDataSyncService.buildPublicFullSyncSnapshot(false)) {
            JsonNode payload = objectMapper.readTree(item.payload());
            assertNoSensitiveField(payload, "password");
            assertNoSensitiveField(payload, "configValue");
            assertNoSensitiveField(payload, "deletedAt");
            assertNoSensitiveField(payload, "processingError");
        }
    }

    @Test
    @TestTransaction
    void shouldReadFullSyncThroughBoundedKeysetBatch() {
        EdgeDataSyncService.PublicFullSyncCounts counts = edgeDataSyncService.loadPublicFullSyncCounts();
        assertTrue(counts.total() >= 0);
        assertTrue(edgeDataSyncService.loadPublicFullSyncBatch(false, "TAG", 0L, 200).size() <= 200);
        assertTrue(edgeDataSyncService.loadPublicFullSyncBatch(false, "POST", 0L, 200).size() <= 200);
    }

    @Test
    @TestTransaction
    void shouldApplyMediaThroughPublicWhitelistOnly() {
        Media media = new Media();
        media.storageKey = "edge-test-original";
        media.url = "https://cdn.example.test/old.jpg";
        media.mediaType = 0;
        media.processingError = "local failure must stay local";
        media.metadata = Map.of("privateKey", "must stay local");
        media.storageClasses = Map.of();
        entityManager.persist(media);
        entityManager.flush();

        long mediaId = media.id;
        SyncDataRequest request = SyncDataRequest.newBuilder()
                .setAction("UPSERT")
                .setEntityType("MEDIA")
                .setEntityId(mediaId)
                .setPayload("""
                        {
                          "id": %d,
                          "storageKey": "edge-test-derived",
                          "url": "https://cdn.example.test/new.jpg",
                          "mediaType": 0,
                          "mimeType": "image/jpeg",
                          "metadata": {
                            "thumbnailUrl": "https://cdn.example.test/thumb.jpg",
                            "privateKey": "must not sync"
                          },
                          "storageClasses": {
                            "local": {
                              "original": {
                                "status": "READY",
                                "path": "safe/path",
                                "secret": "must not sync"
                              }
                            }
                          },
                          "processingStatus": "COMPLETED",
                          "processingProgress": 100,
                          "processingError": "attacker-controlled error",
                          "deletedAt": "2026-01-01T00:00:00Z",
                          "createdAt": "2026-01-01T00:00:00Z",
                          "updatedAt": "2026-01-01T00:00:00Z"
                        }
                        """.formatted(mediaId))
                .build();

        syncDataApplyService.apply(request);
        entityManager.flush();
        entityManager.clear();

        Media updated = Media.findById(mediaId);
        assertEquals("https://cdn.example.test/new.jpg", updated.url);
        assertEquals("local failure must stay local", updated.processingError);
        assertNull(updated.deletedAt);
        assertEquals("https://cdn.example.test/thumb.jpg", updated.metadata.get("thumbnailUrl"));
        assertFalse(updated.metadata.containsKey("privateKey"));
        Map<?, ?> localStorage = (Map<?, ?>) updated.storageClasses.get("local");
        Map<?, ?> originalVariant = (Map<?, ?>) localStorage.get("original");
        assertEquals("safe/path", originalVariant.get("path"));
        assertFalse(originalVariant.containsKey("secret"));
    }

    @Test
    @TestTransaction
    void shouldNotResurrectLocallyDeletedMediaFromPublicSnapshot() {
        Media media = new Media();
        media.storageKey = "edge-deleted-original";
        media.url = "https://cdn.example.test/deleted.jpg";
        media.mediaType = 0;
        media.deletedAt = OffsetDateTime.now();
        media.storageClasses = Map.of();
        entityManager.persist(media);
        entityManager.flush();

        long mediaId = media.id;
        SyncDataRequest request = SyncDataRequest.newBuilder()
                .setAction("UPSERT")
                .setEntityType("MEDIA")
                .setEntityId(mediaId)
                .setPayload("""
                        {
                          "id": %d,
                          "storageKey": "should-not-restore",
                          "url": "https://cdn.example.test/restore.jpg",
                          "mediaType": 0
                        }
                        """.formatted(mediaId))
                .build();

        syncDataApplyService.apply(request);
        entityManager.clear();

        Media unchanged = Media.findById(mediaId);
        assertEquals("https://cdn.example.test/deleted.jpg", unchanged.url);
        assertTrue(unchanged.deletedAt != null);
    }

    @Test
    @TestTransaction
    void shouldCreateNewMediaWithEmptyStorageClassMapWhenSnapshotOmitsIt() {
        long mediaId = 900_000_000L + UUID.randomUUID().getMostSignificantBits() % 100_000_000L;
        if (mediaId <= 0) {
            mediaId = 900_000_001L;
        }
        SyncDataRequest request = SyncDataRequest.newBuilder()
                .setAction("UPSERT")
                .setEntityType("MEDIA")
                .setEntityId(mediaId)
                .setPayload("""
                        {
                          "id": %d,
                          "storageKey": "edge-new-original",
                          "url": "https://cdn.example.test/new-media.jpg",
                          "mediaType": 0
                        }
                        """.formatted(mediaId))
                .build();

        syncDataApplyService.apply(request);
        entityManager.flush();
        entityManager.clear();

        Media created = Media.findById(mediaId);
        assertNotNull(created);
        assertNotNull(created.storageClasses);
        assertTrue(created.storageClasses.isEmpty());
    }

    @Test
    @TestTransaction
    void shouldCreateNewTagAndCategoryWithSynchronizedIds() {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        long tagId = 910_000_000L + UUID.randomUUID().getMostSignificantBits() % 10_000_000L;
        long categoryId = tagId + 1;
        if (tagId <= 0) {
            tagId = 910_000_001L;
            categoryId = 910_000_002L;
        }

        SyncDataRequest tagRequest = SyncDataRequest.newBuilder()
                .setAction("UPSERT")
                .setEntityType("TAG")
                .setEntityId(tagId)
                .setPayload("""
                        {
                          "id": %d,
                          "slug": "edge-tag-%s",
                          "name": {"zh-CN": "边缘标签"},
                          "description": {"zh-CN": "公开描述"},
                          "password": "must-not-sync"
                        }
                        """.formatted(tagId, suffix))
                .build();
        SyncDataRequest categoryRequest = SyncDataRequest.newBuilder()
                .setAction("UPSERT")
                .setEntityType("CATEGORY")
                .setEntityId(categoryId)
                .setPayload("""
                        {
                          "id": %d,
                          "parent": null,
                          "slug": "edge-category-%s",
                          "name": {"zh-CN": "边缘分类"},
                          "description": {"zh-CN": "公开描述"},
                          "path": "edge_%s",
                          "postCount": 3,
                          "configValue": {"secret": "must-not-sync"}
                        }
                        """.formatted(categoryId, suffix, suffix))
                .build();

        syncDataApplyService.apply(tagRequest);
        syncDataApplyService.apply(categoryRequest);
        entityManager.flush();
        entityManager.clear();

        Tag tag = Tag.findById(tagId);
        Category category = Category.findById(categoryId);
        assertNotNull(tag);
        assertEquals("edge-tag-" + suffix, tag.slug);
        assertNotNull(category);
        assertEquals("edge-category-" + suffix, category.slug);
        assertEquals(3L, category.postCount);
    }

    @Test
    @TestTransaction
    void shouldCreateNewPostAndPublicRevisionWithoutPassword() {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        long postId = 920_000_000L + UUID.randomUUID().getMostSignificantBits() % 10_000_000L;
        long revisionId = postId + 1;
        if (postId <= 0) {
            postId = 920_000_001L;
            revisionId = 920_000_002L;
        }

        User user = new User();
        user.username = "edge-sync-user-" + suffix;
        user.email = "edge-sync-user-" + suffix + "@example.com";
        user.password = "test-password-hash";
        user.status = 1;
        entityManager.persist(user);
        entityManager.flush();

        SyncDataRequest request = SyncDataRequest.newBuilder()
                .setAction("UPSERT")
                .setEntityType("POST")
                .setEntityId(postId)
                .setPayload("""
                        {
                          "post": {
                            "id": %d,
                            "slug": "edge-post-%s",
                            "title": {"zh-CN": "公开文章"},
                            "summary": {"zh-CN": "摘要"},
                            "aiSummary": {},
                            "aiSummaryStatus": 0,
                            "status": "PUBLISHED",
                            "visibility": 0,
                            "seoTitle": "标题",
                            "seoKeywords": "关键词",
                            "seoDescription": "描述",
                            "renderType": "MARKDOWN",
                            "user": {"id": %d},
                            "category": null,
                            "extraInfo": {"free_lines": 5, "secret": "must-not-sync"},
                            "visibilityRegions": null,
                            "contentDeclarations": ["AI_ASSISTED"],
                            "publishedAt": "2026-08-03T00:00:00Z",
                            "viewCount": 7,
                            "featured": false,
                            "allowComment": true,
                            "version": 0,
                            "password": "must-not-sync",
                            "deletedAt": "2026-01-01T00:00:00Z"
                          },
                          "currentRevision": {
                            "id": %d,
                            "title": {"zh-CN": "公开文章"},
                            "contentMarkdown": {"zh-CN": "公开预览"},
                            "editorType": 0,
                            "revisionNumber": 1,
                            "createdBy": {"id": %d},
                            "password": "must-not-sync"
                          }
                        }
                        """.formatted(postId, suffix, user.id, revisionId, user.id))
                .build();

        syncDataApplyService.apply(request);
        entityManager.flush();
        entityManager.clear();

        Post post = Post.findById(postId);
        PostRevision revision = PostRevision.findById(revisionId);
        assertNotNull(post);
        assertEquals("edge-post-" + suffix, post.slug);
        assertNull(post.password);
        Map<?, ?> extraInfo = (Map<?, ?>) post.extraInfo;
        assertEquals(5, ((Number) extraInfo.get("free_lines")).intValue());
        assertNotNull(revision);
        assertEquals(postId, revision.post.id);
    }

    private void assertNoSensitiveField(JsonNode node, String fieldName) {
        if (node.isObject()) {
            org.junit.jupiter.api.Assertions.assertFalse(node.has(fieldName),
                    "edge snapshot contains forbidden field: " + fieldName);
            node.fields().forEachRemaining(entry -> assertNoSensitiveField(entry.getValue(), fieldName));
        } else if (node.isArray()) {
            for (JsonNode child : node) {
                assertNoSensitiveField(child, fieldName);
            }
        }
    }
}
