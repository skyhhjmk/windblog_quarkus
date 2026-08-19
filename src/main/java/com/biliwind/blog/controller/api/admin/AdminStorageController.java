package com.biliwind.blog.controller.api.admin;

import com.biliwind.blog.controller.api.admin.dto.storage.*;
import com.biliwind.blog.model.Media;
import com.biliwind.blog.model.StorageClassEntity;
import com.biliwind.blog.common.CacheService;
import com.biliwind.blog.service.RegionValidationService;
import com.biliwind.blog.service.edge.EdgeWriteGuard;
import com.biliwind.blog.service.storage.MediaSyncStatus;
import com.biliwind.blog.service.storage.StorageClass;
import com.biliwind.blog.service.storage.StorageConfigProtector;
import com.biliwind.blog.service.storage.StorageService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Path("/api/admin/storage")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@ApplicationScoped
public class AdminStorageController {

    @Inject
    StorageService storageService;

    @Inject
    ObjectMapper objectMapper;

    @Inject
    RegionValidationService regionValidationService;

    @Inject
    StorageConfigProtector storageConfigProtector;

    @Inject
    EdgeWriteGuard edgeWriteGuard;

    @Inject
    CacheService cacheService;

    @GET
    @Path("/classes")
    @Transactional
    public Response listClasses() {
        List<StorageClassEntity> entities = storageService.getAllStorageClassEntities();
        ArrayList<StorageClassResponse> result = new ArrayList<>();
        for (StorageClassEntity entity : entities) {
            StorageClassResponse response = StorageClassResponse.fromEntity(entity, storageConfigProtector);
            result.add(response);
        }
        return Response.ok(result).build();
    }

    @POST
    @Path("/classes")
    @Transactional
    public Response createClass(StorageClassCreateRequest request) {
        edgeWriteGuard.rejectWriteOnEdge("创建存储类");
        StorageClassEntity existing = StorageClassEntity.find("name", request.name()).firstResult();
        if (existing != null) {
            throw new WebApplicationException("存储类名称已存在", Response.Status.CONFLICT);
        }

        String configJson = request.configJson();
        if (configJson == null || configJson.isBlank()) {
            configJson = "{}";
        } else {
            try {
                JsonNode node = objectMapper.readTree(configJson);
                configJson = storageConfigProtector.protectForStorage(node.toString());
            } catch (Exception e) {
                throw new WebApplicationException("Invalid configJson format", Response.Status.BAD_REQUEST);
            }
        }

        String supportedTypes = request.supportedTypes();
        if (supportedTypes == null || supportedTypes.isBlank()) {
            supportedTypes = "[\"*\"]";
        } else {
            try {
                JsonNode node = objectMapper.readTree(supportedTypes);
                if (node.isArray()) {
                    supportedTypes = node.toString();
                } else if (node.isTextual()) {
                    supportedTypes = "[\"" + node.asText() + "\"]";
                } else {
                    throw new WebApplicationException("supportedTypes must be a JSON array", Response.Status.BAD_REQUEST);
                }
            } catch (Exception e) {
                String[] parts = supportedTypes.split(",");
                try {
                    ArrayList<String> list = new ArrayList<>();
                    for (String p : parts) {
                        list.add(p.trim());
                    }
                    supportedTypes = objectMapper.writeValueAsString(list);
                } catch (Exception ex) {
                    supportedTypes = "[\"*\"]";
                }
            }
        }

        StorageClassEntity entity = new StorageClassEntity();
        entity.name = request.name();
        entity.displayName = request.displayName();
        entity.providerType = normalizeProviderType(request.providerType());
        entity.isEnabled = request.isEnabled() != null ? request.isEnabled() : true;
        entity.role = normalizeStorageClassRole(request.role(), request.isPrimary());
        entity.isPrimary = "primary".equals(entity.role);
        if (entity.isPrimary) {
            StorageClassEntity.update("isPrimary = false where isPrimary = true");
        }

        entity.configJson = configJson;
        entity.supportedTypes = supportedTypes;
        entity.cdnDomain = request.cdnDomain();
        entity.cdnEnabled = request.cdnEnabled() != null ? request.cdnEnabled() : false;
        entity.serviceRegion = normalizeOptionalText(request.serviceRegion());
        entity.contentRegions = normalizeContentRegions(request.contentRegions());
        entity.priority = request.priority() != null ? request.priority() : 0;

        entity.persist();
        invalidateMediaCache();
        return Response.ok(StorageClassResponse.fromEntity(entity, storageConfigProtector)).build();
    }

