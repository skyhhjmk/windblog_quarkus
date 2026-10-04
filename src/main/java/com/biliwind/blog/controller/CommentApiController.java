package com.biliwind.blog.controller;

import com.biliwind.blog.common.helper.CommentMarkdownHelper;
import com.biliwind.blog.model.Comment;
import com.biliwind.blog.model.CommentQuote;
import com.biliwind.blog.model.Post;
import com.biliwind.blog.model.User;
import com.biliwind.blog.service.ConfigManager;
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
    com.biliwind.blog.common.security.UserTokenVerifier tokenVerifier;

    @Inject
    ConfigManager configManager;

    @Inject
    com.biliwind.blog.service.ReliableAiTaskService reliableAiTaskService;

    @Inject
    com.biliwind.blog.service.CodexCreatorEventPublisher codexCreatorEventPublisher;

    @Inject
    com.biliwind.blog.common.CacheService cacheService;

    @GET
    @Path("/post/{slug}")
    @Transactional
    public Response listByPost(
            @PathParam("slug") String slug,
            @QueryParam("page") @DefaultValue("0") int page,
            @QueryParam("size") @DefaultValue("10") int size) {
        Post post = findPublicPost(slug);

        // 1. 分页查询根评论
        io.quarkus.hibernate.orm.panache.PanacheQuery<Comment> rootQuery = Comment.find(
                "post = ?1 and parent is null and status = ?2 and deletedAt is null order by createdAt desc",
                post, STATUS_APPROVED);
        List<Comment> roots = rootQuery.page(page, size).list();
        long totalRoots = rootQuery.count();

        if (roots.isEmpty()) {
            return Response.ok(Map.of(
                    "success", true,
                    "data", List.of(),
                    "total", 0,
                    "page", page,
                    "size", size
            )).build();
        }

        // 2. 为了构建树，我们需要获取这些根评论的所有后代。
        // 由于评论通常按时间排序且量级在文章维度受限，我们查出文章下所有通过的评论并在内存构建树。
        // 但为了性能，我们只返回选定的根评论。
        List<Comment> allApproved = Comment.list(
                "post = ?1 and status = ?2 and deletedAt is null order by createdAt asc",
                post, STATUS_APPROVED);

        Map<Long, CommentNode> nodeIndex = new HashMap<>();
        List<CommentNode> resultRoots = new ArrayList<>();
        Set<Long> targetRootIds = new HashSet<>();
        for (Comment r : roots) {
            targetRootIds.add(r.id);
        }

        for (Comment comment : allApproved) {
            CommentNode node = toNode(comment);
            nodeIndex.put(comment.id, node);

            Long parentId = comment.parent != null ? comment.parent.id : null;
            if (parentId != null) {
                CommentNode parentNode = nodeIndex.get(parentId);
                if (parentNode != null) {
                    parentNode.replies().add(node);
                }
            }
        }

        // 按分页查询出的 roots 顺序（desc）组装结果
        for (Comment r : roots) {
            CommentNode rootNode = nodeIndex.get(r.id);
            if (rootNode != null) {
                resultRoots.add(rootNode);
            }
        }

        return Response.ok(Map.of(
                "success", true,
                "data", resultRoots,
                "total", totalRoots,
                "page", page,
                "size", size
        )).build();
    }

    @POST
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
        if (user.emailVerifiedAt == null) {
            throw new ForbiddenException("请先完成邮箱验证后再发表评论");
        }

        final Post post = findPublicPost(request.postSlug());
        final Comment parent = resolveParent(post, request.parentId());
        final String content = normalizeContent(request.content());

        final Comment comment = new Comment();
        // 用于在事务外携带数据
        class CreateResult {
            Long id;
            String content;
            boolean isReviewing;
        }
        final CreateResult result = new CreateResult();

        io.quarkus.narayana.jta.QuarkusTransaction.requiringNew().run(() -> {
            comment.post = post;
            comment.parent = parent;
            comment.user = user;
            comment.content = content;
            comment.status = STATUS_PENDING;

            // 触发 AI 审核状态预设
            com.biliwind.blog.model.SystemSetting auditSetting = com.biliwind.blog.model.SystemSetting.findByKey("ai_comment_audit");
            if (auditSetting != null && auditSetting.configValue != null
                    && auditSetting.configValue.path("autoAudit").asBoolean(false)) {
                comment.auditStatus = 1; // 审核中
                comment.isReviewing = true;
            }
            comment.persist();

            if (request.quoteType() != null && !request.quoteType().isBlank()) {
                CommentQuote quote = new CommentQuote();
                quote.comment = comment;
                quote.post = post;
                quote.quoteType = request.quoteType().trim().toUpperCase();
                quote.quoteText = request.quoteText() != null ? request.quoteText() : "";

                if (request.anchorDataJson() != null && !request.anchorDataJson().isBlank()) {
                    try {
                        quote.anchorData = objectMapper.readTree(request.anchorDataJson());
                    } catch (Exception e) {
                        quote.anchorData = new HashMap<String, Object>();
                    }
                } else {
                    quote.anchorData = new HashMap<String, Object>();
                }
                quote.status = 0;
                quote.persist();
            }

            result.id = comment.id;
            result.content = comment.content;
            result.isReviewing = comment.isReviewing;
        });

        // 提交成功后发送异步任务
        if (result.isReviewing) {
            reliableAiTaskService.enqueueAudit(
                    new com.biliwind.blog.service.ai.AiAuditTask(result.id, result.content));
        }
        codexCreatorEventPublisher.commentCreated(result.id, post.id, result.content, null);

        // 清理侧边栏统计
        cacheService.delete(com.biliwind.blog.common.CacheService.Keys.SIDEBAR_STATS);

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

        Post post = Post.find(
                "slug = ?1 and status = ?2 and deletedAt is null and publishedRevision is not null",
                slug.trim(),
                com.biliwind.blog.model.PostStatus.PUBLISHED
        ).firstResult();
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
                comment.user != null ? comment.user.username
                        : (comment.guestName == null || comment.guestName.isBlank() ? "Guest" : comment.guestName),
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

        com.biliwind.blog.common.security.UserTokenVerifier.VerifiedToken verified = tokenVerifier.verify(cookie.getValue());
        if (verified == null) {
            return null;
        }

        User user = User.find("id = ?1 and deletedAt is null", verified.uid()).firstResult();
        if (user == null || user.status != 1) {
            return null;
        }
        return user;
    }

    @GET
    @Path("/quotes/post/{slug}")
    @Transactional
    public Response listQuotesByPost(@PathParam("slug") String slug) {
        Post post = findPublicPost(slug);

        List<CommentQuote> quotes = CommentQuote.list(
                "post = ?1 and comment.status = ?2 and comment.deletedAt is null and (quoteType = 'QUOTE' or (quoteType = 'CORRECTION' and status = 0))",
                post, STATUS_APPROVED);

        List<Map<String, Object>> resultList = new ArrayList<>();
        for (CommentQuote q : quotes) {
            Map<String, Object> map = new HashMap<>();
            map.put("id", q.id);
            map.put("commentId", q.comment.id);
            map.put("userName", q.comment.user != null ? q.comment.user.username : "Guest");
            map.put("content", q.comment.content);
            map.put("quoteType", q.quoteType);
            map.put("quoteText", q.quoteText);
            map.put("anchorData", q.anchorData);
            map.put("status", q.status);
            map.put("createdAt", q.createdAt);
            resultList.add(map);
        }

        return Response.ok(Map.of(
                "success", true,
                "data", resultList
        )).build();
    }

    public record CommentCreateRequest(
            @NotBlank String postSlug,
            Long parentId,
            @NotBlank @Size(max = MAX_COMMENT_LENGTH) String content,
            String quoteType,
            String quoteText,
            String anchorDataJson) {
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
