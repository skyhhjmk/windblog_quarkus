package com.biliwind.blog.controller;

import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.nio.file.Files;
import java.nio.file.Paths;

@Path("/uploads")
public class UploadFileController {

    @ConfigProperty(name = "media.upload.dir")
    String uploadDir;

    @GET
    @Path("/{fileName}")
    @Produces(MediaType.WILDCARD)
    public Response getFile(@PathParam("fileName") String fileName) {
        if (fileName == null || fileName.isBlank() || fileName.contains("/") || fileName.contains("\\")) {
            throw new NotFoundException();
        }

        java.nio.file.Path root = Paths.get(uploadDir).toAbsolutePath().normalize();
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

    private String probeMimeType(java.nio.file.Path path) {
        try {
            String detected = Files.probeContentType(path);
            if (detected != null && !detected.isBlank()) {
                return detected;
            }
        } catch (Exception ignored) {
        }
        return MediaType.APPLICATION_OCTET_STREAM;
    }
}
