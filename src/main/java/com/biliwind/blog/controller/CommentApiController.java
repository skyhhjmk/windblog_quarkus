package com.biliwind.blog.controller;

import com.biliwind.blog.common.helper.CommentMarkdownHelper;
import com.biliwind.blog.model.Comment;
import com.biliwind.blog.model.Post;
import com.biliwind.blog.model.User;
import com.biliwind.blog.service.ConfigManager;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.*;

import java.time.OffsetDateTime;
import java.util.*;

@Path("/api/comments")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class CommentApiController {

    private static final short STATUS_PENDING = 0;
    private static final short STATUS_APPROVED = 1;
    private static final int MAX_COMMENT_LENGTH = 4000;

    @Inject
    ObjectMapper objectMapper;

    @Inject
    ConfigManager configManager;

    @GET
    @Path("/post/{slug}")
    @Transactional
    public Response listByPost(@PathParam("slug") String slug) {
        Post post = findPublicPost(slug);
        List<Comment> comments = Comment.list(
                "post = ?1 and status = ?2 and deletedAt is null order by createdAt asc",
                post, STATUS_APPROVED);

        Map<Long, CommentNode> nodeIndex = new HashMap<>();
        List<CommentNode> roots = new ArrayList<>();

        for (Comment comment : comments) {
            CommentNode node = toNode(comment);
            nodeIndex.put(comment.id, node);

            Long parentId = comment.parent != null ? comment.parent.id : null;
            if (parentId == null) {
                roots.add(node);
                continue;
            }

            CommentNode parentNode = nodeIndex.get(parentId);
            if (parentNode == null) {
                roots.add(node);
                continue;
            }

            parentNode.replies().add(node);
        }

        roots.sort(Comparator.comparing(CommentNode::createdAt));

        return Response.ok(Map.of("success", true, "data", roots)).build();
    }

    @POST
    @Transactional
    public Response create(CommentCreateRequest request, @Context HttpHeaders headers) {
        if (!configManager.getBoolean("feature_toggles", "enable_comment", true)) {
            return Response.status(Response.Status.FORBIDDEN)
                    .entity(Map.of("success", false, "message", "评论功能已关闭")).build();
        }
        if (request == null) {
            throw new BadRequestException("Request body is required");
        }

        User user = resolveUserFromCookie(headers);
        if (user == null) {
            throw new ForbiddenException("Login required");
        }

        Post post = findPublicPost(request.postSlug());
        Comment parent = resolveParent(post, request.parentId());

        String content = normalizeContent(request.content());
        Comment comment = new Comment();
        comment.post = post;
        comment.parent = parent;
        comment.user = user;
        comment.content = content;
        comment.status = STATUS_PENDING;
        comment.persist();

        return Response.status(Response.Status.CREATED)
                .entity(Map.of(
                        "success", true,
                        "message", "Comment submitted for review",
                        "data", Map.of(
                                "id", comment.id,
                                "status", comment.status,
                                "createdAt", comment.createdAt)))
                .build();
    }

    private Post findPublicPost(String slug) {
        if (slug == null || slug.isBlank()) {
            throw new NotFoundException("Post not found");
        }

        Post post = Post.find("slug = ?1 and deletedAt is null", slug.trim()).firstResult();
        if (post == null || post.visibility == 1) {
            throw new NotFoundException("Post not found");
        }
        return post;
    }

    private Comment resolveParent(Post post, Long parentId) {
        if (parentId == null) {
            return null;
        }

        Comment parent = Comment.findById(parentId);
        if (parent == null || parent.deletedAt != null) {
            throw new BadRequestException("Parent comment not found");
        }
        if (!parent.post.id.equals(post.id)) {
            throw new BadRequestException("Parent comment does not belong to the post");
        }
        return parent;
    }

    private String normalizeContent(String content) {
        if (content == null) {
            throw new BadRequestException("Comment content is required");
        }

        String trimmed = content.trim();
        if (trimmed.isBlank()) {
            throw new BadRequestException("Comment content is required");
        }
        if (trimmed.length() > MAX_COMMENT_LENGTH) {
            throw new BadRequestException("Comment content is too long");
        }
        return trimmed;
    }

    private CommentNode toNode(Comment comment) {
        return new CommentNode(
                comment.id,
                comment.parent != null ? comment.parent.id : null,
                comment.user != null ? comment.user.id : null,
                comment.user != null ? comment.user.username : "Guest",
                comment.content,
                CommentMarkdownHelper.toSafeHtml(comment.content),
                comment.createdAt,
                new ArrayList<>());
    }

    private User resolveUserFromCookie(HttpHeaders headers) {
        Cookie cookie = headers.getCookies().get("user_token");
        if (cookie == null || cookie.getValue() == null || cookie.getValue().isBlank()) {
            return null;
        }

        try {
            String[] parts = cookie.getValue().split("\\.");
            if (parts.length != 3) {
                return null;
            }

            String payload = new String(Base64.getUrlDecoder().decode(parts[1]));
            Map<String, Object> claims = objectMapper.readValue(payload, new TypeReference<>() {
            });
            Long uid = claims.get("uid") instanceof Number n ? n.longValue() : null;
            if (uid == null) {
                return null;
            }

            User user = User.find("id = ?1 and deletedAt is null", uid).firstResult();
            if (user == null || user.status != 1) {
                return null;
            }
            return user;
        } catch (Exception ignored) {
            return null;
        }
    }

    public record CommentCreateRequest(
            @NotBlank String postSlug,
            Long parentId,
            @NotBlank @Size(max = MAX_COMMENT_LENGTH) String content) {
    }

    public record CommentNode(
            Long id,
            Long parentId,
            Long userId,
            String userName,
            String content,
            String contentHtml,
            OffsetDateTime createdAt,
            List<CommentNode> replies) {
    }
}