    @PUT
    @Path("/classes/{id}")
    @Transactional
    public Response updateClass(@PathParam("id") Long id, StorageClassUpdateRequest request) {
        edgeWriteGuard.rejectWriteOnEdge("更新存储类");
        StorageClassEntity existing = StorageClassEntity.findById(id);
        if (existing == null) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }

        String configJson = request.configJson();
        if (configJson == null || configJson.isBlank()) {
            configJson = existing.configJson;
        } else {
            try {
                JsonNode node = objectMapper.readTree(configJson);
                configJson = storageConfigProtector.protectForStorage(node.toString(), existing.configJson);
            } catch (Exception e) {
                throw new WebApplicationException("Invalid configJson format", Response.Status.BAD_REQUEST);
            }
        }

        String supportedTypes = request.supportedTypes();
        if (supportedTypes == null || supportedTypes.isBlank()) {
            supportedTypes = "[\"*\"]";
        } else {
            try {
                JsonNode node = objectMapper.readTree(supportedTypes);
                if (node.isArray()) {
                    supportedTypes = node.toString();
                } else if (node.isTextual()) {
                    supportedTypes = "[\"" + node.asText() + "\"]";
                } else {
                    throw new WebApplicationException("supportedTypes must be a JSON array", Response.Status.BAD_REQUEST);
                }
            } catch (Exception e) {
                String[] parts = supportedTypes.split(",");
                try {
                    ArrayList<String> list = new ArrayList<>();
                    for (String p : parts) {
                        list.add(p.trim());
                    }
                    supportedTypes = objectMapper.writeValueAsString(list);
                } catch (Exception ex) {
                    supportedTypes = "[\"*\"]";
                }
            }
        }

        existing.displayName = request.displayName();
        existing.isEnabled = request.isEnabled() != null ? request.isEnabled() : existing.isEnabled;

        String normalizedRole = normalizeStorageClassRole(request.role(), request.isPrimary());
        existing.role = normalizedRole;
        existing.isPrimary = "primary".equals(normalizedRole);
        if (existing.isPrimary) {
            StorageClassEntity.update("isPrimary = false where isPrimary = true");
        }

        existing.configJson = configJson;
        existing.supportedTypes = supportedTypes;
        existing.cdnDomain = request.cdnDomain();
        existing.cdnEnabled = request.cdnEnabled() != null ? request.cdnEnabled() : existing.cdnEnabled;
        if (request.serviceRegion() != null) {
            existing.serviceRegion = normalizeOptionalText(request.serviceRegion());
        }
        if (request.contentRegions() != null) {
            existing.contentRegions = normalizeContentRegions(request.contentRegions());
        }
        existing.priority = request.priority() != null ? request.priority() : existing.priority;

