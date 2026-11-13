package com.biliwind.blog.controller;

import com.biliwind.blog.common.helper.CommentMarkdownHelper;
import com.biliwind.blog.model.Comment;
import com.biliwind.blog.model.CommentQuote;
import com.biliwind.blog.model.Post;
import com.biliwind.blog.model.User;
import com.biliwind.blog.service.settings.SettingKey;
import com.biliwind.blog.service.settings.SettingsReader;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.runtime.annotations.RegisterForReflection;
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
    private static final int MAX_PAGE_SIZE = 50;
    private static final int MAX_PAGE_INDEX = 1000;
    private static final int MAX_REPLY_DEPTH = 50;
    private static final int MAX_COMMENT_NODES = 2000;

    @Inject
    ObjectMapper objectMapper;

    @Inject
    com.biliwind.blog.common.security.UserTokenVerifier tokenVerifier;

    @Inject
    SettingsReader settings;

    @Inject
    com.biliwind.blog.service.ReliableAiTaskService reliableAiTaskService;

    @Inject
    com.biliwind.blog.common.CacheService cacheService;

    @Inject
    com.biliwind.blog.service.CommentCaptchaService captchaService;

    @Inject
    com.biliwind.blog.service.security.SecurityRateLimitService rateLimitService;

    @Inject
    com.biliwind.blog.service.security.ClientIpResolver clientIpResolver;

    private void checkRate(io.vertx.ext.web.RoutingContext context, String action, int limit) {
        String ip = clientIpResolver.resolve(context).clientIp();
        if (!rateLimitService.tryAcquire("comment-" + action + ":" + ip, limit, java.time.Duration.ofMinutes(1))) {
            throw new WebApplicationException("操作过于频繁，请稍后重试", 429);
        }
    }

    @GET
    @Path("/captcha")
    public Response captcha(@Context io.vertx.ext.web.RoutingContext context) {
        if (!settings.flag(SettingKey.FEATURE_TOGGLES, "enable_comment")) {
            return Response.status(Response.Status.FORBIDDEN)
                    .entity(Map.of("success", false, "message", "评论功能已关闭")).build();
        }
        checkRate(context, "captcha", 30);
        return Response.ok(Map.of("success", true, "data", captchaService.issue()))
                .header("Cache-Control", "no-store").build();
    }

    @GET
    @Path("/post/{slug}")
    @Transactional
    public Response listByPost(
            @PathParam("slug") String slug,
            @QueryParam("page") @DefaultValue("0") int page,
            @QueryParam("size") @DefaultValue("10") int size) {
        Post post = findPublicPost(slug);
        int safePage = Math.min(MAX_PAGE_INDEX, Math.max(0, page));
        int safeSize = Math.min(MAX_PAGE_SIZE, Math.max(1, size));

        // 1. 分页查询根评论
        io.quarkus.hibernate.orm.panache.PanacheQuery<Comment> rootQuery = Comment.find(
                "post = ?1 and parent is null and status = ?2 and deletedAt is null order by createdAt desc",
                post, STATUS_APPROVED);
        List<Comment> roots = rootQuery.page(safePage, safeSize).list();
        long totalRoots = rootQuery.count();

        if (roots.isEmpty()) {
            return Response.ok(Map.of(
                    "success", true,
                    "data", List.of(),
                    "total", 0,
                    "page", safePage,
                    "size", safeSize
            )).build();
        }

        // 2. 只加载当前页根评论的后代，避免每次请求都扫描整篇文章的评论。
        Map<Long, CommentNode> nodeIndex = new LinkedHashMap<>();
        List<CommentNode> resultRoots = new ArrayList<>();
        Set<Long> rootIds = new LinkedHashSet<>();
        Map<Long, String> userNames = new LinkedHashMap<>(loadUserNames(roots));
        for (Comment root : roots) {
            CommentNode rootNode = toNode(root, userNames);
            nodeIndex.put(root.id, rootNode);
            resultRoots.add(rootNode);
            rootIds.add(root.id);
        }
        loadReplies(post, rootIds, nodeIndex, userNames);

        return Response.ok(Map.of(
                "success", true,
                "data", resultRoots,
                "total", totalRoots,
                "page", safePage,
                "size", safeSize
        )).build();
    }

    /**
     * Breadth-first walk over the replies of the already selected roots. Bounding
     * depth and node count keeps a crafted thread from turning one list request
     * into an unbounded query.
     */
    private void loadReplies(Post post, Set<Long> rootIds, Map<Long, CommentNode> nodeIndex,
                             Map<Long, String> userNames) {
        Set<Long> frontier = new LinkedHashSet<>(rootIds);
        int depth = 0;
        while (!frontier.isEmpty() && depth < MAX_REPLY_DEPTH && nodeIndex.size() < MAX_COMMENT_NODES) {
            List<Comment> children = Comment.find(
                    "post = ?1 and status = ?2 and deletedAt is null and parent.id in ?3 order by createdAt asc",
                    post, STATUS_APPROVED, new ArrayList<>(frontier)).list();
            if (children.isEmpty()) {
                return;
            }
            userNames.putAll(loadUserNames(children));
            Set<Long> nextFrontier = new LinkedHashSet<>();
            for (Comment child : children) {
                CommentNode parentNode = child.parent == null ? null : nodeIndex.get(child.parent.id);
                // Orphans (parent pending or outside this page's roots) can never
                // appear in the response, so don't index or traverse them.
                if (parentNode == null || nodeIndex.containsKey(child.id)) {
                    continue;
                }
                CommentNode node = toNode(child, userNames);
                nodeIndex.put(child.id, node);
                parentNode.replies().add(node);
                nextFrontier.add(child.id);
                if (nodeIndex.size() >= MAX_COMMENT_NODES) {
                    return;
                }
            }
            frontier = nextFrontier;
            depth++;
        }
    }

    @POST
    public Response create(CommentCreateRequest request, @Context HttpHeaders headers,
                           @Context io.vertx.ext.web.RoutingContext context) {
        checkRate(context, "submit", 10);
        if (!settings.flag(SettingKey.FEATURE_TOGGLES, "enable_comment")) {
            return Response.status(Response.Status.FORBIDDEN)
                    .entity(Map.of("success", false, "message", "评论功能已关闭")).build();
        }
        if (request == null) {
            throw new BadRequestException("Request body is required");
        }

        User user = resolveUserFromCookie(headers);
        final String displayName = user == null ? normalizeGuestName(request.guestName()) : null;
        final String guestEmail = user == null ? normalizeGuestEmail(request.guestEmail()) : null;
        final String guestWebsite = user == null ? normalizeGuestWebsite(request.guestWebsite()) : null;
        final String userAgent = headers.getHeaderString("User-Agent");
        final String clientOs = com.biliwind.blog.common.helper.CommentClientHelper.operatingSystem(userAgent);
        final String clientBrowser = com.biliwind.blog.common.helper.CommentClientHelper.browser(userAgent);
        captchaService.verify(request.captchaId(), request.powNonce());

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
            comment.guestName = user == null ? displayName : null;
            comment.guestEmail = guestEmail;
            comment.guestWebsite = guestWebsite;
            comment.clientOs = clientOs;
            comment.clientBrowser = clientBrowser;
            comment.content = content;
            comment.status = STATUS_PENDING;

            // 触发 AI 审核状态预设
            if (settings.flag(SettingKey.AI_COMMENT_AUDIT, "autoAudit")) {
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

    private String normalizeGuestName(String value) {
        String name = value == null ? "" : value.trim();
        if (name.isBlank() || name.length() > 80) {
            throw new BadRequestException("请填写昵称（最多 80 个字符）");
        }
        return name;
    }

    private String normalizeGuestEmail(String value) {
        String email = value == null ? "" : value.trim();
        if (email.isBlank() || email.length() > 255
                || !email.matches("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")) {
            throw new BadRequestException("请填写有效的邮箱地址");
        }
        return email;
    }

    private String normalizeGuestWebsite(String value) {
        if (value == null || value.isBlank()) return null;
        String website = value.trim();
        try {
            java.net.URI uri = java.net.URI.create(website);
            if (website.length() > 2048 || uri.getHost() == null || uri.getUserInfo() != null
                    || !("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))) {
                throw new IllegalArgumentException();
            }
        } catch (IllegalArgumentException e) {
            throw new BadRequestException("站点地址必须是有效的 http:// 或 https:// 链接");
        }
        return website;
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

    /** Loads usernames for a batch of comments in one query instead of one per comment. */
    private Map<Long, String> loadUserNames(java.util.Collection<Comment> comments) {
        Set<Long> userIds = new LinkedHashSet<>();
        for (Comment comment : comments) {
            if (comment.user != null) {
                userIds.add(comment.user.id);
            }
        }
        if (userIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, String> names = new LinkedHashMap<>();
        for (User user : User.<User>find("id in ?1", userIds).list()) {
            names.put(user.id, user.username);
        }
        return names;
    }

    private CommentNode toNode(Comment comment, Map<Long, String> userNames) {
        Long userId = comment.user != null ? comment.user.id : null;
        String userName = userId == null ? resolveGuestName(comment) : userNames.get(userId);
        if (userName == null) {
            userName = comment.user != null ? comment.user.username : resolveGuestName(comment);
        }
        return new CommentNode(
                comment.id,
                comment.parent != null ? comment.parent.id : null,
                userId,
                userName,
                comment.guestWebsite,
                comment.clientOs,
                comment.clientBrowser,
                comment.content,
                CommentMarkdownHelper.toSafeHtml(comment.content),
                comment.createdAt,
                new ArrayList<>());
    }

    private String resolveGuestName(Comment comment) {
        return comment.guestName == null || comment.guestName.isBlank() ? "Guest" : comment.guestName;
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

        List<CommentQuote> quotes = CommentQuote.find(
                "select q from CommentQuote q join fetch q.comment c left join fetch c.user "
                        + "where q.post = ?1 and c.status = ?2 and c.deletedAt is null "
                        + "and (q.quoteType = 'QUOTE' or (q.quoteType = 'CORRECTION' and q.status = 0))",
                post, STATUS_APPROVED).list();

        List<Map<String, Object>> resultList = new ArrayList<>();
        for (CommentQuote q : quotes) {
            Map<String, Object> map = new HashMap<>();
            map.put("id", q.id);
            map.put("commentId", q.comment.id);
            map.put("userName", q.comment.user != null ? q.comment.user.username
                    : (q.comment.guestName == null ? "访客" : q.comment.guestName));
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

    @RegisterForReflection
    public record CommentCreateRequest(
            @NotBlank String postSlug,
            Long parentId,
            @NotBlank @Size(max = MAX_COMMENT_LENGTH) String content,
            String quoteType,
            String quoteText,
            String anchorDataJson,
            @Size(max = 80) String guestName,
            @Size(max = 255) String guestEmail,
            @Size(max = 2048) String guestWebsite,
            String captchaId,
            String powNonce) {
    }

    @RegisterForReflection
    public record CommentNode(
            Long id,
            Long parentId,
            Long userId,
            String userName,
            String guestWebsite,
            String clientOs,
            String clientBrowser,
            String content,
            String contentHtml,
            OffsetDateTime createdAt,
            List<CommentNode> replies) {
    }
}
