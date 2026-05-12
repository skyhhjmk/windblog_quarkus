package com.biliwind.blog.controller.api.admin;

import com.biliwind.blog.controller.api.admin.dto.storage.*;
import com.biliwind.blog.model.Media;
import com.biliwind.blog.model.StorageProviderEntity;
import com.biliwind.blog.service.storage.MediaSyncStatus;
import com.biliwind.blog.service.storage.StorageProvider;
import com.biliwind.blog.service.storage.StorageService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.ArrayList;
import java.util.List;

@ApplicationScoped
@Path("/api/admin/storage/providers")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class AdminStorageController {

    @Inject
    StorageService storageService;

    @GET
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
    @Transactional
    public Response createProvider(StorageProviderCreateRequest request) {
        StorageProviderEntity existing = StorageProviderEntity.find("name", request.name()).firstResult();
        if (existing != null) {
            throw new WebApplicationException("存储提供者名称已存在", Response.Status.CONFLICT);
        }

        StorageProviderEntity entity = new StorageProviderEntity();
        entity.name = request.name();
        entity.displayName = request.displayName();
        entity.providerType = request.providerType();
        entity.isEnabled = request.isEnabled();
        entity.isPrimary = request.isPrimary();
        entity.role = request.role();
        entity.configJson = request.configJson();
        entity.supportedTypes = request.supportedTypes();
        entity.cdnDomain = request.cdnDomain();
        entity.cdnEnabled = request.cdnEnabled();
        entity.region = request.region();
        entity.priority = request.priority();

        entity.persist();
        return Response.ok(StorageProviderResponse.fromEntity(entity)).build();
    }

    @GET
    @Path("/{name}")
    public Response getProvider(@PathParam("name") String name) {
        StorageProviderEntity entity = storageService.getProviderEntityByName(name);
        if (entity == null) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        return Response.ok(StorageProviderResponse.fromEntity(entity)).build();
    }

    @PUT
    @Path("/{name}")
    @Transactional
    public Response updateProvider(@PathParam("name") String name, StorageProviderUpdateRequest request) {
        StorageProviderEntity existing = storageService.getProviderEntityByName(name);
        if (existing == null) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }

        existing.displayName = request.displayName();
        existing.isEnabled = request.isEnabled();
        existing.configJson = request.configJson();
        existing.supportedTypes = request.supportedTypes();
        existing.cdnDomain = request.cdnDomain();
        existing.cdnEnabled = request.cdnEnabled();
        existing.region = request.region();
        existing.priority = request.priority();

        existing.persist();
        return Response.ok(StorageProviderResponse.fromEntity(existing)).build();
    }

    @DELETE
    @Path("/{name}")
    @Transactional
    public Response deleteProvider(@PathParam("name") String name) {
        StorageProviderEntity existing = storageService.getProviderEntityByName(name);
        if (existing == null) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        existing.isEnabled = false;
        existing.persist();
        return Response.noContent().build();
    }

    @POST
    @Path("/{name}/test")
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

    @POST
    @Path("/{name}/set-primary")
    @Transactional
    public Response setPrimaryProvider(@PathParam("name") String name) {
        StorageProviderEntity target = storageService.getProviderEntityByName(name);
        if (target == null) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }

        StorageProviderEntity.update("isPrimary = false");
        StorageProviderEntity.update("isPrimary = true where name = ?1", name);

        StorageProviderEntity updated = storageService.getProviderEntityByName(name);
        return Response.ok(StorageProviderResponse.fromEntity(updated)).build();
    }

    @GET
    @Path("/sync-status")
    public Response getSyncStatus() {
        List<Media> allMedia = Media.listAll();
        int totalMedia = allMedia.size();
        int totalVariants = 0;
        int syncedCount = 0;
        int pendingCount = 0;
        int failedCount = 0;
        ArrayList<MediaSyncDetailResponse> details = new ArrayList<>();

        for (Media media : allMedia) {
            if (media.storageNodes != null) {
                for (java.util.Map.Entry<String, Object> entry : media.storageNodes.entrySet()) {
                    Object nodeDataObj = entry.getValue();
                    if (nodeDataObj instanceof java.util.Map) {
                        java.util.Map<String, Object> nodeData = (java.util.Map<String, Object>) nodeDataObj;
                        for (java.util.Map.Entry<String, Object> varEntry : nodeData.entrySet()) {
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
                    media.id, media.fileName, media.mimeType, media.storageNodes);
            details.add(detail);
        }

        SyncStatusResponse response = new SyncStatusResponse(
                totalMedia, totalVariants, syncedCount, pendingCount, failedCount, details);
        return Response.ok(response).build();
    }

    @GET
    @Path("/sync-status/{mediaId}")
    public Response getSyncDetail(@PathParam("mediaId") Long mediaId) {
        MediaSyncStatus status = storageService.getSyncStatus(mediaId);
        if (status == null) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }

        Media media = Media.findById(mediaId);
        MediaSyncDetailResponse detail = new MediaSyncDetailResponse(
                media.id, media.fileName, media.mimeType, media.storageNodes);
        return Response.ok(detail).build();
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
