package com.biliwind.blog.controller;

import com.biliwind.blog.service.PostViewService;
import com.biliwind.blog.service.security.ClientIpResolver;
import io.vertx.ext.web.RoutingContext;
import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * 公开文章浏览量异步统计与批量查询接口。
 */
@Path("/api/public/posts")
@Produces(MediaType.APPLICATION_JSON)
public class PostViewApiController {

    @Inject
    PostViewService postViewService;

    @Inject
    ClientIpResolver clientIpResolver;

    @GET
    @Path("/views")
    public Response getViews(@QueryParam("slugs") String slugs) {
        if (slugs == null || slugs.isBlank()) {
            return Response.ok(Map.of("success", true, "data", Map.of()))
                    .header("Cache-Control", "no-cache")
                    .build();
        }
        List<String> slugList = Arrays.stream(slugs.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
        Map<String, Long> counts = postViewService.getViewCounts(slugList);
        return Response.ok(Map.of("success", true, "data", counts))
                .header("Cache-Control", "no-cache")
                .build();
    }

    @POST
    @Path("/{slug}/view")
    public Response recordView(@PathParam("slug") String slug, @Context RoutingContext context) {
        if (slug == null || slug.isBlank()) {
            throw new BadRequestException("文章标识不能为空");
        }
        String clientIp = clientIpResolver != null && context != null
                ? clientIpResolver.resolve(context).clientIp()
                : null;
        long count = postViewService.recordView(slug, clientIp)
                .orElseThrow(() -> new NotFoundException("文章不存在"));
        return Response.ok(Map.of("success", true, "data", Map.of(
                        "slug", slug.trim(),
                        "viewCount", count)))
                .header("Cache-Control", "no-store")
                .build();
    }
}
