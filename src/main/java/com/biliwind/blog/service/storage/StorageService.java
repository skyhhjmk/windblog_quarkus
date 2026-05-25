package com.biliwind.blog.service.storage;

import com.biliwind.blog.model.Media;
import com.biliwind.blog.model.StorageClassEntity;
import com.biliwind.blog.service.storage.dto.StorageSyncMessage;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.arc.Unremovable;
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
@Unremovable
public class StorageService {

    private static final Logger log = LoggerFactory.getLogger(StorageService.class);

    private final List<StorageClass> enabledProviders = new ArrayList<>();
    @Inject
    ObjectMapper objectMapper;
    @Inject
    EntityManager entityManager;
    @Inject
    @Channel("storage-sync-tasks")
    Emitter<StorageSyncMessage> syncEmitter;
    private StorageClass primaryProvider;

    @PostConstruct
    void init() {
        io.quarkus.narayana.jta.QuarkusTransaction.requiringNew().run(new Runnable() {
            @Override
            public void run() {
                long count = StorageClassEntity.count();
                if (count == 0) {
                    log.info("No storage classes found, initializing default local_fs storage class...");
                    String rootPath = org.eclipse.microprofile.config.ConfigProvider.getConfig()
                            .getOptionalValue("storage.path", String.class)
                            .orElse(org.eclipse.microprofile.config.ConfigProvider.getConfig()
                                    .getOptionalValue("media.upload.dir", String.class).orElse("./uploads"));
                    String baseUrl = org.eclipse.microprofile.config.ConfigProvider.getConfig()
                            .getOptionalValue("media.upload.path", String.class).orElse("/uploads");

                    StorageClassEntity defaultProvider = new StorageClassEntity();
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

                List<StorageClassEntity> entities = StorageClassEntity.list("isEnabled = true ORDER BY priority ASC");
                for (StorageClassEntity entity : entities) {
                    StorageClass provider = createProvider(entity);
                    if (provider == null) {
                        continue;
                    }
                    try {
                        JsonNode configJson = objectMapper.readTree(entity.configJson);
                        ArrayList<String> supportedTypes = parseSupportedTypes(entity.supportedTypes);
                        StorageClassConfig config = new StorageClassConfig(
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
        });
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

    private StorageClass createProvider(StorageClassEntity entity) {
        StorageClass provider = null;
        if ("oss_aliyun".equals(entity.providerType) || "aliyun_oss_v2".equals(entity.providerType)) {
            provider = new AliyunOssStorageClass();
        } else if ("local_fs".equals(entity.providerType)) {
            provider = new LocalFsStorageClass();
        } else {
            log.warn("Unknown provider type: {}", entity.providerType);
            return null;
        }

        if (provider instanceof AliyunOssStorageClass) {
            ((AliyunOssStorageClass) provider).setName(entity.name);
        } else if (provider instanceof LocalFsStorageClass) {
            ((LocalFsStorageClass) provider).setName(entity.name);
        }
        return provider;
    }

    public StorageClass getPrimaryProvider() {
        return primaryProvider;
    }

    public StorageClass getPrimaryStorageClass() {
        return primaryProvider;
    }

    public List<StorageClass> getNonPrimaryProviders() {
        List<StorageClass> result = new ArrayList<>();
        for (StorageClass provider : enabledProviders) {
            if (provider != primaryProvider) {
                result.add(provider);
            }
        }
        return result;
    }

    public List<StorageClass> getNonPrimaryStorageClasses() {
        return getNonPrimaryProviders();
    }

    public String getPrimaryProviderName() {
        if (primaryProvider == null) {
            return "unknown";
        }
        return primaryProvider.getName();
    }

    public String getPrimaryStorageClassName() {
        return getPrimaryProviderName();
    }

    public List<StorageClassEntity> getAllProviderEntities() {
        return StorageClassEntity.list("isEnabled = true ORDER BY priority ASC");
    }

    public List<StorageClassEntity> getAllStorageClassEntities() {
        return getAllProviderEntities();
    }

    public StorageClassEntity getProviderEntityByName(String name) {
        return StorageClassEntity.find("name", name).firstResult();
    }

    public StorageClassEntity getStorageClassEntityByName(String name) {
        return getProviderEntityByName(name);
    }

    public List<StorageClassEntity> getNonPrimaryProviderEntities() {
        List<StorageClassEntity> allEntities = getAllProviderEntities();
        List<StorageClassEntity> result = new ArrayList<>();
        String primaryName = getPrimaryProviderName();
        for (StorageClassEntity entity : allEntities) {
            if (entity.isPrimary == null || !entity.isPrimary) {
                result.add(entity);
            }
        }
        return result;
    }

    public List<StorageClassEntity> getNonPrimaryStorageClassEntities() {
        return getNonPrimaryProviderEntities();
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
        Media media = Media.findById(mediaId);
        if (media == null) {
            return;
        }
        List<StorageClass> nonPrimaryStorageClasses = getNonPrimaryStorageClasses();
        StorageClass originStorageClass = findOriginLoadedStorageClass();
        if (originStorageClass != null) {
            scheduleSyncToStorageClass(media, originStorageClass, variants);
        }
        for (StorageClass storageClass : nonPrimaryStorageClasses) {
            if (originStorageClass != null && originStorageClass.getName().equals(storageClass.getName())) {
                continue;
            }
            scheduleSyncToStorageClass(media, storageClass, variants);
        }
    }

    private void scheduleSyncToStorageClass(Media media, StorageClass storageClass, Set<VariantType> variants) {
        String storageClassName = storageClass.getName();
        if (!shouldSyncToStorageClass(media, storageClassName)) {
            return;
        }
        for (VariantType variant : variants) {
            StorageSyncMessage message = new StorageSyncMessage(
                    media.id, storageClassName, variant.name(), 0);
            syncEmitter.send(message);
        }
    }

    public void scheduleSyncForMedia(Long mediaId) {
        Media media = Media.findById(mediaId);
        if (media == null) {
            return;
        }
        if (media.storageClasses == null) {
            return;
        }
        HashSet<VariantType> variants = new HashSet<>();
        for (String storageClassName : media.storageClasses.keySet()) {
            Object providerValue = media.storageClasses.get(storageClassName);
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

    public SyncResult executeSync(Long mediaId, String storageClassName, VariantType variant, int retryCount) {
        try {
            Media media = Media.findById(mediaId);
            if (media == null) {
                String errorMsg = "Media not found: " + mediaId;
                log.error(errorMsg);
                return new SyncResult(false, errorMsg);
            }

            if (media.storageClasses == null) {
                String errorMsg = "Media has no storage_classes: " + mediaId;
                log.error(errorMsg);
                return new SyncResult(false, errorMsg);
            }

            if (!shouldSyncToStorageClass(media, storageClassName)) {
                String errorMsg = "Storage class is skipped by media policy: " + storageClassName;
                log.info(errorMsg);
                return new SyncResult(false, errorMsg);
            }

            int currentVersion = media.version;
            boolean casSuccess = updateVariantStatusWithLock(
                    mediaId, storageClassName, variant, "syncing", null, null, currentVersion);
            if (!casSuccess) {
                log.info("CAS update failed for mediaId={}, provider={}, variant={}, version={}",
                        mediaId, storageClassName, variant.name(), currentVersion);
                return new SyncResult(false, "CAS version mismatch");
            }

            StorageClass targetProvider = findProviderByName(storageClassName);
            if (targetProvider == null) {
                String errorMsg = "Target provider not found: " + storageClassName;
                log.error(errorMsg);
                updateVariantStatus(
                        mediaId, storageClassName, variant, "failed", null, null, currentVersion + 1);
                return new SyncResult(false, errorMsg);
            }

            SyncSource syncSource = findSyncSource(media, variant, storageClassName);
            if (syncSource == null) {
                String errorMsg = "No source path found for variant: " + variant.name();
                log.error(errorMsg);
                updateVariantStatus(
                        mediaId, storageClassName, variant, "failed", null, null, currentVersion + 1);
                return new SyncResult(false, errorMsg);
            }
            if (syncSource.waitingForOrigin) {
                String errorMsg = "Origin storage class is not ready for variant: " + variant.name();
                log.info(errorMsg);
                updateVariantStatus(
                        mediaId, storageClassName, variant, "pending", null, null, currentVersion + 1);
                return new SyncResult(false, errorMsg);
            }

            String targetPath = generateTargetPath(variant, media.mimeType);
            String uploadedPath;
            try (InputStream downloadStream = syncSource.storageClass.download(syncSource.path)) {
                uploadedPath = targetProvider.upload(downloadStream, targetPath, media.mimeType);
            }

            long fileSize = 0;
            String etag = "";
            Media refreshedMedia = Media.findById(mediaId);
            int newVersion = refreshedMedia.version;
            updateVariantStatus(
                    mediaId, storageClassName, variant, "synced", uploadedPath, fileSize, newVersion);

            return new SyncResult(true, null);
        } catch (Exception e) {
            log.error("Storage sync failed for mediaId={}, provider={}, variant={}",
                    mediaId, storageClassName, variant.name(), e);
            return new SyncResult(false, e.getMessage());
        }
    }

    private StorageClass findProviderByName(String name) {
        for (StorageClass provider : enabledProviders) {
            if (provider.getName().equals(name)) {
                return provider;
            }
        }
        return null;
    }

    private SyncSource findSyncSource(Media media, VariantType variant, String targetStorageClassName) {
        StorageClassEntity originStorageClass = findOriginStorageClass();
        if (originStorageClass != null && !originStorageClass.name.equals(targetStorageClassName)) {
            String originPath = getSourcePathFromStorageClasses(media, variant, originStorageClass.name);
            if (originPath != null && !originPath.isEmpty()) {
                StorageClass originProvider = findProviderByName(originStorageClass.name);
                if (originProvider != null) {
                    return new SyncSource(originProvider, originPath, false);
                }
            }
            if (shouldSyncToStorageClass(media, originStorageClass.name)) {
                return new SyncSource(null, null, true);
            }
        }

        String primaryName = getPrimaryProviderName();
        String primaryPath = getSourcePathFromStorageClasses(media, variant, primaryName);
        if (primaryPath == null || primaryPath.isEmpty()) {
            return null;
        }
        return new SyncSource(primaryProvider, primaryPath, false);
    }

    private StorageClassEntity findOriginStorageClass() {
        List<StorageClassEntity> allEntities = getAllProviderEntities();
        for (StorageClassEntity entity : allEntities) {
            if (entity.role != null && "origin".equals(entity.role)) {
                return entity;
            }
        }
        return null;
    }

    private StorageClass findOriginLoadedStorageClass() {
        StorageClassEntity originStorageClass = findOriginStorageClass();
        if (originStorageClass == null) {
            return null;
        }
        return findProviderByName(originStorageClass.name);
    }

    private String getSourcePathFromStorageClasses(Media media, VariantType variant, String storageClassName) {
        Object providerDataObj = media.storageClasses.get(storageClassName);
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

    private static class SyncSource {
        StorageClass storageClass;
        String path;
        boolean waitingForOrigin;

        SyncSource(StorageClass storageClass, String path, boolean waitingForOrigin) {
            this.storageClass = storageClass;
            this.path = path;
            this.waitingForOrigin = waitingForOrigin;
        }
    }

    @Transactional
    public boolean updateVariantStatusWithLock(Long mediaId, String storageClassName,
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
                + "storage_classes = jsonb_set("
                + "  jsonb_set(storage_classes, ARRAY[?1::text], COALESCE(storage_classes -> ?1, '{}'::jsonb), true),"
                + "  ARRAY[?1::text, ?2::text],"
                + "  CAST(?3 AS jsonb)"
                + "), "
                + "version = version + 1 "
                + "WHERE id = ?4 AND version = ?5";

        jakarta.persistence.Query query = entityManager.createNativeQuery(sql);
        query.setParameter(1, storageClassName);
        query.setParameter(2, variantKey);
        query.setParameter(3, jsonPayload);
        query.setParameter(4, mediaId);
        query.setParameter(5, expectedVersion);

        int updatedRows = query.executeUpdate();
        return updatedRows > 0;
    }

    @Transactional
    public void updateVariantStatus(Long mediaId, String storageClassName, VariantType variant,
                                    String status, String path, Long size, int expectedVersion) {
        updateVariantStatusWithLock(mediaId, storageClassName, variant, status, path, size, expectedVersion);
    }

    public String getBestAccessUrl(Media media, VariantType variant) {
        if (media == null || media.storageClasses == null) {
            return null;
        }

        for (StorageClass provider : enabledProviders) {
            String storageClassName = provider.getName();
            Object providerDataObj = media.storageClasses.get(storageClassName);
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

            if (!shouldReadFromStorageClass(media, storageClassName)) {
                continue;
            }

            StorageClassEntity entity = getProviderEntityByName(storageClassName);
            if (entity != null && entity.cdnEnabled != null && entity.cdnEnabled
                    && entity.cdnDomain != null && !entity.cdnDomain.isEmpty()) {
                return "https://" + entity.cdnDomain + "/" + path;
            }

            return provider.getPublicUrl(path);
        }
        return null;
    }

    public String getBestSignedUrl(Media media, VariantType variant, java.time.Duration expiration) {
        if (media == null) {
            return null;
        }
        if (media.storageClasses == null) {
            return null;
        }

        for (StorageClass provider : enabledProviders) {
            String storageClassName = provider.getName();
            if (!shouldReadFromStorageClass(media, storageClassName)) {
                continue;
            }

            Object providerDataObj = media.storageClasses.get(storageClassName);
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

            return provider.getSignedUrl(path, expiration);
        }
        return null;
    }

    public InputStream fallbackDownload(Media media, VariantType variant) {
        if (media == null || media.storageClasses == null) {
            throw new StorageException("No storage information available for download");
        }

        for (StorageClass provider : enabledProviders) {
            String storageClassName = provider.getName();
            if (!shouldReadFromStorageClass(media, storageClassName)) {
                continue;
            }

            Object providerDataObj = media.storageClasses.get(storageClassName);
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
                log.warn("Download failed from provider {}, trying next", storageClassName, e);
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

        if (media.storageClasses != null) {
            for (Map.Entry<String, Object> entry : media.storageClasses.entrySet()) {
                String storageClassName = entry.getKey();
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

                status.details.put(storageClassName, providerVariants);
            }
        }

        return status;
    }

    @Transactional
    public void initializeStorageClassesForMedia(Long mediaId) {
        Media media = Media.findById(mediaId);
        if (media == null || media.storageClasses == null) {
            return;
        }

        Map<String, Object> primaryStorageClassData = findPrimaryStorageClassData(media);
        if (primaryStorageClassData == null || primaryStorageClassData.isEmpty()) {
            return;
        }

        List<String> variantNames = new ArrayList<>();
        for (String variantName : primaryStorageClassData.keySet()) {
            variantNames.add(variantName);
        }

        List<StorageClassEntity> storageClasses = getNonPrimaryStorageClassEntities();
        for (StorageClassEntity storageClassEntity : storageClasses) {
            String storageClassName = storageClassEntity.name;
            if (!shouldSyncToStorageClass(media, storageClassName)) {
                media.storageClasses.remove(storageClassName);
                continue;
            }
            Object existingData = media.storageClasses.get(storageClassName);
            Map<String, Object> storageClassData;
            if (existingData instanceof Map) {
                storageClassData = (Map<String, Object>) existingData;
            } else {
                storageClassData = new LinkedHashMap<>();
                media.storageClasses.put(storageClassName, storageClassData);
            }
            for (String variantName : variantNames) {
                if (!storageClassData.containsKey(variantName)) {
                    Map<String, Object> pendingVariant = new LinkedHashMap<>();
                    pendingVariant.put("status", "pending");
                    pendingVariant.put("path", null);
                    storageClassData.put(variantName, pendingVariant);
                }
            }
        }
        media.persist();
    }

    private Map<String, Object> findPrimaryStorageClassData(Media media) {
        String primaryName = getPrimaryProviderName();
        Object primaryData = media.storageClasses.get(primaryName);
        if (primaryData instanceof Map) {
            return (Map<String, Object>) primaryData;
        }
        return null;
    }

    private boolean shouldReadFromStorageClass(Media media, String storageClassName) {
        if (media == null || storageClassName == null) {
            return false;
        }
        String primaryName = getPrimaryProviderName();
        if (storageClassName.equals(primaryName)) {
            return true;
        }
        return shouldSyncToStorageClass(media, storageClassName);
    }

    public boolean shouldSyncToStorageClass(Media media, String storageClassName) {
        if (media == null || storageClassName == null || storageClassName.isBlank()) {
            return false;
        }

        String primaryName = getPrimaryProviderName();
        if (storageClassName.equals(primaryName)) {
            return false;
        }

        if (containsName(media.skipStorageClasses, storageClassName)) {
            return false;
        }

        if (media.syncStorageClasses == null || media.syncStorageClasses.isEmpty()) {
            return true;
        }

        return containsName(media.syncStorageClasses, storageClassName);
    }

    private boolean containsName(List<String> names, String expectedName) {
        if (names == null || expectedName == null) {
            return false;
        }
        for (String name : names) {
            if (name != null && name.equals(expectedName)) {
                return true;
            }
        }
        return false;
    }
}
