package com.biliwind.blog.controller;

import com.biliwind.blog.model.Media;
import com.biliwind.blog.service.MediaAccessService;
import com.biliwind.blog.service.PostAccessService;
import com.biliwind.blog.service.storage.StorageService;
import com.biliwind.blog.service.storage.VariantType;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.nio.file.Files;
import java.util.List;

@Path("/uploads")
public class UploadFileController {

    @ConfigProperty(name = "media.upload.dir")
    String uploadDir;

    @Inject
    StorageService storageService;

    @Inject
    MediaAccessService mediaAccessService;

    @Inject
    PostAccessService postAccessService;

    @Inject
    com.biliwind.blog.context.RegionContext regionContext;

    @GET
    @Path("/{fileName}")
    @Produces(MediaType.WILDCARD)
    public Response getFile(@PathParam("fileName") String fileName, @Context UriInfo uriInfo) {
        if (fileName == null || fileName.isBlank() || fileName.contains("/") || fileName.contains("\\")) {
            throw new NotFoundException();
        }

        try {
            String storageKeyCandidate = extractStorageKey(fileName);
            Media media = findMediaForStoragePath(fileName, storageKeyCandidate);

            // 软删除防绕过：数据库中不存在或已软删除的文件，一律拒绝访问，
            // 不允许因 media 为 null 就跳过权限检查直接读取本地物理文件。
            if (media == null) {
                throw new NotFoundException();
            }

            if (postAccessService.hasProtectedMediaReference(media.id)) {
                throw new NotFoundException();
            }

            if (!mediaAccessService.canAccess(media, regionContext.getCurrentRegion())) {
                throw new NotFoundException();
            }
            if (media.storageClasses != null) {
                String bestUrl = storageService.getBestAccessUrl(media, VariantType.ORIGINAL);
                if (bestUrl != null && !bestUrl.isBlank()) {
                    if (isSameRequestUrl(bestUrl, uriInfo) == false) {
                        return Response.status(Response.Status.FOUND)
                                .header("Location", bestUrl)
                                .build();
                    }
                }
            }
        } catch (NotFoundException notFoundException) {
            throw notFoundException;
        } catch (Exception exception) {
            throw new ServiceUnavailableException("媒体访问校验暂不可用");
        }

        java.nio.file.Path root = java.nio.file.Paths.get(uploadDir).toAbsolutePath().normalize();
        java.nio.file.Path target = root.resolve(fileName).normalize();
        if (!target.startsWith(root) || !Files.exists(target) || !Files.isRegularFile(target)) {
            throw new NotFoundException();
        }

        String mimeType = probeMimeType(target);
        return Response.ok(target.toFile(), mimeType)
                .header("Cache-Control", "public, max-age=31536000, immutable")
                .header("X-Access-IF-Type", "D")
                .build();
    }

    private String extractStorageKey(String fileName) {
        String base = fileName;
        int dotIndex = fileName.lastIndexOf('.');
        if (dotIndex > 0) {
            base = fileName.substring(0, dotIndex);
        }
        String suffix = "_cover";
        if (base.endsWith(suffix)) {
            return base.substring(0, base.length() - suffix.length()) + ".jpg";
        }
        String suffixP = "_p";
        if (base.endsWith(suffixP)) {
            return base.substring(0, base.length() - suffixP.length()) + ".jpg";
        }
        String suffixWebp = ".webp";
        if (fileName.endsWith(suffixWebp)) {
            return base + ".webp";
        }
        return fileName;
    }

    private Media findMediaForStoragePath(String fileName, String storageKeyCandidate) {
        Media exact = Media.find(
                "storageKey = ?1 AND deletedAt IS NULL", storageKeyCandidate).firstResult();
        if (exact != null) {
            return exact;
        }

        String variantBase = extractGeneratedVariantBase(fileName);
        if (variantBase == null) {
            return null;
        }

        List<Media> candidates = Media.find(
                "storageKey like ?1 AND deletedAt IS NULL", variantBase + ".%")
                .range(0, 1)
                .list();
        if (candidates.size() != 1) {
            return null;
        }
        return candidates.get(0);
    }

    static String extractGeneratedVariantBase(String fileName) {
        String[] suffixes = {"_placeholder.jpg", "_cover.jpg", "_p.jpg", ".webp"};
        for (String suffix : suffixes) {
            if (fileName.endsWith(suffix) && fileName.length() > suffix.length()) {
                return fileName.substring(0, fileName.length() - suffix.length());
            }
        }
        return null;
    }

    private boolean isSameRequestUrl(String targetUrl, UriInfo uriInfo) {
        if (targetUrl == null) {
            return false;
        }
        if (uriInfo == null) {
            return false;
        }

        String normalizedTargetUrl = normalizeUrlForComparison(targetUrl);
        String requestPath = uriInfo.getRequestUri().getRawPath();
        String normalizedRequestPath = normalizeUrlForComparison(requestPath);
        if (normalizedTargetUrl.equals(normalizedRequestPath)) {
            return true;
        }

        String absoluteRequestUrl = uriInfo.getRequestUri().toString();
        String normalizedAbsoluteRequestUrl = normalizeUrlForComparison(absoluteRequestUrl);
        return normalizedTargetUrl.equals(normalizedAbsoluteRequestUrl);
    }

    private String normalizeUrlForComparison(String url) {
        String normalizedUrl = url.trim();
        int fragmentIndex = normalizedUrl.indexOf('#');
        if (fragmentIndex >= 0) {
            normalizedUrl = normalizedUrl.substring(0, fragmentIndex);
        }
        int queryIndex = normalizedUrl.indexOf('?');
        if (queryIndex >= 0) {
            normalizedUrl = normalizedUrl.substring(0, queryIndex);
        }
        while (normalizedUrl.endsWith("/") && normalizedUrl.length() > 1) {
            normalizedUrl = normalizedUrl.substring(0, normalizedUrl.length() - 1);
        }
        return normalizedUrl;
    }

    private String probeMimeType(java.nio.file.Path path) {
        String detected = null;
        try {
            detected = Files.probeContentType(path);
        } catch (Exception ignored) {
        }
        if (detected != null && !detected.isBlank()) {
            return detected;
        }
        return MediaType.APPLICATION_OCTET_STREAM;
    }
}
