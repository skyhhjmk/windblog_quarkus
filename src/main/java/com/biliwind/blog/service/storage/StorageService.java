package com.biliwind.blog.service.storage;

import com.biliwind.blog.model.Media;
import com.biliwind.blog.model.StorageProviderEntity;
import com.biliwind.blog.service.storage.dto.StorageSyncMessage;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.eclipse.microprofile.reactive.messaging.Channel;
import org.eclipse.microprofile.reactive.messaging.Emitter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.util.*;

@ApplicationScoped
public class StorageService {

    private static final Logger log = LoggerFactory.getLogger(StorageService.class);

    private final List<StorageProvider> enabledProviders = new ArrayList<>();
    @Inject
    ObjectMapper objectMapper;
    @Inject
    EntityManager entityManager;
    @Inject
    @Channel("storage-sync-tasks")
    Emitter<StorageSyncMessage> syncEmitter;
    private StorageProvider primaryProvider;

    @PostConstruct
    @Transactional
    void init() {
        long count = StorageProviderEntity.count();
        if (count == 0) {
            log.info("No storage providers found, initializing default local_fs provider...");
            String rootPath = org.eclipse.microprofile.config.ConfigProvider.getConfig()
                    .getOptionalValue("storage.path", String.class)
                    .orElse(org.eclipse.microprofile.config.ConfigProvider.getConfig()
                            .getOptionalValue("media.upload.dir", String.class).orElse("./uploads"));
            String baseUrl = org.eclipse.microprofile.config.ConfigProvider.getConfig()
                    .getOptionalValue("media.upload.path", String.class).orElse("/uploads");

            StorageProviderEntity defaultProvider = new StorageProviderEntity();
            defaultProvider.name = "local";
            defaultProvider.displayName = "默认本地存储";
            defaultProvider.providerType = "local_fs";
            defaultProvider.isEnabled = true;
            defaultProvider.isPrimary = true;
            defaultProvider.role = "primary";
            defaultProvider.supportedTypes = "[\"*\"]";

            Map<String, String> configMap = new HashMap<>();
            configMap.put("rootPath", rootPath);
            configMap.put("baseUrl", baseUrl);
            try {
                defaultProvider.configJson = objectMapper.writeValueAsString(configMap);
            } catch (Exception e) {
                defaultProvider.configJson = "{\"rootPath\":\"" + rootPath + "\",\"baseUrl\":\"" + baseUrl + "\"}";
            }
            defaultProvider.persist();
        }

        List<StorageProviderEntity> entities = StorageProviderEntity.list("isEnabled = true ORDER BY priority ASC");
        for (StorageProviderEntity entity : entities) {
            StorageProvider provider = createProvider(entity);
            if (provider == null) {
                continue;
            }
            try {
                JsonNode configJson = objectMapper.readTree(entity.configJson);
                ArrayList<String> supportedTypes = parseSupportedTypes(entity.supportedTypes);
                StorageProviderConfig config = new StorageProviderConfig(
                        configJson, supportedTypes, entity.cdnDomain, entity.cdnEnabled);
                provider.initialize(config);

                enabledProviders.add(provider);
                if (entity.isPrimary != null && entity.isPrimary) {
                    if (primaryProvider != null) {
                        throw new IllegalStateException("Multiple primary storage providers found!");
                    }
                    primaryProvider = provider;
                }
            } catch (Exception e) {
                log.error("Failed to initialize storage provider: {}", entity.name, e);
            }
        }

        if (primaryProvider == null) {
            log.warn("No primary storage provider configured.");
        } else {
            log.info("StorageService initialized with {} providers, primary: {}",
                    enabledProviders.size(), primaryProvider.getName());
        }
    }

    private ArrayList<String> parseSupportedTypes(String supportedTypesStr) {
        ArrayList<String> result = new ArrayList<>();
        if (supportedTypesStr == null) {
            result.add("*");
            return result;
        }
        try {
            JsonNode typesNode = objectMapper.readTree(supportedTypesStr);
            if (typesNode.isArray()) {
                int arraySize = typesNode.size();
                for (int i = 0; i < arraySize; i++) {
                    String typeEntry = typesNode.get(i).asText();
                    result.add(typeEntry);
                }
            } else {
                result.add("*");
            }
        } catch (Exception e) {
            log.error("Failed to parse supported_types: {}", supportedTypesStr, e);
            result.add("*");
        }
        return result;
    }

