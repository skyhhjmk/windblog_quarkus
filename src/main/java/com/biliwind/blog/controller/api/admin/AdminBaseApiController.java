package com.biliwind.blog.controller.api.admin;

import com.biliwind.blog.context.AdminRequestContext;
import com.biliwind.blog.model.Category;
import com.biliwind.blog.model.Comment;
import com.biliwind.blog.model.Post;
import com.biliwind.blog.model.User;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.security.SecurityRequirement;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import java.time.OffsetDateTime;
import java.util.Map;

@Path("/api/admin/base")
@Produces(MediaType.APPLICATION_JSON)
@Tag(name = "AdminBase")
public class AdminBaseApiController {

    @Inject
    AdminRequestContext adminRequestContext;

    @GET
    @Path("/ping")
    @SecurityRequirement(name = "adminBearerAuth")
    @Operation(summary = "后台连通性检查")
    @APIResponse(responseCode = "200", description = "成功")
    public Map<String, Object> ping() {
        return Map.of(
                "success", true,
                "time", OffsetDateTime.now(),
                "userId", adminRequestContext.getUserId(),
                "username", adminRequestContext.getUsername()
        );
    }

    @GET
    @Path("/overview")
    @SecurityRequirement(name = "adminBearerAuth")
    @Operation(summary = "后台概览数据")
    @APIResponse(responseCode = "200", description = "成功")
    public Response overview() {
        long users = User.count("deletedAt is null");
        long posts = Post.count("deletedAt is null");
        long comments = Comment.count("deletedAt is null");
        long tags = com.biliwind.blog.model.Tag.count();
        long categories = Category.count();

        return Response.ok(Map.of(
                "success", true,
                "data", Map.of(
                        "users", users,
                        "posts", posts,
                        "comments", comments,
                        "tags", tags,
                        "categories", categories
                )
        )).build();
    }
}
