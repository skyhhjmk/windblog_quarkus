package com.biliwind.blog.controller.api.admin;

import com.biliwind.blog.controller.api.admin.dto.storage.*;
import com.biliwind.blog.model.Media;
import com.biliwind.blog.model.StorageProviderEntity;
import com.biliwind.blog.service.storage.MediaSyncStatus;
import com.biliwind.blog.service.storage.StorageProvider;
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
    @Path("/providers")
    public Response listProviders() {
        List<StorageProviderEntity> entities = storageService.getAllProviderEntities();
        ArrayList<StorageProviderResponse> result = new ArrayList<>();
        for (StorageProviderEntity entity : entities) {
            StorageProviderResponse response = StorageProviderResponse.fromEntity(entity);
            result.add(response);
        }
        return Response.ok(result).build();
    }

    @POST
    @Path("/providers")
    @Transactional
    public Response createProvider(StorageProviderCreateRequest request) {
        StorageProviderEntity existing = StorageProviderEntity.find("name", request.name()).firstResult();
        if (existing != null) {
            throw new WebApplicationException("存储提供者名称已存在", Response.Status.CONFLICT);
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

        StorageProviderEntity entity = new StorageProviderEntity();
        entity.name = request.name();
        entity.displayName = request.displayName();
        entity.providerType = request.providerType();
        entity.isEnabled = request.isEnabled() != null ? request.isEnabled() : true;
        entity.isPrimary = request.isPrimary() != null ? request.isPrimary() : false;

        if (entity.isPrimary) {
            StorageProviderEntity.update("isPrimary = false where isPrimary = true");
        }

        // Ensure role is not null
        String role = request.role();
        if (role == null || role.isBlank()) {
            role = entity.isPrimary ? "primary" : "backup";
        }
        entity.role = role;

        entity.configJson = configJson;
        entity.supportedTypes = supportedTypes;
        entity.cdnDomain = request.cdnDomain();
        entity.cdnEnabled = request.cdnEnabled() != null ? request.cdnEnabled() : false;
        entity.region = request.region() != null ? request.region() : "global";
        entity.priority = request.priority() != null ? request.priority() : 0;

        entity.persist();
        return Response.ok(StorageProviderResponse.fromEntity(entity)).build();
    }

    @PUT
    @Path("/providers/{id}")
    @Transactional
    public Response updateProvider(@PathParam("id") Long id, StorageProviderUpdateRequest request) {
        StorageProviderEntity existing = StorageProviderEntity.findById(id);
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

        if (request.isPrimary() != null && request.isPrimary()) {
            StorageProviderEntity.update("isPrimary = false where isPrimary = true");
            existing.isPrimary = true;
            // If it becomes primary, force the role to primary if not already set or archive
            if (request.role() == null || request.role().isBlank()) {
                existing.role = "primary";
            }
        } else if (request.isPrimary() != null) {
            existing.isPrimary = false;
        }

        // Update role if provided
        if (request.role() != null && !request.role().isBlank()) {
            existing.role = request.role();
        }

        existing.configJson = configJson;
        existing.supportedTypes = supportedTypes;
        existing.cdnDomain = request.cdnDomain();
        existing.cdnEnabled = request.cdnEnabled() != null ? request.cdnEnabled() : existing.cdnEnabled;
        existing.region = request.region() != null ? request.region() : existing.region;
        existing.priority = request.priority() != null ? request.priority() : existing.priority;

        existing.persist();
        return Response.ok(StorageProviderResponse.fromEntity(existing)).build();
    }

    @DELETE
    @Path("/providers/{id}")
    @Transactional
    public Response deleteProvider(@PathParam("id") Long id) {
        StorageProviderEntity existing = StorageProviderEntity.findById(id);
        if (existing == null) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        existing.isEnabled = false;
        existing.persist();
        return Response.noContent().build();
    }

    @POST
    @Path("/providers/test/{name}")
    public Response testProvider(@PathParam("name") String name) {
        StorageProvider provider = findLoadedProvider(name);
        if (provider == null) {
            StorageTestResult result = new StorageTestResult(false, "Provider 未加载或不存在");
            return Response.status(Response.Status.NOT_FOUND).entity(result).build();
        }

        boolean available = provider.isAvailable();
        String message = "连接失败";
        if (available) {
            message = "连接成功";
        }
        StorageTestResult result = new StorageTestResult(available, message);
        return Response.ok(result).build();
    }

    @GET
    @Path("/sync/status")
    public Response getSyncStatus() {
        List<Media> allMedia = Media.listAll();
        int totalMedia = allMedia.size();
        int totalVariants = 0;
        int syncedCount = 0;
        int pendingCount = 0;
        int failedCount = 0;
        ArrayList<MediaSyncDetailResponse> details = new ArrayList<>();

        for (Media media : allMedia) {
            if (media.storageProviders != null) {
                for (java.util.Map.Entry<String, Object> entry : media.storageProviders.entrySet()) {
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

            MediaSyncDetailResponse detail = new MediaSyncDetailResponse(
                    media.id, media.fileName, media.mimeType, media.storageProviders);
            details.add(detail);
        }

        SyncStatusResponse response = new SyncStatusResponse(
                totalMedia, totalVariants, syncedCount, pendingCount, failedCount, details);
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
                media.id, media.fileName, media.mimeType, media.storageProviders);
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

    private StorageProvider findLoadedProvider(String name) {
        StorageProvider primary = storageService.getPrimaryProvider();
        if (primary != null && primary.getName().equals(name)) {
            return primary;
        }

        List<StorageProvider> nonPrimary = storageService.getNonPrimaryProviders();
        for (StorageProvider provider : nonPrimary) {
            if (provider.getName().equals(name)) {
                return provider;
            }
        }
        return null;
    }
}
