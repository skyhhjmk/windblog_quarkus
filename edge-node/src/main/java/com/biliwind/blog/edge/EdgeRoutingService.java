package com.biliwind.blog.edge;

import com.biliwind.blog.edge.EdgeServiceProto.*;

import com.biliwind.blog.edge.model.Media;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

@ApplicationScoped
public class EdgeRoutingService {
    private static final Logger log = LoggerFactory.getLogger(EdgeRoutingService.class);

    @Inject
    EdgeGrpcClient grpcClient;

    @Inject
    EdgeCacheService cacheService;

    public String getBestAccessUrl(String fileName, String variantType) {
        // 1. Find media by storageKey (extracting from filename if needed)
        String storageKey = extractStorageKey(fileName);

        // Try cache first
        Media media = cacheService.getMedia(storageKey);

        if (media == null) {
            media = Media.find("storageKey = ?1", storageKey).firstResult();
            if (media != null) {
                cacheService.setMedia(media);
            }
        }

        if (media == null) {
            log.warn("Media not found for key: {}", storageKey);
            return null;
        }

        // 2. Get storage config from grpc client
        StorageConfigResponse config = grpcClient.getCurrentConfig();
        if (config == null) {
            log.warn("Storage config not yet available from main node");
            return null;
        }

        // 3. Find first synced node based on priority
        List<StorageNodeConfig> nodes = config.getNodesList();
        // Sort by priority (lower number = higher priority)
        nodes = nodes.stream()
                .sorted((a, b) -> Integer.compare(a.getPriority(), b.getPriority()))
                .toList();

        for (StorageNodeConfig nodeConfig : nodes) {
            String providerName = nodeConfig.getName();
            Object providerDataObj = media.storageProviders.get(providerName);
            if (!(providerDataObj instanceof Map)) {
                continue;
            }

            Map<String, Object> providerData = (Map<String, Object>) providerDataObj;
            Object variantDataObj = providerData.get(variantType.toLowerCase());
            if (!(variantDataObj instanceof Map)) {
                continue;
            }

            Map<String, Object> variantData = (Map<String, Object>) variantDataObj;
            if ("synced".equals(variantData.get("status"))) {
                String path = variantData.get("path").toString();

                if (nodeConfig.getCdnEnabled() && !nodeConfig.getCdnDomain().isEmpty()) {
                    return "https://" + nodeConfig.getCdnDomain() + "/" + path;
                }

                // For local_fs on main node, the edge node can't access it directly
                // except via main node's public URL if available.
                // In a real edge scenario, the edge node might have its own local copy
                // or we use gRPC fallback.

                // If it's the primary node and it's not local, we can return its URL
                if (nodeConfig.getIsPrimary()) {
                    // Simplified: assume it's accessible or use a placeholder
                    return "https://" + providerName + ".example.com/" + path;
                }
            }
        }

        return null;
    }

    private String extractStorageKey(String fileName) {
        // Simplified logic similar to UploadFileController
        if (fileName.contains("_cover.")) {
            return fileName.substring(0, fileName.indexOf("_cover.")) + fileName.substring(fileName.lastIndexOf('.'));
        }
        if (fileName.contains("_p.")) {
            return fileName.substring(0, fileName.indexOf("_p.")) + fileName.substring(fileName.lastIndexOf('.'));
        }
        return fileName;
    }
}