        existing.persist();
        invalidateMediaCache();
        return Response.ok(StorageClassResponse.fromEntity(existing, storageConfigProtector)).build();
    }

    @DELETE
    @Path("/classes/{id}")
    @Transactional
    public Response deleteClass(@PathParam("id") Long id) {
        edgeWriteGuard.rejectWriteOnEdge("删除存储类");
        StorageClassEntity existing = StorageClassEntity.findById(id);
        if (existing == null) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        existing.isEnabled = false;
        existing.persist();
        invalidateMediaCache();
        return Response.noContent().build();
    }

    @POST
    @Path("/classes/test/{name}")
    public Response testClass(@PathParam("name") String name) {
        StorageClass storageClass = findLoadedStorageClass(name);
        if (storageClass == null) {
            StorageTestResult result = new StorageTestResult(false, "存储类未加载或不存在");
            return Response.status(Response.Status.NOT_FOUND).entity(result).build();
        }

        boolean available = storageClass.isAvailable();
        String message = "连接失败";
        if (available) {
            message = "连接成功";
        }
        StorageTestResult result = new StorageTestResult(available, message);
        return Response.ok(result).build();
    }

    @GET
    @Path("/sync/status")
    @Transactional
    public Response getSyncStatus(@QueryParam("page") @DefaultValue("0") int page,
                                  @QueryParam("size") @DefaultValue("20") int size) {
        // 统计扫描按固定批次进行，避免管理端请求把全部媒体实体同时装入 JVM。
        final int scanBatchSize = 200;
        int scanPage = 0;
        int totalMedia = 0;
        int totalVariants = 0;
        int syncedCount = 0;
        int pendingCount = 0;
        int failedCount = 0;

        while (true) {
            List<Media> mediaBatch = Media.find("order by id").page(
                    io.quarkus.panache.common.Page.of(scanPage, scanBatchSize)).list();
            if (mediaBatch.isEmpty()) {
                break;
            }
            totalMedia += mediaBatch.size();
            for (Media media : mediaBatch) {
                if (media.storageClasses != null) {
                    for (java.util.Map.Entry<String, Object> entry : media.storageClasses.entrySet()) {
                        Object providerDataObj = entry.getValue();
                        if (providerDataObj instanceof java.util.Map) {
                            java.util.Map<String, Object> providerData = (java.util.Map<String, Object>) providerDataObj;
                            for (java.util.Map.Entry<String, Object> varEntry : providerData.entrySet()) {
                                Object variantDataObj = varEntry.getValue();
                                if (variantDataObj instanceof java.util.Map) {
                                    java.util.Map<String, Object> variantData = (java.util.Map<String, Object>) variantDataObj;
                                    Object statusObj = variantData.get("status");
                                    String status = "unknown";
                                    if (statusObj != null) {
                                        status = statusObj.toString();
                                    }
                                    totalVariants++;
                                    if ("synced".equals(status)) {
                                        syncedCount++;
                                    } else if ("pending".equals(status)) {
                                        pendingCount++;
                                    } else if ("failed".equals(status)) {
                                        failedCount++;
                                    }
                                }
                            }
                        }
                    }
                }
            }
            scanPage++;
        }

        // 分页获取详情列表
        io.quarkus.panache.common.Page panachePage = io.quarkus.panache.common.Page.of(page, size);
        List<Media> paginatedMedia = Media.find("order by createdAt desc").page(panachePage).list();
        long totalDetails = Media.count();

        ArrayList<MediaSyncDetailResponse> details = new ArrayList<>();
        for (Media media : paginatedMedia) {
            MediaSyncDetailResponse detail = new MediaSyncDetailResponse(
                    media.id, media.fileName, media.mimeType, safeStorageClasses(media.storageClasses));
            details.add(detail);
        }

        SyncStatusResponse response = new SyncStatusResponse(
                totalMedia, totalVariants, syncedCount, pendingCount, failedCount,
                details, totalDetails, page, size);
        return Response.ok(response).build();
    }

    @GET
    @Path("/sync/detail/{mediaId}")
    @Transactional
    public Response getSyncDetail(@PathParam("mediaId") Long mediaId) {
        MediaSyncStatus status = storageService.getSyncStatus(mediaId);
        if (status == null) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }

        Media media = Media.findById(mediaId);
        if (media == null) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        MediaSyncDetailResponse detail = new MediaSyncDetailResponse(
                media.id, media.fileName, media.mimeType, safeStorageClasses(media.storageClasses));
        return Response.ok(detail).build();
    }

    static Map<String, Object> safeStorageClasses(Map<String, Object> storageClasses) {
        if (storageClasses == null || storageClasses.isEmpty()) {
            return Map.of();
        }

        Map<String, Object> safeProviders = new LinkedHashMap<>();
        for (Map.Entry<String, Object> providerEntry : storageClasses.entrySet()) {
            if (!(providerEntry.getValue() instanceof Map<?, ?> providerValue)) {
                continue;
            }

            Map<String, Object> safeVariants = new LinkedHashMap<>();
            for (Map.Entry<?, ?> variantEntry : providerValue.entrySet()) {
                if (!(variantEntry.getKey() instanceof String variantName)
                        || !(variantEntry.getValue() instanceof Map<?, ?> variantValue)) {
                    continue;
                }

                Map<String, Object> safeVariant = new LinkedHashMap<>();
                copySafeScalar(variantValue, safeVariant, "status");
                copySafeScalar(variantValue, safeVariant, "size");
                safeVariants.put(variantName, safeVariant);
            }
            safeProviders.put(providerEntry.getKey(), safeVariants);
        }
        return safeProviders;
    }

    private static void copySafeScalar(Map<?, ?> source, Map<String, Object> target, String key) {
        Object value = source.get(key);
        if (value instanceof String || value instanceof Number || value instanceof Boolean) {
            target.put(key, value);
        }
    }

    @POST
    @Path("/sync/trigger/{mediaId}")
    public Response triggerStorageSync(@PathParam("mediaId") Long mediaId) {
        edgeWriteGuard.rejectWriteOnEdge("触发媒体存储同步");
        storageService.scheduleSyncForMedia(mediaId);
        return Response.noContent().build();
    }

    @POST
    @Path("/sync/batch-trigger")
    public Response triggerBatchStorageSync() {
        edgeWriteGuard.rejectWriteOnEdge("批量触发媒体存储同步");
        storageService.scheduleSyncForAllPending();
        return Response.noContent().build();
    }

    private StorageClass findLoadedStorageClass(String name) {
        StorageClass primary = storageService.getPrimaryStorageClass();
        if (primary != null && primary.getName().equals(name)) {
            return primary;
        }

        List<StorageClass> nonPrimary = storageService.getNonPrimaryStorageClasses();
        for (StorageClass storageClass : nonPrimary) {
            if (storageClass.getName().equals(name)) {
                return storageClass;
            }
        }
        return null;
    }

    private String normalizeProviderType(String providerType) {
        if ("aliyun_oss_v2".equals(providerType)) {
            return "oss_aliyun";
        }
        return providerType;
    }

    private String normalizeStorageClassRole(String role, Boolean isPrimary) {
        if (isPrimary != null && isPrimary) {
            return "primary";
        }
        if (role == null || role.isBlank()) {
            return "backup";
        }
        String normalizedRole = role.trim();
        if ("primary".equals(normalizedRole)) {
            return "primary";
        }
        if ("origin".equals(normalizedRole)) {
            return "origin";
        }
        if ("archive".equals(normalizedRole)) {
            return "archive";
        }
        return "backup";
    }

    private String normalizeOptionalText(String text) {
        if (text == null) {
            return null;
        }
        String trimmedText = text.trim();
        if (trimmedText.isEmpty()) {
            return null;
        }
        return trimmedText;
    }

    private List<String> normalizeContentRegions(List<String> contentRegions) {
        List<String> normalizedContentRegions = regionValidationService.validateAndFilterRegions(contentRegions);
        if (normalizedContentRegions == null || normalizedContentRegions.isEmpty()) {
            return null;
        }
        return normalizedContentRegions;
    }

    private void invalidateMediaCache() {
        cacheService.deletePattern(CacheService.Keys.MEDIA_META_PREFIX + "*");
    }
}
