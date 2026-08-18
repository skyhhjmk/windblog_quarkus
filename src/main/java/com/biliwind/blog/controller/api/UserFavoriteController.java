package com.biliwind.blog.controller.api;

import com.biliwind.blog.common.security.UserTokenVerifier;
import com.biliwind.blog.model.Post;
import com.biliwind.blog.model.PostStatus;
import com.biliwind.blog.model.User;
import com.biliwind.blog.model.UserPostFavorite;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Cookie;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** 用户收藏公开文章。 */
@Path("/api/user/favorites")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "UserFavorite")
public class UserFavoriteController {

    @Inject
    UserTokenVerifier tokenVerifier;

    @GET
    @Transactional
    @Operation(summary = "查询收藏文章")
    public Response list(@Context HttpHeaders headers,
                         @QueryParam("page") @DefaultValue("1") int page,
                         @QueryParam("pageSize") @DefaultValue("20") int pageSize) {
        User user = requireUser(headers);
        int safePage = Math.max(page, 1);
        int safeSize = Math.min(Math.max(pageSize, 1), 50);
        List<UserPostFavorite> all = UserPostFavorite.list(
                "user = ?1 and post.deletedAt is null and post.status = ?2 order by createdAt desc",
                user, PostStatus.PUBLISHED);
        int from = Math.min((safePage - 1) * safeSize, all.size());
        int to = Math.min(from + safeSize, all.size());
        List<FavoriteItem> items = new ArrayList<>();
        for (UserPostFavorite favorite : all.subList(from, to)) {
            items.add(toItem(favorite));
        }
        return Response.ok(Map.of("success", true, "data", items, "total", all.size(),
                "page", safePage, "pageSize", safeSize)).build();
    }

    @GET
    @Path("/post/{slug}/status")
    @Transactional
    @Operation(summary = "查询文章收藏状态")
    public Response status(@PathParam("slug") String slug, @Context HttpHeaders headers) {
        User user = requireUser(headers);
        Post post = publishedPost(slug);
        UserPostFavorite favorite = UserPostFavorite.find("user = ?1 and post = ?2", user, post).firstResult();
        return Response.ok(Map.of("success", true, "favorited", favorite != null)).build();
    }

    @POST
    @Path("/post/{slug}")
    @Transactional
    @Operation(summary = "收藏文章")
    public Response add(@PathParam("slug") String slug, @Context HttpHeaders headers) {
        User user = requireUser(headers);
        Post post = publishedPost(slug);
        UserPostFavorite favorite = UserPostFavorite.find("user = ?1 and post = ?2", user, post).firstResult();
        if (favorite == null) {
            favorite = new UserPostFavorite();
            favorite.user = user;
            favorite.post = post;
            favorite.createdAt = OffsetDateTime.now();
            favorite.persist();
        }
        return Response.ok(Map.of("success", true, "favorited", true)).build();
    }

    @DELETE
    @Path("/post/{slug}")
    @Transactional
    @Operation(summary = "取消收藏文章")
    public Response remove(@PathParam("slug") String slug, @Context HttpHeaders headers) {
        User user = requireUser(headers);
        Post post = publishedPost(slug);
        UserPostFavorite.delete("user = ?1 and post = ?2", user, post);
        return Response.ok(Map.of("success", true, "favorited", false)).build();
    }

    private Post publishedPost(String slug) {
        Post post = Post.find("slug = ?1 and status = ?2 and deletedAt is null and publishedRevision is not null",
                slug, PostStatus.PUBLISHED).firstResult();
        if (post == null) {
            throw new NotFoundException("文章不存在");
        }
        return post;
    }

    private FavoriteItem toItem(UserPostFavorite favorite) {
        Post post = favorite.post;
        return new FavoriteItem(post.id, post.slug, localized(post.title), localized(post.summary), favorite.createdAt);
    }

    private String localized(Map<String, String> values) {
        if (values == null || values.isEmpty()) {
            return "";
        }
        String value = values.get("zh-CN");
        if (value == null) {
            value = values.get("zh-cn");
        }
        return value == null ? values.values().iterator().next() : value;
    }

    private User requireUser(HttpHeaders headers) {
        Cookie cookie = headers.getCookies().get("user_token");
        if (cookie == null || cookie.getValue() == null || cookie.getValue().isBlank()) {
            throw new NotAuthorizedException("请先登录");
        }
        UserTokenVerifier.VerifiedToken verified = tokenVerifier.verify(cookie.getValue());
        User user = verified == null ? null
                : User.find("id = ?1 and status = 1 and deletedAt is null", verified.uid()).firstResult();
        if (user == null) {
            throw new NotAuthorizedException("登录已过期");
        }
        return user;
    }

    public record FavoriteItem(Long id, String slug, String title, String summary, OffsetDateTime createdAt) {
    }
}
