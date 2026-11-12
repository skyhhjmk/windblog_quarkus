package com.biliwind.blog.service.edge;

import com.biliwind.blog.edge.EdgeServiceProto.SyncDataRequest;
import com.biliwind.blog.model.EdgeSyncRecord;
import com.biliwind.blog.model.Media;
import com.biliwind.blog.model.Post;
import com.biliwind.blog.model.RegionRule;
import com.biliwind.blog.model.SystemSetting;
import com.biliwind.blog.model.Tag;
import com.biliwind.blog.model.User;
import com.biliwind.blog.model.WespSyncOperation;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class WespMultiNodeSyncFixesTest {

    @Inject
    WespSyncService wespSyncService;

    @Inject
    EdgeSyncDataApplyService syncDataApplyService;

    @Inject
    EdgeDataSyncService edgeDataSyncService;

    @Inject
    ObjectMapper objectMapper;

    @Inject
    EntityManager entityManager;

    private ObjectNode createOperationEnvelope(String opId, String nodeId, String entityType,
                                               String action, String entityId, String payload,
                                               long seq) {
        WespSyncOperation op = new WespSyncOperation();
        op.opId = opId;
        op.nodeId = nodeId;
        op.datasetId = wespSyncService.bootstrapDatasetId();
        op.incarnation = "test-incarnation";
        op.seq = seq;
        op.entityType = entityType;
        op.action = action;
        op.entityId = entityId;
        String safePayload = wespSyncService.normalizePayload(payload, false);
        op.payload = safePayload;
        op.payloadHash = WespSyncService.sha256(safePayload);
        op.createdAt = OffsetDateTime.now(ZoneOffset.UTC);
        return wespSyncService.operationJson(op);
    }

    private String buildBatch(List<ObjectNode> operations) throws Exception {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("batch_id", UUID.randomUUID().toString());
        ArrayNode ops = root.putArray("operations");
        for (ObjectNode op : operations) {
            ops.add(op);
        }
        return objectMapper.writeValueAsString(root);
    }

    @Test
    @TestTransaction
    void shouldAcceptSystemSettingAndRegionRuleInWespBatch() throws Exception {
        String key = "test_wesp_setting_" + UUID.randomUUID();
        String settingPayload = "{\"configKey\":\"" + key + "\",\"configValue\":{\"enabled\":true},\"version\":1}";
        ObjectNode settingOp = createOperationEnvelope(
                UUID.randomUUID().toString(), "remote-node-1", "SYSTEM_SETTING", "UPSERT", "1", settingPayload, 1);

        String rulePayload = "{\"id\":99881,\"name\":\"Test Rule\",\"ruleType\":\"HOST\",\"pattern\":\"*.example.com\",\"region\":\"CN\",\"priority\":10,\"isEnabled\":true}";
        ObjectNode ruleOp = createOperationEnvelope(
                UUID.randomUUID().toString(), "remote-node-1", "REGION_RULE", "UPSERT", "99881", rulePayload, 2);

        String batchJson = buildBatch(List.of(settingOp, ruleOp));
        Map<String, Object> result = wespSyncService.receiveBatch(batchJson);

        assertEquals("RECEIVED", result.get("status"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> opResults = (List<Map<String, Object>>) result.get("results");
        assertEquals(2, opResults.size());
        assertEquals("APPLIED", opResults.get(0).get("status"), "Expected APPLIED, error: " + opResults.get(0).get("error"));
        assertEquals("APPLIED", opResults.get(1).get("status"), "Expected APPLIED, error: " + opResults.get(1).get("error"));

        assertNotNull(SystemSetting.findByKey(key));
        assertNotNull(RegionRule.findById(99881L));
    }

    @Test
    @TestTransaction
    void shouldPreventPingPongLoopWhenRemoteSyncEventFired() {
        long opCountBefore = WespSyncOperation.count();

        // When remoteSync is true (event fired after applying inbound sync)
        PostSyncedEvent remotePostEvent = new PostSyncedEvent(12345L, true);
        edgeDataSyncService.onPostSynced(remotePostEvent);

        DataSyncEvent remoteDataEvent = new DataSyncEvent("TAG", 6789L, "UPSERT", true);
        edgeDataSyncService.onDataChanged(remoteDataEvent);

        long opCountAfter = WespSyncOperation.count();
        assertEquals(opCountBefore, opCountAfter, "Remote sync events must NOT re-enqueue outbound sync operations");
    }

    @Test
    @TestTransaction
    void shouldFallbackAuthorWhenUserDoesNotExistOnEdgeNode() {
        long nonExistentUserId = 9999998888L;
        assertNull(User.findById(nonExistentUserId));

        String postPayload = """
                {
                    "post": {
                        "id": 887766,
                        "slug": "test-edge-author-fallback",
                        "title": {"zh-CN": "测试作者回退"},
                        "status": "PUBLISHED",
                        "visibility": 0,
                        "renderType": "MARKDOWN",
                        "user": {"id": %d},
                        "authorName": "Original Author",
                        "version": 1
                    },
                    "currentRevision": {
                        "id": 8877661,
                        "title": {"zh-CN": "测试作者回退"},
                        "contentMarkdown": {"zh-CN": "测试正文"},
                        "editorType": 1,
                        "revisionNumber": 1,
                        "createdBy": {"id": %d}
                    }
                }
                """.formatted(nonExistentUserId, nonExistentUserId);

        SyncDataRequest request = SyncDataRequest.newBuilder()
                .setAction("UPSERT")
                .setEntityType("POST")
                .setEntityId(887766L)
                .setPayload(postPayload)
                .build();

        // Must succeed without foreign key constraint failure
        syncDataApplyService.apply(request);

        Post applied = Post.findById(887766L);
        assertNotNull(applied);
        assertNotNull(applied.user, "Author must be resolved to a valid local user");
        assertEquals("Original Author", applied.authorName);
    }

    @Test
    @TestTransaction
    void shouldDetectConcurrentRevisionWhenSameVersionHasDifferentHash() throws Exception {
        String entityId = "776655";
        String payloadA = """
                {
                    "post": {
                        "id": 776655,
                        "slug": "test-post-concurrent",
                        "title": {"zh-CN": "Version A"},
                        "status": "PUBLISHED",
                        "visibility": 0,
                        "renderType": "MARKDOWN",
                        "user": {"id": 1},
                        "authorName": "Author A",
                        "version": 2
                    },
                    "currentRevision": {
                        "id": 7766551,
                        "title": {"zh-CN": "Version A"},
                        "contentMarkdown": {"zh-CN": "Content A"},
                        "editorType": 1,
                        "revisionNumber": 2,
                        "createdBy": {"id": 1}
                    }
                }
                """;
        String payloadB = """
                {
                    "post": {
                        "id": 776655,
                        "slug": "test-post-concurrent",
                        "title": {"zh-CN": "Version B Concurrently"},
                        "status": "PUBLISHED",
                        "visibility": 0,
                        "renderType": "MARKDOWN",
                        "user": {"id": 1},
                        "authorName": "Author B",
                        "version": 2
                    },
                    "currentRevision": {
                        "id": 7766552,
                        "title": {"zh-CN": "Version B Concurrently"},
                        "contentMarkdown": {"zh-CN": "Content B"},
                        "editorType": 1,
                        "revisionNumber": 2,
                        "createdBy": {"id": 1}
                    }
                }
                """;

        ObjectNode opA = createOperationEnvelope(
                UUID.randomUUID().toString(), "node-A", "POST", "UPSERT", entityId, payloadA, 10);
        ObjectNode opB = createOperationEnvelope(
                UUID.randomUUID().toString(), "node-B", "POST", "UPSERT", entityId, payloadB, 20);

        // Receive opA
        Map<String, Object> batchA = wespSyncService.receiveBatch(buildBatch(List.of(opA)));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> resA = (List<Map<String, Object>>) batchA.get("results");
        assertEquals("APPLIED", resA.get(0).get("status"), "Expected APPLIED, error: " + resA.get(0).get("error"));

        // Receive opB with same version=2 but different content/hash from another node
        Map<String, Object> batchB = wespSyncService.receiveBatch(buildBatch(List.of(opB)));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> resB = (List<Map<String, Object>>) batchB.get("results");
        assertEquals("CONFLICT", resB.get(0).get("status"));
        assertEquals("CONCURRENT_REVISION", resB.get(0).get("error"));
    }

    @Test
    @TestTransaction
    void shouldAdvanceCursorPastNonAppliedOperationsInGetChanges() {
        // Create an APPLIED operation
        WespSyncOperation op1 = new WespSyncOperation();
        op1.opId = UUID.randomUUID().toString();
        op1.nodeId = "local";
        op1.datasetId = "public";
        op1.incarnation = "inc1";
        op1.seq = 101;
        op1.entityType = "TAG";
        op1.action = "UPSERT";
        op1.entityId = "1";
        op1.payload = "{\"id\":1,\"name\":{\"zh-CN\":\"T1\"}}";
        op1.payloadHash = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
        op1.status = "APPLIED";
        op1.createdAt = OffsetDateTime.now(ZoneOffset.UTC);
        op1.updatedAt = op1.createdAt;
        op1.persist();

        // Create a REJECTED operation
        WespSyncOperation op2 = new WespSyncOperation();
        op2.opId = UUID.randomUUID().toString();
        op2.nodeId = "local";
        op2.datasetId = "public";
        op2.incarnation = "inc1";
        op2.seq = 102;
        op2.entityType = "TAG";
        op2.action = "UPSERT";
        op2.entityId = "2";
        op2.payload = "{\"id\":2,\"name\":{\"zh-CN\":\"T2\"}}";
        op2.payloadHash = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
        op2.status = "REJECTED";
        op2.createdAt = OffsetDateTime.now(ZoneOffset.UTC);
        op2.updatedAt = op2.createdAt;
        op2.persist();

        Map<String, Object> changes = wespSyncService.getChanges(op1.rowId - 1, 10);
        ArrayNode items = (ArrayNode) changes.get("items");
        // items should only contain op1, but next_cursor should reach op2
        assertEquals(1, items.size());
        assertEquals(Long.toString(op2.rowId), changes.get("next_cursor"));
    }

    @Test
    @TestTransaction
    void shouldReturnWespSyncRecordsAndProgress() {
        String testNode = "test-wesp-edge-" + UUID.randomUUID();
        EdgeDataSyncService.SyncProgress progress = wespSyncService.getSyncProgress(testNode);
        assertNotNull(progress);

        List<EdgeSyncRecord> records = wespSyncService.getSyncRecords(testNode, null, null, 10);
        assertNotNull(records);
    }

    @Test
    @TestTransaction
    void shouldStoreIncomingMediaVersionDuringUpsert() {
        long mediaId = 88991122L;
        String mediaPayload = """
                {
                    "id": %d,
                    "storageKey": "test-key-versioned",
                    "url": "https://cdn.example.test/img.png",
                    "mediaType": 1,
                    "version": 7
                }
                """.formatted(mediaId);

        SyncDataRequest request = SyncDataRequest.newBuilder()
                .setAction("UPSERT")
                .setEntityType("MEDIA")
                .setEntityId(mediaId)
                .setPayload(mediaPayload)
                .build();

        syncDataApplyService.apply(request);

        Media media = Media.findById(mediaId);
        assertNotNull(media);
        assertEquals(7, media.version, "Incoming media version must be stored on edge");
    }

    @Test
    @TestTransaction
    void shouldReturnPerOperationResultsWhenTheSameBatchIsReplayed() throws Exception {
        long tagId = 993100000L + Math.abs(UUID.randomUUID().getMostSignificantBits() % 100000L);
        String payload = "{\"id\":" + tagId + ",\"slug\":\"replay-" + tagId
                + "\",\"name\":{\"zh-CN\":\"重放\"}}";
        ObjectNode operation = createOperationEnvelope(UUID.randomUUID().toString(), "replay-node",
                "TAG", "UPSERT", Long.toString(tagId), payload, 1);
        String batch = buildBatch(List.of(operation));

        Map<String, Object> first = wespSyncService.receiveBatch(batch);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> firstResults = (List<Map<String, Object>>) first.get("results");
        assertEquals("APPLIED", firstResults.get(0).get("status"));
        assertNotNull(Tag.findById(tagId));

        Map<String, Object> replay = wespSyncService.receiveBatch(batch);
        assertEquals("DUPLICATE", replay.get("status"));
        assertEquals(Boolean.TRUE, replay.get("duplicate"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> replayResults = (List<Map<String, Object>>) replay.get("results");
        assertEquals(1, replayResults.size(),
                "A replay must answer each operation instead of returning an indistinguishable empty list");
        assertEquals("DUPLICATE", replayResults.get(0).get("status"));
        assertEquals(1L, Tag.count("id = ?1", tagId), "Replaying a batch must not apply it twice");
    }

    @Test
    @TestTransaction
    void shouldNotSkipASequenceThatArrivesOutOfOrder() throws Exception {
        long firstTag = 993200001L;
        long missingTag = 993200002L;
        long laterTag = 993200003L;
        ObjectNode firstOperation = createOperationEnvelope(UUID.randomUUID().toString(), "order-node",
                "TAG", "UPSERT", Long.toString(firstTag), tagPayload(firstTag), 1);
        ObjectNode missingOperation = createOperationEnvelope(UUID.randomUUID().toString(), "order-node",
                "TAG", "UPSERT", Long.toString(missingTag), tagPayload(missingTag), 2);
        ObjectNode laterOperation = createOperationEnvelope(UUID.randomUUID().toString(), "order-node",
                "TAG", "UPSERT", Long.toString(laterTag), tagPayload(laterTag), 3);

        wespSyncService.receiveBatch(buildBatch(List.of(firstOperation)));

        Map<String, Object> gap = wespSyncService.receiveBatch(buildBatch(List.of(laterOperation)));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> gapResults = (List<Map<String, Object>>) gap.get("results");
        assertEquals("WAITING_DEPENDENCY", gapResults.get(0).get("status"));
        assertNull(Tag.findById(laterTag), "Sequence 3 must wait for the missing sequence 2");

        Map<String, Object> filled = wespSyncService.receiveBatch(buildBatch(List.of(missingOperation)));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> filledResults = (List<Map<String, Object>>) filled.get("results");
        assertEquals("APPLIED", filledResults.get(0).get("status"));
        assertNotNull(Tag.findById(missingTag));
        assertNotNull(Tag.findById(laterTag), "The buffered sequence must be drained after the gap is filled");
        WespSyncOperation laterApplied = WespSyncOperation.find(
                "nodeId = ?1 and seq = ?2", "order-node", 3L).firstResult();
        assertEquals("APPLIED", laterApplied.status);
    }

    @Test
    @TestTransaction
    void shouldSkipStalePostAndSystemSettingVersionsAtTheApplySeam() {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        User user = new User();
        user.username = "version-user-" + suffix;
        user.email = "version-user-" + suffix + "@example.com";
        user.password = "test-password-hash";
        user.status = 1;
        entityManager.persist(user);
        entityManager.flush();

        long postId = 993300001L;
        EdgeSyncEntity.ApplyOutcome firstPost = syncDataApplyService.apply(SyncDataRequest.newBuilder()
                .setAction("UPSERT").setEntityType("POST").setEntityId(postId)
                .setPayload(postVersionPayload(postId, 5, "version-five", user.id)).build());
        EdgeSyncEntity.ApplyOutcome stalePost = syncDataApplyService.apply(SyncDataRequest.newBuilder()
                .setAction("UPSERT").setEntityType("POST").setEntityId(postId)
                .setPayload(postVersionPayload(postId, 4, "version-four", user.id)).build());
        entityManager.flush();
        entityManager.clear();
        assertEquals(EdgeSyncEntity.ApplyOutcome.APPLIED, firstPost);
        assertEquals(EdgeSyncEntity.ApplyOutcome.SKIPPED_STALE, stalePost);
        Post appliedPost = Post.findById(postId);
        assertEquals("version-five", appliedPost.slug);

        String settingKey = "version-setting-" + suffix;
        EdgeSyncEntity.ApplyOutcome firstSetting = syncDataApplyService.apply(SyncDataRequest.newBuilder()
                .setAction("UPSERT").setEntityType("SYSTEM_SETTING").setEntityId(1L)
                .setPayload("{\"configKey\":\"" + settingKey + "\",\"configValue\":{\"value\":3},\"version\":3}")
                .build());
        EdgeSyncEntity.ApplyOutcome staleSetting = syncDataApplyService.apply(SyncDataRequest.newBuilder()
                .setAction("UPSERT").setEntityType("SYSTEM_SETTING").setEntityId(1L)
                .setPayload("{\"configKey\":\"" + settingKey + "\",\"configValue\":{\"value\":2},\"version\":2}")
                .build());
        entityManager.clear();
        assertEquals(EdgeSyncEntity.ApplyOutcome.APPLIED, firstSetting);
        assertEquals(EdgeSyncEntity.ApplyOutcome.SKIPPED_STALE, staleSetting);
        assertEquals(3, SystemSetting.findByKey(settingKey).configValue.path("value").asInt());
    }

    private String tagPayload(long tagId) {
        return "{\"id\":" + tagId + ",\"slug\":\"order-" + tagId
                + "\",\"name\":{\"zh-CN\":\"顺序\"}}";
    }

    private String postVersionPayload(long postId, int version, String slug, long userId) {
        return """
                {
                  "post": {
                    "id": %d,
                    "slug": "%s",
                    "title": {"zh-CN": "%s"},
                    "status": "PUBLISHED",
                    "visibility": 0,
                    "renderType": "MARKDOWN",
                    "user": {"id": %d},
                    "authorName": "Version Author",
                    "version": %d
                  },
                  "currentRevision": {
                    "id": %d,
                    "title": {"zh-CN": "%s"},
                    "contentMarkdown": {"zh-CN": "正文"},
                    "editorType": 1,
                    "revisionNumber": %d,
                    "createdBy": {"id": %d}
                  }
                }
                """.formatted(postId, slug, slug, userId, version, postId + version, slug, version, userId);
    }
}
