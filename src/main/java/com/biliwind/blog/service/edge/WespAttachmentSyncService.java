package com.biliwind.blog.service.edge;

import com.biliwind.blog.model.Media;
import com.biliwind.blog.service.storage.StorageService;
import com.biliwind.blog.service.storage.VariantType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

/**
 * Converts media originals into resumable WESP manifests and content-addressed
 * blocks.  The scanner is intentionally small and finite so an edge node can
 * keep operating while its peer is offline; the manifest remains in the local
 * outbox until the next active session.
 */
@ApplicationScoped
public class WespAttachmentSyncService {
    private static final Logger LOG = LoggerFactory.getLogger(WespAttachmentSyncService.class);
    private static final int BLOCK_SIZE = 512 * 1024;
    private static final int MAX_MEDIA_PER_RUN = 2;

    @Inject WespSyncService wespSyncService;
    @Inject StorageService storageService;
    @Inject ObjectMapper mapper;
    @Inject com.biliwind.blog.service.PostAccessService postAccessService;

    @Scheduled(every = "1m", identity = "wesp-attachment-manifest-scan")
    void scan() {
        if (!wespSyncService.isEnabled()) return;
        List<Long> mediaIds;
        try {
            mediaIds = io.quarkus.narayana.jta.QuarkusTransaction.requiringNew().call(() ->
                    Media.<Media>find("deletedAt is null order by id").range(0, 49)
                            .list().stream().map(media -> media.id).toList());
        } catch (RuntimeException exception) {
            LOG.debug("WESP 附件扫描暂不可用: {}", exception.getMessage());
            return;
        }
        int created = 0;
        for (Long mediaId : mediaIds) {
            if (isProtectedMedia(mediaId)) continue;
            Optional<String> existing = wespSyncService.findManifestPayload(Long.toString(mediaId), "ORIGINAL");
            if (existing.isPresent()) {
                if (!wespSyncService.hasLocalOperation("MEDIA_MANIFEST", Long.toString(mediaId), existing.get())) {
                    wespSyncService.republishManifestBlocks(existing.get());
                    wespSyncService.enqueueLocalOperation("MEDIA_MANIFEST", "UPDATE", Long.toString(mediaId), existing.get());
                }
                continue;
            }
            if (created >= MAX_MEDIA_PER_RUN) break;
            try {
                syncOne(mediaId);
                created++;
            } catch (Exception exception) {
                LOG.warn("WESP 附件 {} 清单生成失败: {}", mediaId, exception.getMessage());
            }
        }
    }

    private boolean isProtectedMedia(Long mediaId) {
        try {
            return io.quarkus.narayana.jta.QuarkusTransaction.requiringNew()
                    .call(() -> postAccessService.hasProtectedMediaReference(mediaId));
        } catch (RuntimeException exception) {
            // Fail closed if the protection query cannot be evaluated.
            LOG.warn("WESP 无法确认媒体 {} 的访问保护状态，跳过附件同步", mediaId);
            return true;
        }
    }

    private void syncOne(Long mediaId) throws Exception {
        Optional<String> existing = wespSyncService.findManifestPayload(Long.toString(mediaId), "ORIGINAL");
        if (existing.isPresent()) {
            if (!wespSyncService.hasLocalOperation("MEDIA_MANIFEST", Long.toString(mediaId), existing.get())) {
                wespSyncService.republishManifestBlocks(existing.get());
                wespSyncService.enqueueLocalOperation("MEDIA_MANIFEST", "UPDATE", Long.toString(mediaId), existing.get());
            }
            return;
        }

        Media media = io.quarkus.narayana.jta.QuarkusTransaction.requiringNew().call(() -> Media.findById(mediaId));
        if (media == null || media.deletedAt != null) return;
        if (isProtectedMedia(mediaId)) {
            // Never turn a private/password-gated attachment into a public
            // WESP block merely because it exists in the local media table.
            return;
        }
        ObjectNode manifest = mapper.createObjectNode();
        manifest.put("schema_version", 1);
        manifest.put("media_id", Long.toString(media.id));
        manifest.put("variant", VariantType.ORIGINAL.name());
        manifest.putObject("chunking").put("algorithm", "fixed").put("block_size", BLOCK_SIZE);
        ArrayNode blocks = manifest.putArray("blocks");
        MessageDigest fileDigest = MessageDigest.getInstance("SHA-256");
        long total = 0;
        try (InputStream input = storageService.fallbackDownload(media, VariantType.ORIGINAL)) {
            byte[] buffer = new byte[BLOCK_SIZE];
            int read;
            while ((read = readChunk(input, buffer)) > 0) {
                byte[] block = read == buffer.length ? buffer.clone() : java.util.Arrays.copyOf(buffer, read);
                fileDigest.update(block);
                String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(block));
                wespSyncService.putBlock(hash, block);
                blocks.addObject().put("hash", hash).put("size", read);
                total += read;
            }
        }
        String fileHash = HexFormat.of().formatHex(fileDigest.digest());
        manifest.put("file_hash", fileHash);
        manifest.put("file_size", total);
        String unsignedPayload = mapper.writeValueAsString(manifest);
        manifest.put("manifest_id", wespSyncService.computeManifestId(unsignedPayload));
        String payload = mapper.writeValueAsString(manifest);
        if (payload.getBytes(StandardCharsets.UTF_8).length > 65_536) {
            LOG.warn("WESP 媒体 {} 的清单超过 64 KiB，当前版本跳过并等待分页清单实现", mediaId);
            return;
        }
        wespSyncService.saveManifest(payload);
        wespSyncService.republishManifestBlocks(payload);
        wespSyncService.enqueueLocalOperation("MEDIA_MANIFEST", "UPDATE", Long.toString(media.id), payload);
    }

    private static int readChunk(InputStream input, byte[] buffer) throws java.io.IOException {
        int offset = 0;
        while (offset < buffer.length) {
            int read = input.read(buffer, offset, buffer.length - offset);
            if (read < 0) break;
            if (read == 0) continue;
            offset += read;
        }
        return offset;
    }

}
