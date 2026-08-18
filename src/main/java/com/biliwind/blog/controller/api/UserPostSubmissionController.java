package com.biliwind.blog.controller.api;

import com.biliwind.blog.common.helper.PostHelper;
import com.biliwind.blog.common.helper.SlugHelper;
import com.biliwind.blog.common.security.UserTokenVerifier;
import com.biliwind.blog.model.Post;
import com.biliwind.blog.model.PostRevision;
import com.biliwind.blog.model.PostStatus;
import com.biliwind.blog.model.User;
import com.biliwind.blog.service.UserNotificationService;
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
import java.util.UUID;

/** 普通用户的投稿工作区：保存草稿，提交审核，等待管理员发布。 */
@Path("/user/api/posts")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "UserPostSubmission")
public class UserPostSubmissionController {

    @Inject
    UserTokenVerifier tokenVerifier;

    @Inject
    UserNotificationService notificationService;

    @GET
    @Transactional
    @Operation(summary = "查询当前用户的投稿")
    public List<PostItem> list(@Context HttpHeaders headers, @QueryParam("status") Short status) {
        User user = requireUser(headers);
        List<Post> posts;
        if (status == null) {
            posts = Post.list("user = ?1 and deletedAt is null order by updatedAt desc", user);
        } else {
            PostStatus postStatus = PostStatus.fromCode(status);
            if (postStatus == null) {
                throw new BadRequestException("未知投稿状态");
            }
            posts = Post.list("user = ?1 and status = ?2 and deletedAt is null order by updatedAt desc",
                    user, postStatus);
        }
        List<PostItem> result = new ArrayList<>();
        for (Post post : posts) {
            result.add(toItem(post));
        }
        return result;
    }

    @GET
    @Path("/{id}")
    @Transactional
    @Operation(summary = "查询投稿详情")
    public PostDetail detail(@PathParam("id") Long id, @Context HttpHeaders headers) {
        return toDetail(ownPost(id, requireUser(headers)));
    }

    @POST
    @Transactional
    @Operation(summary = "保存投稿草稿")
    public Response create(PostRequest request, @Context HttpHeaders headers) {
        User user = requireUser(headers);
        validateRequest(request);

        String slug = uniqueSlug(request.slug(), request.title());
        Post post = new Post();
        OffsetDateTime now = OffsetDateTime.now();
        post.slug = slug;
        post.title = titleMap(request.title());
        post.summary = optionalTextMap(request.summary());
        post.status = PostStatus.DRAFT;
        post.visibility = 0;
        post.renderType = com.biliwind.blog.model.PostRenderType.MARKDOWN;
        post.aiSummaryStatus = 2;
        post.user = user;
        post.createdAt = now;
        post.updatedAt = now;
        post.viewCount = 0L;
        post.featured = false;
        post.allowComment = true;
        post.version = 0;
        post.persist();

        PostRevision revision = newRevision(post, user, request);
        post.currentRevision = revision;
        post.persist();
        return Response.ok(toDetail(post)).build();
    }

    @PUT
    @Path("/{id}")
    @Transactional
    @Operation(summary = "更新投稿草稿")
    public Response update(@PathParam("id") Long id, PostRequest request, @Context HttpHeaders headers) {
        User user = requireUser(headers);
        validateRequest(request);
        Post post = ownPost(id, user);
        if (post.status != PostStatus.DRAFT) {
            throw new ClientErrorException("已提交审核或已发布的文章不能直接编辑", Response.Status.CONFLICT);
        }

        String requestedSlug = request.slug() == null || request.slug().isBlank()
                ? post.slug : SlugHelper.slugify(request.slug());
        if (requestedSlug.isBlank()) {
            requestedSlug = post.slug;
        }
        Post duplicate = Post.find("slug = ?1 and id <> ?2", requestedSlug, post.id).firstResult();
        if (duplicate != null) {
            throw new ClientErrorException("slug 已存在，请更换一个", Response.Status.CONFLICT);
        }

        post.slug = requestedSlug;
        post.title = titleMap(request.title());
        post.summary = optionalTextMap(request.summary());
        post.currentRevision = newRevision(post, user, request);
        post.reviewNote = null;
        post.reviewedAt = null;
        post.reviewedBy = null;
        post.updatedAt = OffsetDateTime.now();
        return Response.ok(toDetail(post)).build();
    }

