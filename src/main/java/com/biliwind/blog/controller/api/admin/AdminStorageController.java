package com.biliwind.blog.controller.api.admin;

import com.biliwind.blog.controller.api.admin.dto.storage.*;
import com.biliwind.blog.model.Media;
import com.biliwind.blog.model.StorageClassEntity;
import com.biliwind.blog.service.storage.MediaSyncStatus;
import com.biliwind.blog.service.storage.StorageClass;
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
import java.util.List;

@Path("/api/admin/storage")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@ApplicationScoped
public class AdminStorageController {

    @Inject
    StorageService storageService;

    @Inject
    ObjectMapper objectMapper;

    @GET
    @Path("/classes")
    public Response listClasses() {
        List<StorageClassEntity> entities = storageService.getAllStorageClassEntities();
        ArrayList<StorageClassResponse> result = new ArrayList<>();
        for (StorageClassEntity entity : entities) {
            StorageClassResponse response = StorageClassResponse.fromEntity(entity);
            result.add(response);
        }
        return Response.ok(result).build();
    }

    @POST
    @Path("/classes")
    @Transactional
    public Response createClass(StorageClassCreateRequest request) {
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
                configJson = node.toString();
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
        entity.priority = request.priority() != null ? request.priority() : 0;

        entity.persist();
        return Response.ok(StorageClassResponse.fromEntity(entity)).build();
    }

    @PUT
    @Path("/classes/{id}")
    @Transactional
    public Response updateClass(@PathParam("id") Long id, StorageClassUpdateRequest request) {
        StorageClassEntity existing = StorageClassEntity.findById(id);
        if (existing == null) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }

        String configJson = request.configJson();
        if (configJson == null || configJson.isBlank()) {
            configJson = "{}";
        } else {
            try {
                JsonNode node = objectMapper.readTree(configJson);
                configJson = node.toString();
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
        existing.priority = request.priority() != null ? request.priority() : existing.priority;

        existing.persist();
        return Response.ok(StorageClassResponse.fromEntity(existing)).build();
    }

    @DELETE
    @Path("/classes/{id}")
    @Transactional
    public Response deleteClass(@PathParam("id") Long id) {
        StorageClassEntity existing = StorageClassEntity.findById(id);
        if (existing == null) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        existing.isEnabled = false;
        existing.persist();
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
    public Response getSyncStatus(@QueryParam("page") @DefaultValue("0") int page,
                                  @QueryParam("size") @DefaultValue("20") int size) {
        // 计算全量统计信息 (这里可以优化为原生 SQL 以提高性能，目前先保持逻辑简单)
        List<Media> allMedia = Media.listAll();
        int totalMedia = allMedia.size();
        int totalVariants = 0;
        int syncedCount = 0;
        int pendingCount = 0;
        int failedCount = 0;

        for (Media media : allMedia) {
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

        // 分页获取详情列表
        io.quarkus.panache.common.Page panachePage = io.quarkus.panache.common.Page.of(page, size);
        List<Media> paginatedMedia = Media.find("order by createdAt desc").page(panachePage).list();
        long totalDetails = Media.count();

        ArrayList<MediaSyncDetailResponse> details = new ArrayList<>();
        for (Media media : paginatedMedia) {
            MediaSyncDetailResponse detail = new MediaSyncDetailResponse(
                    media.id, media.fileName, media.mimeType, media.storageClasses);
            details.add(detail);
        }

        SyncStatusResponse response = new SyncStatusResponse(
                totalMedia, totalVariants, syncedCount, pendingCount, failedCount,
                details, totalDetails, page, size);
        return Response.ok(response).build();
    }

    @GET
    @Path("/sync/detail/{mediaId}")
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
                media.id, media.fileName, media.mimeType, media.storageClasses);
        return Response.ok(detail).build();
    }

    @POST
    @Path("/sync/trigger/{mediaId}")
    public Response triggerStorageSync(@PathParam("mediaId") Long mediaId) {
        storageService.scheduleSyncForMedia(mediaId);
        return Response.noContent().build();
    }

    @POST
    @Path("/sync/batch-trigger")
    public Response triggerBatchStorageSync() {
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
}
