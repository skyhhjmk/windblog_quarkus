package com.biliwind.blog.controller;

import com.biliwind.blog.model.Media;
import com.biliwind.blog.service.storage.StorageService;
import com.biliwind.blog.service.storage.VariantType;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.nio.file.Files;

@Path("/uploads")
public class UploadFileController {

    @ConfigProperty(name = "media.upload.dir")
    String uploadDir;

    @Inject
    StorageService storageService;

    @GET
    @Path("/{fileName}")
    @Produces(MediaType.WILDCARD)
    public Response getFile(@PathParam("fileName") String fileName) {
        if (fileName == null || fileName.isBlank() || fileName.contains("/") || fileName.contains("\\")) {
            throw new NotFoundException();
        }

        try {
            String storageKeyCandidate = extractStorageKey(fileName);
            Media media = Media.find("storageKey = ?1 AND deletedAt IS NULL", storageKeyCandidate).firstResult();
            if (media != null && media.storageProviders != null) {
                String bestUrl = storageService.getBestAccessUrl(media, VariantType.ORIGINAL);
                if (bestUrl != null && !bestUrl.isBlank()) {
                    return Response.status(Response.Status.FOUND)
                            .header("Location", bestUrl)
                            .build();
                }
            }
        } catch (Exception ignored) {
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
