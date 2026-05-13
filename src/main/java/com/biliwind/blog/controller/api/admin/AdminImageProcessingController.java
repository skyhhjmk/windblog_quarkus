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
import java.util.List;
import java.util.Map;

@ApplicationScoped
@Path("/api/admin/storage/image-processing/configs")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class AdminImageProcessingController {

    @Inject
    EntityManager entityManager;

    @GET
    public Response listConfigs() {
        List<ImageProcessingConfig> configs = ImageProcessingConfig.listAll();
        return Response.ok(configs).build();
    }

    @PUT
    @Transactional
    public Response updateConfig(ImageProcessingConfigUpdateRequest request) {
        ImageProcessingConfig existing = ImageProcessingConfig.find("configKey", request.configKey()).firstResult();
        if (existing == null) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }

        existing.configValue = request.configValue;
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

    @GET
    @Path("/metadata")
    public Response getMetadata() {
        List<Map<String, Object>> metadata = List.of(
                Map.of(
                        "key", "cwebp_path",
                        "label", "cwebp 工具路径",
                        "type", "text",
                        "description", "Google WebP 转换工具的绝对路径",
                        "testUrl", "/api/admin/storage/image-processing/configs/test-webp"
                ),
                Map.of(
                        "key", "ffmpeg_path",
                        "label", "FFmpeg 工具路径",
                        "type", "text",
                        "description", "FFmpeg 视频处理工具的绝对路径",
                        "testUrl", "/api/admin/storage/image-processing/configs/test-ffmpeg"
                ),
                Map.of(
                        "key", "webp_quality",
                        "label", "WebP 转换质量",
                        "type", "number",
                        "min", 0.0,
                        "max", 1.0,
                        "description", "WebP 图片压缩质量 (0.0 - 1.0)"
                ),
                Map.of(
                        "key", "webp_method",
                        "label", "WebP 压缩强度",
                        "type", "number",
                        "min", 0,
                        "max", 6,
                        "description", "WebP 压缩方法 (0最快, 6最慢质量最好)"
                ),
                Map.of(
                        "key", "placeholder_max_width",
                        "label", "占位图最大宽度",
                        "type", "number",
                        "description", "生成的占位图片最大宽度 (px)"
                ),
                Map.of(
                        "key", "placeholder_quality",
                        "label", "占位图质量",
                        "type", "number",
                        "min", 0.0,
                        "max", 1.0,
                        "description", "占位图 JPEG 压缩质量"
                )
        );
        return Response.ok(metadata).build();
    }

    public record TestToolResult(String tool, boolean available, String message) {
    }

    public record ImageProcessingConfigUpdateRequest(String configKey, String configValue) {
    }
}