    private StorageProvider createProvider(StorageProviderEntity entity) {
        StorageProvider provider = null;
        if ("oss_aliyun".equals(entity.providerType)) {
            provider = new AliyunOssStorageProvider();
        } else if ("local_fs".equals(entity.providerType)) {
            provider = new LocalFsStorageProvider();
        } else {
            log.warn("Unknown provider type: {}", entity.providerType);
            return null;
        }

        if (provider instanceof AliyunOssStorageProvider) {
            ((AliyunOssStorageProvider) provider).setName(entity.name);
        } else if (provider instanceof LocalFsStorageProvider) {
            ((LocalFsStorageProvider) provider).setName(entity.name);
        }
        return provider;
    }

    public StorageProvider getPrimaryProvider() {
        return primaryProvider;
    }

    public List<StorageProvider> getNonPrimaryProviders() {
        List<StorageProvider> result = new ArrayList<>();
        for (StorageProvider provider : enabledProviders) {
            if (provider != primaryProvider) {
                result.add(provider);
            }
        }
        return result;
    }

    public String getPrimaryProviderName() {
        if (primaryProvider == null) {
            return "unknown";
        }
        return primaryProvider.getName();
    }

    public List<StorageProviderEntity> getAllProviderEntities() {
        return StorageProviderEntity.list("isEnabled = true ORDER BY priority ASC");
    }

    public StorageProviderEntity getProviderEntityByName(String name) {
        return StorageProviderEntity.find("name", name).firstResult();
    }

    public List<StorageProviderEntity> getNonPrimaryProviderEntities() {
        List<StorageProviderEntity> allEntities = getAllProviderEntities();
        List<StorageProviderEntity> result = new ArrayList<>();
        String primaryName = getPrimaryProviderName();
        for (StorageProviderEntity entity : allEntities) {
            if (entity.isPrimary == null || !entity.isPrimary) {
                result.add(entity);
            }
        }
        return result;
    }

    public UploadResult uploadToPrimary(Long mediaId, VariantType variant, InputStream data, String contentType) {
        if (primaryProvider == null) {
            throw new StorageException("No primary provider configured");
        }
        String targetPath = generateTargetPath(variant, contentType);
        String storedPath = primaryProvider.upload(data, targetPath, contentType);
        return new UploadResult(storedPath, 0, "");
    }

    private String generateTargetPath(VariantType variant, String contentType) {
        String yearMonth = getCurrentYearMonth();
        String fileExtension = getExtensionFromMime(contentType, variant);
        String fileName = java.util.UUID.randomUUID().toString() + fileExtension;
        return yearMonth + "/" + fileName;
    }

    private String getCurrentYearMonth() {
        java.time.OffsetDateTime now = java.time.OffsetDateTime.now();
        int year = now.getYear();
        int month = now.getMonthValue();
        String monthStr = String.valueOf(month);
        if (month < 10) {
            monthStr = "0" + month;
        }
        return year + "/" + monthStr;
    }

    private String getExtensionFromMime(String contentType, VariantType variant) {
        if (variant == VariantType.WEBP) {
            return ".webp";
        }
        if (variant == VariantType.PLACEHOLDER || variant == VariantType.COVER) {
            return ".jpg";
        }
        if (contentType != null && !contentType.isEmpty()) {
            int slashIndex = contentType.indexOf('/');
            if (slashIndex >= 0 && slashIndex < contentType.length() - 1) {
                String subType = contentType.substring(slashIndex + 1);
                int semicolonIndex = subType.indexOf(';');
                if (semicolonIndex >= 0) {
                    subType = subType.substring(0, semicolonIndex);
                }
                return "." + subType;
            }
        }
        return ".bin";
    }

    public void scheduleSync(Long mediaId, Set<VariantType> variants) {
        List<StorageProvider> nonPrimaryProviders = getNonPrimaryProviders();
        for (StorageProvider provider : nonPrimaryProviders) {
            String providerName = provider.getName();
            for (VariantType variant : variants) {
                StorageSyncMessage message = new StorageSyncMessage(
                        mediaId, providerName, variant.name(), 0);
                syncEmitter.send(message);
            }
        }
    }

    public void scheduleSyncForMedia(Long mediaId) {
        Media media = Media.findById(mediaId);
        if (media == null) {
            return;
        }
        if (media.storageProviders == null) {
            return;
        }
        HashSet<VariantType> variants = new HashSet<>();
        for (String providerName : media.storageProviders.keySet()) {
            Object providerValue = media.storageProviders.get(providerName);
            if (providerValue instanceof Map) {
                Map<String, Object> providerData = (Map<String, Object>) providerValue;
                for (String variantName : providerData.keySet()) {
                    try {
                        VariantType variant = VariantType.valueOf(variantName.toUpperCase());
                        variants.add(variant);
                    } catch (IllegalArgumentException e) {
                    }
                }
            }
        }
        scheduleSync(mediaId, variants);
    }

