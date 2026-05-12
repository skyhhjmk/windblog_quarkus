package com.biliwind.blog.controller.api.admin;

import com.biliwind.blog.model.ImageProcessingConfig;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.nio.file.Files;

@ApplicationScoped
@Path("/api/admin/image-processing/config")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class AdminImageProcessingController {

    @Inject
    EntityManager entityManager;

    @GET
    public Response listConfigs() {
        java.util.List<ImageProcessingConfig> configs = ImageProcessingConfig.listAll();
        return Response.ok(configs).build();
    }

    @PUT
    @Path("/{key}")
    @Transactional
    public Response updateConfig(@PathParam("key") String key, ImageProcessingConfig config) {
        ImageProcessingConfig existing = ImageProcessingConfig.find("configKey", key).firstResult();
        if (existing == null) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }

        existing.configValue = config.configValue;
        existing.persist();
        return Response.ok(existing).build();
    }

    @POST
    @Path("/test-webp")
    public Response testWebp() {
        ImageProcessingConfig cwebpConfig = ImageProcessingConfig.find("configKey = ?1", "cwebp_path").firstResult();
        String cwebpPath = "/usr/bin/cwebp";
        if (cwebpConfig != null) {
            cwebpPath = cwebpConfig.configValue;
        }

        java.nio.file.Path cwebpPathObj = java.nio.file.Path.of(cwebpPath);
        boolean exists = Files.exists(cwebpPathObj);
        boolean executable = false;
        if (exists) {
            executable = Files.isExecutable(cwebpPathObj);
        }

        String message = "cwebp 工具检测: ";
        if (exists && executable) {
            message = message + "存在且可执行";
        } else if (exists) {
            message = message + "存在但不可执行";
        } else {
            message = message + "不存在于路径: " + cwebpPath;
        }

        TestToolResult result = new TestToolResult("cwebp", exists && executable, message);
        return Response.ok(result).build();
    }

    @POST
    @Path("/test-ffmpeg")
    public Response testFfmpeg() {
        ImageProcessingConfig ffmpegConfig = ImageProcessingConfig.find("configKey = ?1", "ffmpeg_path").firstResult();
        String ffmpegPath = "/usr/bin/ffmpeg";
        if (ffmpegConfig != null) {
            ffmpegPath = ffmpegConfig.configValue;
        }

        java.nio.file.Path ffmpegPathObj = java.nio.file.Path.of(ffmpegPath);
        boolean exists = Files.exists(ffmpegPathObj);
        boolean executable = false;
        if (exists) {
            executable = Files.isExecutable(ffmpegPathObj);
        }

        String message = "FFmpeg 工具检测: ";
        if (exists && executable) {
            message = message + "存在且可执行";
        } else if (exists) {
            message = message + "存在但不可执行";
        } else {
            message = message + "不存在于路径: " + ffmpegPath;
        }

        TestToolResult result = new TestToolResult("ffmpeg", exists && executable, message);
        return Response.ok(result).build();
    }

    public record TestToolResult(String tool, boolean available, String message) {
    }
}
