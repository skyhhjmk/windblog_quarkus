package com.biliwind.blog.controller.api.admin;

import com.biliwind.blog.context.AdminRequestContext;
import com.biliwind.blog.model.User;
import com.biliwind.blog.model.UserProfileReview;
import com.biliwind.blog.service.UserProfileReviewService;
import io.quarkus.panache.common.Page;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Path("/api/admin/user-profile-reviews")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "AdminUserProfileReview")
public class AdminUserProfileReviewController {

    @Inject
    UserProfileReviewService reviewService;

    @Inject
    AdminRequestContext adminRequestContext;

    public record ReviewDecisionRequest(String reason, String note) {
    }

    public record BatchDecisionRequest(List<Long> ids, String action, String reason) {
    }

    @GET
    @Operation(summary = "聚合查询用户资料变更审核列表")
    public Map<String, Object> list(
            @QueryParam("status") @DefaultValue("PENDING") String status,
            @QueryParam("keyword") String keyword,
            @QueryParam("page") @DefaultValue("1") int page,
            @QueryParam("pageSize") @DefaultValue("20") int pageSize) {

        StringBuilder where = new StringBuilder("1 = 1");
        Map<String, Object> params = new HashMap<>();

        if (status != null && !status.isBlank() && !"ALL".equalsIgnoreCase(status)) {
            where.append(" and status = :status");
            params.put("status", status.trim().toUpperCase());
        }

        if (keyword != null && !keyword.isBlank()) {
            String like = "%" + keyword.trim().toLowerCase() + "%";
            List<Long> matchedUserIds = User.<User>find(
                            "deletedAt is null and (lower(username) like ?1 or lower(nickname) like ?1 or lower(email) like ?1)",
                            like)
                    .list()
                    .stream()
                    .map(u -> u.id)
                    .toList();
            if (matchedUserIds.isEmpty()) {
                where.append(" and (lower(coalesce(newNickname, '')) like :kw or lower(coalesce(newBio, '')) like :kw)");
                params.put("kw", like);
            } else {
                where.append(" and (userId in (:userIds) or lower(coalesce(newNickname, '')) like :kw or lower(coalesce(newBio, '')) like :kw)");
                params.put("userIds", matchedUserIds);
                params.put("kw", like);
            }
        }

        var query = UserProfileReview.find(where + " order by createdAt desc", params);
        int safePage = Math.max(page, 1);
        int safeSize = Math.min(Math.max(pageSize, 1), 100);
        long total = query.count();
        List<UserProfileReview> reviews = query.page(Page.of(safePage - 1, safeSize)).list();

        List<Map<String, Object>> items = reviews.stream().map(this::toDto).toList();
        return Map.of(
                "items", items,
                "total", total,
                "page", safePage,
                "pageSize", safeSize
        );
    }

    @GET
    @Path("/summary")
    @Operation(summary = "获取待审核资料数量汇总")
    public Map<String, Object> summary() {
        long pending = UserProfileReview.count("status = 'PENDING'");
        long manualRequired = UserProfileReview.count("status = 'PENDING' and aiStatus = 'MANUAL_REQUIRED'");
        long flagged = UserProfileReview.count("status = 'PENDING' and aiStatus = 'FLAGGED'");
        return Map.of(
                "pending", pending,
                "manualRequired", manualRequired,
                "flagged", flagged
        );
    }

    @GET
    @Path("/{id}/pending-avatar")
    @Produces(MediaType.WILDCARD)
    @Operation(summary = "预览待审核头像")
    public Response pendingAvatar(@PathParam("id") Long id) {
        UserProfileReviewService.AvatarPayload payload = reviewService.loadPendingAvatarByReviewId(id);
        if (payload == null) {
            throw new NotFoundException("待审头像不存在");
        }
        return Response.ok(payload.bytes(), payload.mimeType())
                .header("Cache-Control", "no-store")
                .build();
    }

    @POST
    @Path("/{id}/approve")
    @Operation(summary = "审核通过用户资料变更")
    public Map<String, Object> approve(@PathParam("id") Long id, ReviewDecisionRequest body) {
        Long adminId = adminRequestContext.getUserId();
        UserProfileReview updated = reviewService.approve(id, adminId, body == null ? null : body.note());
        return Map.of("success", true, "item", toDto(updated));
    }

    @POST
    @Path("/{id}/reject")
    @Operation(summary = "驳回用户资料变更")
    public Map<String, Object> reject(@PathParam("id") Long id, ReviewDecisionRequest body) {
        Long adminId = adminRequestContext.getUserId();
        String reason = body != null && body.reason() != null && !body.reason().isBlank()
                ? body.reason()
                : (body != null ? body.note() : null);
        UserProfileReview updated = reviewService.reject(id, adminId, reason);
        return Map.of("success", true, "item", toDto(updated));
    }

    @POST
    @Path("/{id}/ai-audit")
    @Operation(summary = "重新触发 AI 审核")
    public Map<String, Object> aiAudit(@PathParam("id") Long id) {
        UserProfileReview updated = reviewService.runAiAudit(id);
        return Map.of("success", true, "item", toDto(updated));
    }

    @POST
    @Path("/batch")
    @Operation(summary = "批量审批用户资料变更")
    public Map<String, Object> batch(BatchDecisionRequest body) {
        if (body == null || body.ids() == null || body.ids().isEmpty()
                || body.action() == null || body.action().isBlank()) {
            throw new BadRequestException("请提供有效的批量操作参数");
        }
        Long adminId = adminRequestContext.getUserId();
        int processed = reviewService.batchDecision(body.ids(), body.action(), adminId, body.reason());
        return Map.of("success", true, "processed", processed);
    }

    private Map<String, Object> toDto(UserProfileReview review) {
        User user = User.findById(review.userId);
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", review.id);
        map.put("userId", review.userId);
        map.put("username", user != null ? user.username : ("#" + review.userId));
        map.put("email", user != null ? user.email : null);
        map.put("status", review.status);
        map.put("oldNickname", review.oldNickname);
        map.put("newNickname", review.newNickname);
        map.put("oldBio", review.oldBio);
        map.put("newBio", review.newBio);
        map.put("oldAvatar", review.oldAvatar);
        map.put("hasPendingAvatar", review.pendingAvatarPath != null && !review.pendingAvatarPath.isBlank());
        map.put("pendingAvatarMime", review.pendingAvatarMime);
        map.put("pendingAvatarSize", review.pendingAvatarSize);
        map.put("aiStatus", review.aiStatus);
        map.put("aiIsSafe", review.aiIsSafe);
        map.put("aiScore", review.aiScore);
        map.put("aiReason", review.aiReason);
        map.put("reviewNote", review.reviewNote);
        map.put("reviewedBy", review.reviewedBy);
        map.put("reviewedAt", review.reviewedAt);
        map.put("createdAt", review.createdAt);
        map.put("updatedAt", review.updatedAt);
        return map;
    }
}