    public void scheduleSyncForAllPending() {
        List<Media> allMedia = Media.listAll();
        for (Media media : allMedia) {
            scheduleSyncForMedia(media.id);
        }
    }

    public SyncResult executeSync(Long mediaId, String providerName, VariantType variant, int retryCount) {
        try {
            Media media = Media.findById(mediaId);
            if (media == null) {
                String errorMsg = "Media not found: " + mediaId;
                log.error(errorMsg);
                return new SyncResult(false, errorMsg);
            }

            if (media.storageProviders == null) {
                String errorMsg = "Media has no storage_providers: " + mediaId;
                log.error(errorMsg);
                return new SyncResult(false, errorMsg);
            }

            int currentVersion = media.version;
            boolean casSuccess = updateVariantStatusWithLock(
                    mediaId, providerName, variant, "syncing", null, null, currentVersion);
            if (!casSuccess) {
                log.info("CAS update failed for mediaId={}, provider={}, variant={}, version={}",
                        mediaId, providerName, variant.name(), currentVersion);
                return new SyncResult(false, "CAS version mismatch");
            }

            StorageProvider targetProvider = findProviderByName(providerName);
            if (targetProvider == null) {
                String errorMsg = "Target provider not found: " + providerName;
                log.error(errorMsg);
                updateVariantStatus(
                        mediaId, providerName, variant, "failed", null, null, currentVersion + 1);
                return new SyncResult(false, errorMsg);
            }

            String sourcePath = getSourcePathFromStorageProviders(media, variant);
            if (sourcePath == null || sourcePath.isEmpty()) {
                String errorMsg = "No source path found for variant: " + variant.name();
                log.error(errorMsg);
                updateVariantStatus(
                        mediaId, providerName, variant, "failed", null, null, currentVersion + 1);
                return new SyncResult(false, errorMsg);
            }

            InputStream downloadStream = primaryProvider.download(sourcePath);
            String targetPath = generateTargetPath(variant, media.mimeType);
            String uploadedPath = targetProvider.upload(downloadStream, targetPath, media.mimeType);
            downloadStream.close();

            long fileSize = 0;
            String etag = "";
            Media refreshedMedia = Media.findById(mediaId);
            int newVersion = refreshedMedia.version;
            updateVariantStatus(
                    mediaId, providerName, variant, "synced", uploadedPath, fileSize, newVersion);

            return new SyncResult(true, null);
        } catch (Exception e) {
            log.error("Storage sync failed for mediaId={}, provider={}, variant={}",
                    mediaId, providerName, variant.name(), e);
            return new SyncResult(false, e.getMessage());
        }
    }

    private StorageProvider findProviderByName(String name) {
        for (StorageProvider provider : enabledProviders) {
            if (provider.getName().equals(name)) {
                return provider;
            }
        }
        return null;
    }

    private String getSourcePathFromStorageProviders(Media media, VariantType variant) {
        String primaryName = getPrimaryProviderName();
        Object providerDataObj = media.storageProviders.get(primaryName);
        if (!(providerDataObj instanceof Map)) {
            return null;
        }
        Map<String, Object> primaryProviderData = (Map<String, Object>) providerDataObj;
        Object variantDataObj = primaryProviderData.get(variant.name().toLowerCase());
        if (!(variantDataObj instanceof Map)) {
            return null;
        }
        Map<String, Object> variantData = (Map<String, Object>) variantDataObj;
        Object pathObj = variantData.get("path");
        if (pathObj == null) {
            return null;
        }
        return pathObj.toString();
    }

    @Transactional
    public boolean updateVariantStatusWithLock(Long mediaId, String providerName,
                                               VariantType variant, String status,
                                               String path, Long size, int expectedVersion) {
        String variantKey = variant.name().toLowerCase();
        Map<String, Object> variantInfo = new LinkedHashMap<>();
        variantInfo.put("status", status);
        variantInfo.put("path", path);
        variantInfo.put("size", size);

        String jsonPayload;
        try {
            jsonPayload = objectMapper.writeValueAsString(variantInfo);
        } catch (Exception e) {
            log.error("Failed to serialize variant status", e);
            return false;
        }

        String sql = "UPDATE media SET "
                + "storage_providers = jsonb_set("
                + "  jsonb_set(storage_providers, '{\"$1\"}', COALESCE(storage_providers->'$1', '{}'::jsonb), true),"
                + "  '{\"$1\",\"$2\"}',"
                + "  '$3'::jsonb"
                + "), "
                + "version = version + 1 "
                + "WHERE id = $4 AND version = $5";

        sql = sql.replace("$1", providerName);
        sql = sql.replace("$2", variantKey);
        sql = sql.replace("$3", jsonPayload.replace("'", "''"));
        sql = sql.replace("$4", mediaId.toString());
        sql = sql.replace("$5", String.valueOf(expectedVersion));

        int updatedRows = entityManager.createNativeQuery(sql).executeUpdate();
        return updatedRows > 0;
    }