    @POST
    @Path("/{id}/submit")
    @Transactional
    @Operation(summary = "提交投稿审核")
    public Response submit(@PathParam("id") Long id, @Context HttpHeaders headers) {
        User user = requireUser(headers);
        Post post = ownPost(id, user);
        if (post.status != PostStatus.DRAFT) {
            throw new ClientErrorException("当前文章不在可提交状态", Response.Status.CONFLICT);
        }
        if (post.currentRevision == null || !hasContent(post.currentRevision)) {
            throw new BadRequestException("请先填写标题和正文");
        }
        post.status = PostStatus.PENDING_REVIEW;
        post.submittedAt = OffsetDateTime.now();
        post.reviewedAt = null;
        post.reviewedBy = null;
        post.reviewNote = null;
        post.updatedAt = OffsetDateTime.now();
        notificationService.notifyPostSubmitted(post);
        return Response.ok(toDetail(post)).build();
    }

    private PostRevision newRevision(Post post, User user, PostRequest request) {
        PostRevision revision = new PostRevision();
        revision.post = post;
        revision.title = titleMap(request.title());
        revision.contentMarkdown = PostHelper.injectBlockIds(Map.of("zh-CN", request.contentMarkdown().trim()));
        revision.editorType = 0;
        PostRevision latest = PostRevision.find("post.id = ?1 order by revisionNumber desc", post.id).firstResult();
        revision.revisionNumber = latest == null ? 1 : latest.revisionNumber + 1;
        revision.createdBy = user;
        revision.createdAt = OffsetDateTime.now();
        revision.persist();
        return revision;
    }

    private Post ownPost(Long id, User user) {
        Post post = Post.find("id = ?1 and user = ?2 and deletedAt is null", id, user).firstResult();
        if (post == null) {
            throw new NotFoundException("投稿不存在");
        }
        return post;
    }

    private User requireUser(HttpHeaders headers) {
        Cookie cookie = headers.getCookies().get("user_token");
        if (cookie == null || cookie.getValue() == null || cookie.getValue().isBlank()) {
            throw new NotAuthorizedException("请先登录");
        }
        UserTokenVerifier.VerifiedToken verified = tokenVerifier.verify(cookie.getValue());
        if (verified == null) {
            throw new NotAuthorizedException("登录已过期");
        }
        User user = User.find("id = ?1 and status = 1 and deletedAt is null", verified.uid()).firstResult();
        if (user == null) {
            throw new NotAuthorizedException("用户不存在或已禁用");
        }
        return user;
    }

    private void validateRequest(PostRequest request) {
        if (request == null || request.title() == null || request.title().isBlank()) {
            throw new BadRequestException("标题不能为空");
        }
        if (request.title().trim().length() > 160) {
            throw new BadRequestException("标题不能超过 160 个字符");
        }
        if (request.contentMarkdown() == null || request.contentMarkdown().isBlank()) {
            throw new BadRequestException("正文不能为空");
        }
        if (request.contentMarkdown().length() > 1_000_000) {
            throw new BadRequestException("正文过长");
        }
    }

    private String uniqueSlug(String requested, String title) {
        String slug = requested == null || requested.isBlank() ? SlugHelper.slugify(title) : SlugHelper.slugify(requested);
        if (slug.isBlank()) {
            slug = "user-post-" + UUID.randomUUID().toString().substring(0, 8);
        }
        String base = slug;
        int suffix = 2;
        while (Post.count("slug", slug) > 0) {
            slug = base + "-" + suffix++;
        }
        return slug;
    }

    private Map<String, String> titleMap(String title) {
        return Map.of("zh-CN", title.trim());
    }

    private Map<String, String> optionalTextMap(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return Map.of("zh-CN", value.trim());
    }

    private boolean hasContent(PostRevision revision) {
        return revision.title != null && !revision.title.isEmpty()
                && revision.contentMarkdown != null
                && revision.contentMarkdown.values().stream().anyMatch(value -> value != null && !value.isBlank());
    }

    private PostItem toItem(Post post) {
        String title = localized(post.title);
        return new PostItem(post.id, post.slug, title, post.status.getCode(), post.status.name(),
                post.reviewNote, post.submittedAt, post.updatedAt);
    }

    private PostDetail toDetail(Post post) {
        String content = post.currentRevision == null ? ""
                : localized(post.currentRevision.contentMarkdown);
        return new PostDetail(post.id, post.slug, localized(post.title), localized(post.summary), content,
                post.status.getCode(), post.status.name(), post.reviewNote, post.submittedAt, post.updatedAt);
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

    public record PostRequest(String title, String slug, String summary, String contentMarkdown) {
    }

    public record PostItem(Long id, String slug, String title, short status, String statusName,
                           String reviewNote, OffsetDateTime submittedAt, OffsetDateTime updatedAt) {
    }

    public record PostDetail(Long id, String slug, String title, String summary, String contentMarkdown,
                             short status, String statusName, String reviewNote,
                             OffsetDateTime submittedAt, OffsetDateTime updatedAt) {
    }
}
