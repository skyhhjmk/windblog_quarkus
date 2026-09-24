package com.biliwind.blog.service.storage;

import com.biliwind.blog.common.security.SensitiveMessageSanitizer;
import com.biliwind.blog.model.Media;
import com.biliwind.blog.model.StorageClassEntity;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.arc.Unremovable;
import io.quarkus.panache.common.Page;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.*;

@ApplicationScoped
@Unremovable
public class StorageService {

    private static final Logger log = LoggerFactory.getLogger(StorageService.class);

    private volatile List<StorageClass> enabledProviders = List.of();
    @Inject
    ObjectMapper objectMapper;

    @Inject
    StorageConfigProtector storageConfigProtector;
    @Inject
    EntityManager entityManager;
    @Inject
    com.biliwind.blog.service.OutboxEventService outboxEventService;
    @Inject
    StorageRegionPolicy regionPolicy;
    @Inject
    EncryptedBackupCodec backupCodec;
    @Inject
    jakarta.enterprise.inject.Instance<StorageService> selfProxy;
    private volatile StorageClass primaryProvider;

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

                reloadProviders();
            }
        });
    }

    /** Atomically publish a fresh provider snapshot after admin changes. */
    @Transactional
    public synchronized void reloadProviders() {
        List<StorageClass> loaded = new ArrayList<>();
        StorageClass primary = null;
        List<StorageClassEntity> entities = StorageClassEntity.list("isEnabled = true ORDER BY priority ASC");
        for (StorageClassEntity entity : entities) {
            StorageClass provider = createProvider(entity);
            if (provider == null) {
                continue;
            }
            try {
                String runtimeConfigJson = storageConfigProtector.revealForRuntime(entity.configJson);
                JsonNode configJson = objectMapper.readTree(runtimeConfigJson);
                provider.initialize(new StorageClassConfig(configJson,
                        parseSupportedTypes(entity.supportedTypes), entity.cdnDomain, entity.cdnEnabled));
                loaded.add(provider);
                if (Boolean.TRUE.equals(entity.isPrimary)) {
                    if (primary != null) {
                        throw new IllegalStateException("Multiple primary storage providers found");
                    }
                    primary = provider;
                }
            } catch (Exception exception) {
                log.error("Failed to initialize storage provider: {}", entity.name, exception);
            }
        }
        primaryProvider = primary;
        enabledProviders = List.copyOf(loaded);
        log.info("Storage providers reloaded: {} enabled", loaded.size());
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

    /** Install processed variants from private staging on a region-compliant normal provider. */
    public InitialStorage installProcessedMedia(Media media, Map<VariantType, String> stagedVariants,
                                                Path stagingRoot) {
        StorageClass source = null;
        for (StorageClass candidate : enabledProviders) {
            StorageClassEntity entity = getProviderEntityByName(candidate.getName());
            if (regionPolicy.placement(media, entity) == StorageRegionPolicy.Placement.NORMAL
                    && candidate.isAvailable()
                    && stagedVariants.keySet().stream().allMatch(variant ->
                    candidate.supportsVariant(variantContentType(media.mimeType, variant), variant.name()))) {
                source = candidate;
                break;
            }
        }
        if (source == null) {
            throw new StorageException("No region-compliant storage class for media upload");
        }
        Map<String, Object> variants = new LinkedHashMap<>();
        Map<String, String> urls = new HashMap<>();
        List<String> uploaded = new ArrayList<>();
        try {
            for (Map.Entry<VariantType, String> entry : stagedVariants.entrySet()) {
                VariantType variant = entry.getKey();
                Path staged = stagingRoot.resolve(entry.getValue()).normalize();
                if (!staged.startsWith(stagingRoot) || !Files.isRegularFile(staged)) {
                    throw new StorageException("Processed media variant is missing");
                }
                String uuid = canonicalUuid(media, variant, entry.getValue());
                String path = canonicalNormalPath(entry.getValue(), uuid, variant, media.mimeType);
                String stored;
                try (InputStream input = Files.newInputStream(staged)) {
                    stored = source.upload(input, path, variantContentType(media.mimeType, variant));
                }
                uploaded.add(stored);
                Map<String, Object> info = new LinkedHashMap<>();
                info.put("status", "synced");
                info.put("mode", "NORMAL");
                info.put("path", stored);
                info.put("sha256", EncryptedBackupCodec.sha256Hex(staged));
                info.put("size", Files.size(staged));
                variants.put(variant.name().toLowerCase(), info);
                urls.put(variant.name(), source.getPublicUrl(stored));
            }
            return new InitialStorage(source.getName(), variants, urls);
        } catch (Exception exception) {
            for (String stored : uploaded) {
                try {
                    source.delete(stored);
                } catch (Exception cleanupFailure) {
                    log.warn("Unable to remove partial initial media copy from {}", source.getName(), cleanupFailure);
                }
            }
            throw new StorageException("Unable to install media on region-compliant storage", exception);
        }
    }

    public record InitialStorage(String storageClassName, Map<String, Object> variants,
                                 Map<String, String> urls) {}

    public boolean hasNormalPlacement(Media media) {
        for (StorageClassEntity entity : getAllStorageClassEntities()) {
            if (regionPolicy.placement(media, entity) == StorageRegionPolicy.Placement.NORMAL) {
                return true;
            }
        }
        return false;
    }

    public Long firstMediaWithoutNormalPlacement() {
        long cursor = 0;
        while (true) {
            List<Media> batch = Media.find("deletedAt is null and id > ?1 order by id", cursor)
                    .page(Page.ofSize(200)).list();
            if (batch.isEmpty()) {
                return null;
            }
            for (Media media : batch) {
                if (!hasNormalPlacement(media)) {
                    return media.id;
                }
                cursor = media.id;
            }
        }
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
        if (media == null || media.deletedAt != null) {
            return;
        }
        for (StorageClass storageClass : enabledProviders) {
            StorageClassEntity entity = getProviderEntityByName(storageClass.getName());
            if (regionPolicy.placement(media, entity) == StorageRegionPolicy.Placement.NORMAL) {
                scheduleSyncToStorageClass(media, storageClass, variants);
            }
        }
        for (StorageClass storageClass : enabledProviders) {
            StorageClassEntity entity = getProviderEntityByName(storageClass.getName());
            if (regionPolicy.placement(media, entity) == StorageRegionPolicy.Placement.ENCRYPTED_BACKUP) {
                for (VariantType variant : variants) {
                    if (hasHealthyNormalReplica(media, variant, storageClass.getName())) {
                        scheduleSyncToStorageClass(media, storageClass, Set.of(variant));
                    }
                }
            }
        }
    }

    private boolean hasHealthyNormalReplica(Media media, VariantType variant, String excludedName) {
        for (StorageClass provider : enabledProviders) {
            String name = provider.getName();
            if (name.equals(excludedName) || !isNormalSynced(media, name, variant)) {
                continue;
            }
            StorageClassEntity entity = getProviderEntityByName(name);
            String path = getSourcePathFromStorageClasses(media, variant, name);
            if (regionPolicy.placement(media, entity) == StorageRegionPolicy.Placement.NORMAL
                    && path != null && provider.exists(path)) {
                return true;
            }
        }
        return false;
    }

    private void scheduleSyncToStorageClass(Media media, StorageClass storageClass, Set<VariantType> variants) {
        String storageClassName = storageClass.getName();
        if (!shouldSyncToStorageClass(media, storageClassName)) {
            return;
        }
        for (VariantType variant : variants) {
            if (isNormalSynced(media, storageClassName, variant)
                    && regionPolicy.placement(media, getProviderEntityByName(storageClassName))
                    == StorageRegionPolicy.Placement.NORMAL
                    && copyExists(media, storageClass, variant)) {
                continue;
            }
            if (isBackupSynced(media, storageClassName, variant)
                    && regionPolicy.placement(media, getProviderEntityByName(storageClassName))
                    == StorageRegionPolicy.Placement.ENCRYPTED_BACKUP
                    && copyExists(media, storageClass, variant)) {
                continue;
            }
            try {
                outboxEventService.enqueue(
                        "STORAGE_SYNC:" + media.id + ":" + storageClassName + ":" + variant.name(),
                        "STORAGE_SYNC",
                        "MEDIA",
                        media.id.toString(),
                        Map.of("mediaId", media.id,
                                "storageClassName", storageClassName,
                                "variantType", variant.name(),
                                "retryCount", 0),
                        null);
            } catch (Exception exception) {
                log.error("存储同步任务写入 outbox 失败，不绕过队列容量和租约直接发布", exception);
                throw new IllegalStateException("存储同步任务写入 outbox 失败", exception);
            }
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
        final int batchSize = 200;
        long lastMediaId = 0L;
        while (true) {
            List<Media> mediaBatch = Media.find(
                            "deletedAt is null and id > ?1 order by id", lastMediaId)
                    .page(Page.ofSize(batchSize))
                    .list();
            if (mediaBatch.isEmpty()) {
                return;
            }
            for (Media media : mediaBatch) {
                scheduleSyncForMedia(media.id);
                lastMediaId = media.id;
            }
        }
    }

    public SyncResult executeSync(Long mediaId, String storageClassName, VariantType variant, int retryCount) {
        try {
            Media media = Media.findById(mediaId);
            if (media == null || media.deletedAt != null) {
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

            StorageClassEntity targetEntity = getProviderEntityByName(storageClassName);
            StorageRegionPolicy.Placement placement = regionPolicy.placement(media, targetEntity);
            if (placement == StorageRegionPolicy.Placement.SKIP) {
                return new SyncResult(false, "Storage policy excludes this media");
            }
            if (placement == StorageRegionPolicy.Placement.ENCRYPTED_BACKUP
                    && !hasHealthyNormalReplica(media, variant, storageClassName)) {
                return new SyncResult(false, "Awaiting compliant normal copy");
            }

            String oldPath = getSourcePathFromStorageClasses(media, variant, storageClassName);

            StorageClass targetProvider = findProviderByName(storageClassName);
            if (targetProvider == null) {
                String errorMsg = "Target provider not found: " + storageClassName;
                log.error(errorMsg);
                return new SyncResult(false, errorMsg);
            }

            SyncSource syncSource = findSyncSource(media, variant, storageClassName);
            if (syncSource == null) {
                String errorMsg = "No source path found for variant: " + variant.name();
                log.error(errorMsg);
                return new SyncResult(false, errorMsg);
            }
            if (syncSource.waitingForOrigin) {
                String errorMsg = "Origin storage class is not ready for variant: " + variant.name();
                log.info(errorMsg);
                return new SyncResult(false, errorMsg);
            }

            String uuid = canonicalUuid(media, variant, syncSource.path);
            String targetPath = placement == StorageRegionPolicy.Placement.ENCRYPTED_BACKUP
                    ? "encrypted-backup/v1/" + variant.name().toLowerCase() + "/" + uuid + ".wbak"
                    : canonicalNormalPath(syncSource.path, uuid, variant, media.mimeType);
            String uploadedPath;
            String sha256;
            Path sourceFile = Files.createTempFile("windblog-sync-source-", ".tmp");
            Path payload = null;
            try {
                try (InputStream downloadStream = syncSource.storageClass.download(syncSource.path)) {
                    Files.copy(downloadStream, sourceFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
                sha256 = EncryptedBackupCodec.sha256Hex(sourceFile);
                Object rawSource = media.storageClasses.get(syncSource.storageClass.getName());
                if (rawSource instanceof Map<?, ?> sourceVariants
                        && sourceVariants.get(variant.name().toLowerCase()) instanceof Map<?, ?> sourceEntry
                        && sourceEntry.get("sha256") instanceof String expectedSha
                        && !expectedSha.equals(sha256)) {
                    throw new java.io.IOException("Source checksum changed during replication");
                }
                payload = placement == StorageRegionPolicy.Placement.ENCRYPTED_BACKUP
                        ? backupCodec.encrypt(Files.newInputStream(sourceFile), uuid) : sourceFile;
                try (InputStream data = Files.newInputStream(payload)) {
                    uploadedPath = targetProvider.upload(data, targetPath,
                            placement == StorageRegionPolicy.Placement.ENCRYPTED_BACKUP
                                    ? "application/octet-stream" : variantContentType(media.mimeType, variant));
                }
            } finally {
                Files.deleteIfExists(sourceFile);
                if (payload != null && !payload.equals(sourceFile)) {
                    Files.deleteIfExists(payload);
                }
            }

            int newVersion = databaseVersion(mediaId);
            boolean recorded = selfProxy.get().updateVariantStatusWithLock(mediaId, storageClassName, variant,
                    placement == StorageRegionPolicy.Placement.ENCRYPTED_BACKUP
                            ? "backup_synced" : "synced", uploadedPath, null, sha256, newVersion);
            if (!recorded) {
                return new SyncResult(false, "Concurrent media update");
            }
            if (oldPath != null && !oldPath.equals(uploadedPath)) {
                try {
                    targetProvider.delete(oldPath);
                } catch (Exception cleanupFailure) {
                    log.warn("Unable to remove superseded storage copy: mediaId={}, storageClass={}",
                            mediaId, storageClassName, cleanupFailure);
                }
            }

            return new SyncResult(true, null);
        } catch (Exception e) {
            log.error("Storage sync failed for mediaId={}, provider={}, variant={}",
                    mediaId, storageClassName, variant.name(), e);
            return new SyncResult(false, SensitiveMessageSanitizer.sanitize(e.getMessage()));
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
        if (originStorageClass != null && !originStorageClass.name.equals(targetStorageClassName)
                && isNormalSynced(media, originStorageClass.name, variant)) {
            String originPath = getSourcePathFromStorageClasses(media, variant, originStorageClass.name);
            if (originPath != null && !originPath.isEmpty()) {
                StorageClass originProvider = findProviderByName(originStorageClass.name);
                if (originProvider != null && originProvider.exists(originPath)
                        && sourceMatchesChecksum(media, variant, originProvider, originPath)) {
                    return new SyncSource(originProvider, originPath, false);
                }
            }
        }

        for (StorageClass provider : enabledProviders) {
            if (provider.getName().equals(targetStorageClassName)) {
                continue;
            }
            if (!isNormalSynced(media, provider.getName(), variant)) {
                continue;
            }
            String path = getSourcePathFromStorageClasses(media, variant, provider.getName());
            if (path != null && !path.isEmpty() && provider.exists(path)
                    && sourceMatchesChecksum(media, variant, provider, path)) {
                return new SyncSource(provider, path, false);
            }
        }
        return null;
    }

    private boolean sourceMatchesChecksum(Media media, VariantType variant,
                                          StorageClass provider, String path) {
        Object raw = media.storageClasses.get(provider.getName());
        String expected = null;
        if (raw instanceof Map<?, ?> variants
                && variants.get(variant.name().toLowerCase()) instanceof Map<?, ?> entry
                && entry.get("sha256") instanceof String checksum) {
            expected = checksum;
        }
        Path checked = null;
        try {
            checked = Files.createTempFile("windblog-source-check-", ".tmp");
            try (InputStream data = provider.download(path)) {
                Files.copy(data, checked, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            if (expected == null || expected.equals(EncryptedBackupCodec.sha256Hex(checked))) {
                return true;
            }
        } catch (Exception exception) {
            log.warn("Storage source unavailable: mediaId={}, provider={}", media.id,
                    provider.getName(), exception);
        } finally {
            if (checked != null) {
                try {
                    Files.deleteIfExists(checked);
                } catch (Exception ignored) {
                }
            }
        }
        selfProxy.get().updateVariantStatusWithLock(media.id, provider.getName(), variant,
                "corrupt", path, null, expected, databaseVersion(media.id));
        return false;
    }

    private boolean isNormalSynced(Media media, String name, VariantType variant) {
        if (media.storageClasses == null || !(media.storageClasses.get(name) instanceof Map<?, ?> variants)
                || !(variants.get(variant.name().toLowerCase()) instanceof Map<?, ?> entry)) {
            return false;
        }
        return "synced".equals(entry.get("status"));
    }

    private boolean isBackupSynced(Media media, String name, VariantType variant) {
        if (media.storageClasses == null || !(media.storageClasses.get(name) instanceof Map<?, ?> variants)
                || !(variants.get(variant.name().toLowerCase()) instanceof Map<?, ?> entry)) {
            return false;
        }
        return "backup_synced".equals(entry.get("status"));
    }

    private boolean copyExists(Media media, StorageClass provider, VariantType variant) {
        String path = getSourcePathFromStorageClasses(media, variant, provider.getName());
        return path != null && provider.exists(path);
    }

    private String canonicalUuid(Media media, VariantType variant, String sourcePath) {
        String base = media.storageKey == null ? "" : media.storageKey;
        base = base.substring(base.lastIndexOf('/') + 1);
        int dot = base.indexOf('.');
        String candidate = dot < 0 ? base : base.substring(0, dot);
        try {
            return UUID.fromString(candidate).toString();
        } catch (Exception ignored) {
            return UUID.nameUUIDFromBytes((media.id + ":" + variant.name())
                    .getBytes(StandardCharsets.UTF_8)).toString();
        }
    }

    private String canonicalNormalPath(String sourcePath, String uuid, VariantType variant, String mimeType) {
        String base = sourcePath.substring(sourcePath.lastIndexOf('/') + 1);
        if (base.startsWith(uuid) && base.matches("[a-fA-F0-9-]{36}([_a-zA-Z0-9-]*)?\\.[a-zA-Z0-9]{1,8}")) {
            return base;
        }
        return switch (variant) {
            case WEBP -> uuid + "_webp.webp";
            case PLACEHOLDER -> uuid + "_placeholder.jpg";
            case COVER -> uuid + "_cover.jpg";
            case RAW -> uuid + "_raw.bin";
            case ORIGINAL -> uuid + getExtensionFromMime(mimeType, variant);
        };
    }

    private String variantContentType(String originalMimeType, VariantType variant) {
        return switch (variant) {
            case WEBP -> "image/webp";
            case PLACEHOLDER, COVER -> "image/jpeg";
            default -> originalMimeType;
        };
    }

    /** Explicit administrator recovery; ordinary download paths never call this method. */
    public SyncResult restoreFromEncryptedBackup(Long mediaId, String targetName, VariantType variant) {
        Media media = Media.findById(mediaId);
        if (media == null || media.deletedAt != null || media.storageClasses == null) {
            return new SyncResult(false, "Media is not available");
        }
        StorageClassEntity targetEntity = getProviderEntityByName(targetName);
        if (regionPolicy.placement(media, targetEntity) != StorageRegionPolicy.Placement.NORMAL) {
            return new SyncResult(false, "Target cannot hold a normal copy in this region");
        }
        StorageClass target = findProviderByName(targetName);
        if (target == null) {
            return new SyncResult(false, "Target storage class is unavailable");
        }
        String uuid = canonicalUuid(media, variant, media.storageKey);
        for (StorageClass backup : enabledProviders) {
            if (!isBackupSynced(media, backup.getName(), variant)) {
                continue;
            }
            String backupPath = getSourcePathFromStorageClasses(media, variant, backup.getName());
            if (backupPath == null || !backupPath.contains("encrypted-backup/")) {
                continue;
            }
            Path restored = null;
            try {
                try (InputStream encrypted = backup.download(backupPath)) {
                    restored = backupCodec.decrypt(encrypted, uuid);
                }
                String targetPath = canonicalNormalPath("", uuid, variant, media.mimeType);
                String stored;
                try (InputStream plaintext = Files.newInputStream(restored)) {
                    stored = target.upload(plaintext, targetPath, variantContentType(media.mimeType, variant));
                }
                int version = databaseVersion(mediaId);
                if (!selfProxy.get().updateVariantStatusWithLock(mediaId, targetName, variant, "synced", stored,
                        Files.size(restored), EncryptedBackupCodec.sha256Hex(restored), version)) {
                    return new SyncResult(false, "Concurrent media update; retry recovery");
                }
                return new SyncResult(true, null);
            } catch (Exception exception) {
                log.warn("Encrypted media restore failed: mediaId={}, source={}", mediaId,
                        backup.getName(), exception);
            } finally {
                if (restored != null) {
                    try {
                        Files.deleteIfExists(restored);
                    } catch (Exception cleanupFailure) {
                        log.warn("Unable to delete private restore temporary file", cleanupFailure);
                    }
                }
            }
        }
        return new SyncResult(false, "No valid encrypted backup can restore this variant");
    }

    private int databaseVersion(Long mediaId) {
        return ((Number) entityManager.createNativeQuery("select version from media where id = ?1")
                .setParameter(1, mediaId).getSingleResult()).intValue();
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
        return updateVariantStatusWithLock(mediaId, storageClassName, variant,
                status, path, size, null, expectedVersion);
    }

    @Transactional
    public boolean updateVariantStatusWithLock(Long mediaId, String storageClassName,
                                               VariantType variant, String status,
                                               String path, Long size, String sha256,
                                               int expectedVersion) {
        String variantKey = variant.name().toLowerCase();
        Map<String, Object> variantInfo = new LinkedHashMap<>();
        variantInfo.put("status", status);
        variantInfo.put("path", path);
        variantInfo.put("size", size);
        variantInfo.put("mode", status.startsWith("backup_") ? "ENCRYPTED_BACKUP" : "NORMAL");
        if (sha256 != null) {
            variantInfo.put("sha256", sha256);
        }

        String jsonPayload;
        try {
            jsonPayload = objectMapper.writeValueAsString(variantInfo);
        } catch (Exception e) {
            log.error("Failed to serialize variant status", e);
            return false;
        }

        String sql = "UPDATE media SET "
                + "storage_classes = jsonb_set("
                + "  jsonb_set(COALESCE(storage_classes, '{}'::jsonb), ARRAY[?1::text], COALESCE(storage_classes -> ?1, '{}'::jsonb), true),"
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
            if (!provider.exists(path)) {
                continue;
            }

            StorageClassEntity entity = getProviderEntityByName(storageClassName);
            if (entity != null && entity.cdnEnabled != null && entity.cdnEnabled
                    && entity.cdnDomain != null && !entity.cdnDomain.isEmpty()) {
                return "https://" + entity.cdnDomain + "/" + path;
            }

            String publicUrl = provider.getPublicUrl(path);
            if (publicUrl != null && !publicUrl.isBlank()) {
                return publicUrl;
            }
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
            if (!provider.exists(path)) {
                continue;
            }
            String signedUrl = provider.getSignedUrl(path, expiration);
            if (signedUrl != null && !signedUrl.isBlank()) {
                return signedUrl;
            }
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
                        if ("synced".equals(variantStatus) || "backup_synced".equals(variantStatus)) {
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

        Map<String, Object> sourceStorageData = null;
        for (String name : media.storageClasses.keySet()) {
            Object value = media.storageClasses.get(name);
            if (value instanceof Map<?, ?> candidate && !candidate.isEmpty()) {
                sourceStorageData = (Map<String, Object>) candidate;
                break;
            }
        }
        if (sourceStorageData == null) {
            return;
        }

        List<String> variantNames = new ArrayList<>();
        for (String variantName : sourceStorageData.keySet()) {
            variantNames.add(variantName);
        }

        List<StorageClassEntity> storageClasses = getAllStorageClassEntities();
        for (StorageClassEntity storageClassEntity : storageClasses) {
            String storageClassName = storageClassEntity.name;
            if (regionPolicy.placement(media, storageClassEntity) == StorageRegionPolicy.Placement.SKIP) {
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

    public void reconcileMedia(Long mediaId) {
        Media media = Media.findById(mediaId);
        if (media == null || media.deletedAt != null || media.storageClasses == null) {
            return;
        }
        selfProxy.get().initializeStorageClassesForMedia(mediaId);
        scheduleSyncForMedia(mediaId);
        media = Media.findById(mediaId);
        if (media == null || media.storageClasses == null) {
            return;
        }
        for (StorageClass provider : enabledProviders) {
            StorageClassEntity entity = getProviderEntityByName(provider.getName());
            if (regionPolicy.placement(media, entity) != StorageRegionPolicy.Placement.SKIP) {
                continue;
            }
            Object raw = media.storageClasses.get(provider.getName());
            if (!(raw instanceof Map<?, ?> variants)) {
                continue;
            }
            for (VariantType variant : VariantType.values()) {
                if (!(variants.get(variant.name().toLowerCase()) instanceof Map<?, ?> entry)) {
                    continue;
                }
                Object rawPath = entry.get("path");
                if (rawPath == null || !hasHealthyNormalReplica(media, variant, provider.getName())) {
                    continue;
                }
                try {
                    provider.delete(rawPath.toString());
                    selfProxy.get().updateVariantStatus(mediaId, provider.getName(), variant,
                            "skipped", null, null, databaseVersion(mediaId));
                } catch (Exception exception) {
                    log.warn("Unable to remove excluded storage copy: mediaId={}, provider={}",
                            mediaId, provider.getName(), exception);
                }
            }
        }
    }

    /** Bounded caller controls the rate of full-object verification. */
    public void verifyMediaCopies(Long mediaId) {
        Media media = Media.findById(mediaId);
        if (media == null || media.deletedAt != null || media.storageClasses == null) {
            return;
        }
        for (StorageClass provider : enabledProviders) {
            Object raw = media.storageClasses.get(provider.getName());
            if (!(raw instanceof Map<?, ?> variants)) {
                continue;
            }
            for (VariantType variant : VariantType.values()) {
                if (!(variants.get(variant.name().toLowerCase()) instanceof Map<?, ?> entry)) {
                    continue;
                }
                String status = String.valueOf(entry.get("status"));
                if (!"synced".equals(status) && !"backup_synced".equals(status)) {
                    continue;
                }
                Object pathValue = entry.get("path");
                Object hashValue = entry.get("sha256");
                if (pathValue == null || hashValue == null) {
                    continue; // Legacy copies receive a checksum when next copied.
                }
                Path verified = null;
                try {
                    try (InputStream input = provider.download(pathValue.toString())) {
                        if ("backup_synced".equals(status)) {
                            verified = backupCodec.decrypt(input,
                                    canonicalUuid(media, variant, media.storageKey));
                        } else {
                            verified = Files.createTempFile("windblog-replica-verify-", ".tmp");
                            Files.copy(input, verified, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                        }
                    }
                    if (!hashValue.equals(EncryptedBackupCodec.sha256Hex(verified))) {
                        throw new java.io.IOException("Replica SHA-256 mismatch");
                    }
                } catch (Exception exception) {
                    log.warn("Corrupt or unavailable replica: mediaId={}, provider={}, variant={}",
                            mediaId, provider.getName(), variant, exception);
                    selfProxy.get().updateVariantStatusWithLock(mediaId, provider.getName(), variant,
                            "backup_synced".equals(status) ? "backup_corrupt" : "corrupt",
                            pathValue.toString(), null, hashValue.toString(), databaseVersion(mediaId));
                } finally {
                    if (verified != null) {
                        try {
                            Files.deleteIfExists(verified);
                        } catch (Exception cleanupFailure) {
                            log.warn("Unable to delete replica verification file", cleanupFailure);
                        }
                    }
                }
            }
        }
        scheduleSyncForMedia(mediaId);
    }

    public boolean isNormalAccessAllowed(Media media, String storageClassName) {
        return shouldReadFromStorageClass(media, storageClassName);
    }

    private boolean shouldReadFromStorageClass(Media media, String storageClassName) {
        if (media == null || storageClassName == null) {
            return false;
        }
        return regionPolicy.placement(media, getProviderEntityByName(storageClassName))
                == StorageRegionPolicy.Placement.NORMAL;
    }

    public boolean shouldSyncToStorageClass(Media media, String storageClassName) {
        if (media == null || storageClassName == null || storageClassName.isBlank()) {
            return false;
        }

        StorageClassEntity target = getProviderEntityByName(storageClassName);
        return target != null && regionPolicy.placement(media, target) != StorageRegionPolicy.Placement.SKIP;
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