    @Transactional
    public void updateVariantStatus(Long mediaId, String providerName, VariantType variant,
                                    String status, String path, Long size, int expectedVersion) {
        updateVariantStatusWithLock(mediaId, providerName, variant, status, path, size, expectedVersion);
    }

    public String getBestAccessUrl(Media media, VariantType variant) {
        if (media == null || media.storageProviders == null) {
            return null;
        }

        for (StorageProvider provider : enabledProviders) {
            String providerName = provider.getName();
            Object providerDataObj = media.storageProviders.get(providerName);
            if (!(providerDataObj instanceof Map)) {
                continue;
            }
            Map<String, Object> providerData = (Map<String, Object>) providerDataObj;
            String variantKey = variant.name().toLowerCase();
            Object variantDataObj = providerData.get(variantKey);
            if (!(variantDataObj instanceof Map)) {
                continue;
            }
            Map<String, Object> variantData = (Map<String, Object>) variantDataObj;
            Object statusObj = variantData.get("status");
            if (!"synced".equals(statusObj)) {
                continue;
            }
            Object pathObj = variantData.get("path");
            if (pathObj == null) {
                continue;
            }
            String path = pathObj.toString();

            StorageProviderEntity entity = getProviderEntityByName(providerName);
            if (entity != null && entity.cdnEnabled != null && entity.cdnEnabled
                    && entity.cdnDomain != null && !entity.cdnDomain.isEmpty()) {
                return "https://" + entity.cdnDomain + "/" + path;
            }

            return provider.getPublicUrl(path);
        }
        return null;
    }

    public InputStream fallbackDownload(Media media, VariantType variant) {
        if (media == null || media.storageProviders == null) {
            throw new StorageException("No storage information available for download");
        }

        for (StorageProvider provider : enabledProviders) {
            String providerName = provider.getName();
            Object providerDataObj = media.storageProviders.get(providerName);
            if (!(providerDataObj instanceof Map)) {
                continue;
            }
            Map<String, Object> providerData = (Map<String, Object>) providerDataObj;
            String variantKey = variant.name().toLowerCase();
            Object variantDataObj = providerData.get(variantKey);
            if (!(variantDataObj instanceof Map)) {
                continue;
            }
            Map<String, Object> variantData = (Map<String, Object>) variantDataObj;
            Object statusObj = variantData.get("status");
            if (!"synced".equals(statusObj)) {
                continue;
            }
            Object pathObj = variantData.get("path");
            if (pathObj == null) {
                continue;
            }
            String path = pathObj.toString();
            try {
                return provider.download(path);
            } catch (Exception e) {
                log.warn("Download failed from provider {}, trying next", providerName, e);
            }
        }

        throw new StorageException("No synced copy found for variant: " + variant.name());
    }

    public MediaSyncStatus getSyncStatus(Long mediaId) {
        Media media = Media.findById(mediaId);
        if (media == null) {
            return null;
        }

        MediaSyncStatus status = new MediaSyncStatus();
        status.mediaId = mediaId;
        status.totalVariants = 0;
        status.syncedCount = 0;
        status.pendingCount = 0;
        status.failedCount = 0;
        status.details = new LinkedHashMap<>();

        if (media.storageProviders != null) {
            for (Map.Entry<String, Object> entry : media.storageProviders.entrySet()) {
                String providerName = entry.getKey();
                Object providerDataObj = entry.getValue();
                if (!(providerDataObj instanceof Map)) {
                    continue;
                }
                Map<String, Object> providerData = (Map<String, Object>) providerDataObj;
                Map<String, String> providerVariants = new LinkedHashMap<>();

                for (Map.Entry<String, Object> variantEntry : providerData.entrySet()) {
                    String variantKey = variantEntry.getKey();
                    Object variantDataObj = variantEntry.getValue();
                    if (variantDataObj instanceof Map) {
                        Map<String, Object> variantData = (Map<String, Object>) variantDataObj;
                        Object statusObj = variantData.get("status");
                        String variantStatus = "unknown";
                        if (statusObj != null) {
                            variantStatus = statusObj.toString();
                        }
                        providerVariants.put(variantKey, variantStatus);

                        status.totalVariants++;
                        if ("synced".equals(variantStatus)) {
                            status.syncedCount++;
                        } else if ("pending".equals(variantStatus)) {
                            status.pendingCount++;
                        } else if ("failed".equals(variantStatus)) {
                            status.failedCount++;
                        }
                    }
                }

                status.details.put(providerName, providerVariants);
            }
        }

        return status;
    }
}
