package com.biliwind.blog.service.ai.tool;

import com.biliwind.blog.model.Media;
import com.biliwind.blog.model.User;
import com.biliwind.blog.service.MediaManagementService;
import com.biliwind.blog.service.ai.AiTool;
import com.biliwind.blog.service.ai.AiToolContext;
import com.biliwind.blog.service.ai.AiToolDefinition;
import com.biliwind.blog.service.storage.StorageService;
import com.biliwind.blog.service.storage.VariantType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Downloads an external image URL into WindBlog's media library via
 * {@link MediaManagementService#importFromUrl} and returns its public access URL.
 */
@ApplicationScoped
public class ImportImageToMediaTool implements AiTool {

    private static final AiToolDefinition DEFINITION = new AiToolDefinition(
            "import_image_to_media",
            "Download an external image URL into WindBlog's media library and return its local/CDN access URL and Markdown snippet. "
                    + "Always use this tool before embedding any external image in an article.",
            Map.of(
                    "type", "object",
                    "properties", Map.of(
                            "imageUrl", Map.of("type", "string", "description", "Public http/https image URL to import"),
                            "alt", Map.of("type", "string", "description", "Optional alt text for the Markdown image")
                    ),
                    "required", List.of("imageUrl")
            )
    );

    @Inject
    MediaManagementService mediaManagementService;

    @Inject
    StorageService storageService;

    @Inject
    ObjectMapper objectMapper;

    @Override
    public AiToolDefinition definition() {
        return DEFINITION;
    }

    @Override
    public String execute(JsonNode arguments, AiToolContext context) throws Exception {
        if (context != null && !context.allowMediaImport()) {
            return objectMapper.writeValueAsString(Map.of("success", false, "error", "当前上下文不允许导入媒体"));
        }
        String imageUrl = arguments == null ? "" : arguments.path("imageUrl").asText("").trim();
        String alt = arguments == null ? "" : arguments.path("alt").asText("").trim();
        if (imageUrl.isBlank()) {
            return objectMapper.writeValueAsString(Map.of("success", false, "error", "imageUrl 不能为空"));
        }

        try {
            Map<String, Object> imported = QuarkusTransaction.requiringNew().call(() -> {
                User operator = resolveOperator(context == null ? null : context.operatorId());
                if (operator == null) {
                    throw new IllegalStateException("找不到可用于导入媒体的操作用户");
                }
                Media media = mediaManagementService.importFromUrl(operator, imageUrl);
                String accessUrl = resolveAccessUrl(media);
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("success", true);
                result.put("mediaId", media.id);
                result.put("url", accessUrl);
                result.put("fileName", media.fileName);
                result.put("mimeType", media.mimeType);
                if (media.width != null) result.put("width", media.width);
                if (media.height != null) result.put("height", media.height);
                String safeAlt = alt.isBlank() ? (media.fileName == null ? "image" : media.fileName) : alt;
                result.put("markdown", "![" + safeAlt.replace("]", "") + "](" + accessUrl + ")");
                return result;
            });
            return objectMapper.writeValueAsString(imported);
        } catch (Exception exception) {
            Throwable cause = exception.getCause() == null ? exception : exception.getCause();
            return objectMapper.writeValueAsString(Map.of(
                    "success", false,
                    "imageUrl", imageUrl,
                    "error", cause.getMessage() == null ? "导入图片失败" : cause.getMessage()
            ));
        }
    }

    private String resolveAccessUrl(Media media) {
        if (media == null) return "";
        String url = media.storageClasses == null
                ? media.url
                : storageService.getBestAccessUrl(media, VariantType.ORIGINAL);
        if (url == null || url.isBlank()) {
            url = media.url == null ? "" : media.url;
        }
        return url;
    }

    private User resolveOperator(Long operatorId) {
        if (operatorId != null) {
            User found = User.findById(operatorId);
            if (found != null) return found;
        }
        return User.find("order by id asc").firstResult();
    }
}
