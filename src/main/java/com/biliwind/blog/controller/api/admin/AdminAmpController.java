package com.biliwind.blog.controller.api.admin;

import com.biliwind.blog.service.AmpPageService;
import com.biliwind.blog.service.PublicUrlService;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

@Path("/api/admin/system/amp")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class AdminAmpController {

    @Inject
    AmpPageService ampPageService;

    @Inject
    PublicUrlService publicUrlService;

    @ConfigProperty(name = "windblog.amp.enabled", defaultValue = "true")
    boolean enabled;

    @GET
    public Response getInfo() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("enabled", enabled);
        data.put("publicBaseUrl", publicUrlService.getBaseUrl());
        data.put("defaultRoute", "/amp/post/{slug}");
        data.put("localizedRoute", "/{lang}/amp/post/{slug}");
        data.put("cacheMaxAgeSeconds", 300);
        data.put("protectedContentPolicy", "受保护或付费文章不生成 AMP 页面");
        data.put("configurationSource", "WIND_BLOG_AMP_ENABLED，修改后重启应用");
        return Response.ok(Map.of("success", true, "data", data)).build();
    }

    @POST
    @Path("/check")
    public Response check(AmpCheckRequest request) {
        if (request == null || request.slug() == null || request.slug().isBlank()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("success", false, "message", "文章 slug 不能为空"))
                    .build();
        }
        String language = request.language();
        java.util.Optional<AmpPageService.AmpPost> result = ampPageService.find(request.slug(), language);
        if (result.isEmpty()) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(Map.of("success", false, "message", "文章不存在或尚未发布"))
                    .build();
        }

        AmpPageService.AmpPost post = result.get();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("available", enabled && post.available());
        data.put("enabled", enabled);
        String reason = post.unavailableReason();
        if (!enabled) {
            reason = "AMP 功能已关闭";
        }
        data.put("reason", reason);
        data.put("slug", post.slug());
        data.put("language", post.language());
        data.put("title", post.title());
        data.put("canonicalUrl", post.canonicalUrl());
        data.put("ampUrl", post.ampUrl());
        data.put("lastUpdated", post.lastUpdated() == null ? null : post.lastUpdated().toString());
        data.put("contentLength", post.html().length());
        data.put("imageCount", post.imageCount());
        data.put("removedElementCount", post.removedElementCount());
        return Response.ok(Map.of("success", true, "data", data)).build();
    }

    public record AmpCheckRequest(String slug, String language) {
    }
}
