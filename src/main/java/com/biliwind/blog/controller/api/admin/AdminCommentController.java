package com.biliwind.blog.controller.api.admin;

import com.biliwind.blog.common.security.SensitiveMessageSanitizer;
import com.biliwind.blog.context.AdminRequestContext;
import com.biliwind.blog.controller.api.admin.dto.AdminCommentDtos.AdminCommentItem;
import com.biliwind.blog.controller.api.admin.dto.AdminCommentDtos.AiAuditState;
import com.biliwind.blog.controller.api.admin.dto.AdminCommentDtos.CommentBatchRequest;
import com.biliwind.blog.controller.api.admin.dto.AdminCommentDtos.CommentBatchResult;
import com.biliwind.blog.controller.api.admin.dto.AdminCommentDtos.CommentUpdateRequest;
import com.biliwind.blog.controller.api.admin.dto.AdminUserDtos.PageResult;
import com.biliwind.blog.model.Comment;
import com.biliwind.blog.model.CommentQuote;
import com.biliwind.blog.model.OutboxEvent;
import com.biliwind.blog.service.AuditService;
import com.biliwind.blog.service.ai.AiManager;
import com.biliwind.blog.service.ai.AiRetryPolicy;
import com.biliwind.blog.service.ai.AiTaskRuntimeTracker;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import io.quarkus.panache.common.Page;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Path("/api/admin/comments")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Tag(name = "Admin - Comment", description = "评论管理")
public class AdminCommentController {

    private static final int MAX_BATCH_SIZE = 100;
    private static final int OUTBOX_MAX_ATTEMPTS = 8;

    @Inject
    com.biliwind.blog.service.CommentEmailNotificationService commentEmailNotificationService;

    @Inject
    com.biliwind.blog.service.UserNotificationService userNotificationService;

    @Inject
    AiManager aiManager;

    @Inject
    com.biliwind.blog.service.ReliableAiTaskService reliableAiTaskService;

    @Inject
    AiTaskRuntimeTracker runtimeTracker;

    @Inject
    AiRetryPolicy retryPolicy;

    @Inject
    AuditService auditService;

    @Inject
    AdminRequestContext adminRequestContext;

    @Inject
    EntityManager entityManager;

    @Inject
    com.biliwind.blog.common.CacheService cacheService;

    @ConfigProperty(name = "windblog.outbox.enabled", defaultValue = "true")
    boolean outboxEnabled;

    private void invalidateCommentCaches() {
        cacheService.delete(com.biliwind.blog.common.CacheService.Keys.SIDEBAR_STATS);
    }

    @GET
    @Transactional
    @Operation(summary = "评论列表")
    public PageResult<AdminCommentItem> list(
            @QueryParam("page") @DefaultValue("1") int page,
            @QueryParam("pageSize") @DefaultValue("20") int pageSize,
            @QueryParam("status") Short status,
            @QueryParam("auditStatus") Short auditStatus,
            @QueryParam("keyword") String keyword,
            @QueryParam("postId") Long postId) {

        int safePage = Math.max(1, page);
        int safePageSize = Math.max(1, Math.min(pageSize, 100));

        StringBuilder where = new StringBuilder("c.deletedAt is null");
        Map<String, Object> params = new HashMap<>();

        if (status != null) {
            where.append(" and c.status = :status");
            params.put("status", status);
        }

        if (auditStatus != null) {
            where.append(" and c.auditStatus = :auditStatus");
            params.put("auditStatus", auditStatus);
        }

        if (postId != null) {
            where.append(" and c.post.id = :postId");
            params.put("postId", postId);
        }

        if (keyword != null && !keyword.isBlank()) {
            where.append(" and (lower(c.content) like :keyword or lower(coalesce(c.guestName, '')) like :keyword or lower(coalesce(u.username, '')) like :keyword)");
            params.put("keyword", "%" + keyword.trim().toLowerCase(Locale.ROOT) + "%");
        }

        String queryStr = "from Comment c left join fetch c.post left join fetch c.user u where "
                + where + " order by c.createdAt desc";
        PanacheQuery<Comment> query = Comment.find(queryStr, params);

        Page pageObj = Page.of(safePage - 1, safePageSize);
        List<Comment> comments = query.page(pageObj).list();

        long totalCount = query.count();

        Map<Long, CommentQuote> quotes = loadQuotes(comments);
        Map<Long, OutboxEvent> latestOutbox = loadLatestAuditOutbox(comments);
        Map<Long, AiTaskRuntimeTracker.ActiveTaskSnapshot> activeAudits = runtimeTracker.snapshotActiveAudits();

        List<AdminCommentItem> items = new ArrayList<>(comments.size());
        for (Comment comment : comments) {
            items.add(toItem(
                    comment,
                    quotes.get(comment.id),
                    latestOutbox.get(comment.id),
                    activeAudits.get(comment.id)));
        }

        return new PageResult<>(items, totalCount, safePage, safePageSize);
    }

    @PUT
    @Path("/{id}")
    @Transactional
    @Operation(summary = "更新评论")
    public AdminCommentItem update(@PathParam("id") Long id, CommentUpdateRequest req) {
        Comment comment = entityManager.find(Comment.class, id, LockModeType.PESSIMISTIC_WRITE);
        if (comment == null || comment.deletedAt != null) {
            throw new NotFoundException();
        }

        Map<String, Object> oldVal = new HashMap<>();
        Map<String, Object> newVal = new HashMap<>();

        boolean shouldNotifyReplyRecipient = false;
        if (req.status() != null) {
            oldVal.put("status", comment.status);
            oldVal.put("auditStatus", comment.auditStatus);
            oldVal.put("isReviewing", comment.isReviewing);

            if (comment.isReviewing) {
                cancelPendingAuditOutbox(comment.id, "管理员已手动审核，取消排队中的 AI 审核任务");
                runtimeTracker.abortAudit(comment.id, "管理员已手动审核，中断 AI 审核");
                comment.isReviewing = false;
            }

            comment.status = req.status();
            if (comment.status == 1) {
                comment.auditStatus = Comment.AUDIT_STATUS_APPROVED;
                shouldNotifyReplyRecipient = oldVal.get("status") instanceof Short
                        && ((Short) oldVal.get("status")) != 1;
            } else {
                comment.auditStatus = Comment.AUDIT_STATUS_REJECTED;
            }

            newVal.put("status", comment.status);
            newVal.put("auditStatus", comment.auditStatus);
            newVal.put("isReviewing", comment.isReviewing);
        }

        if (req.content() != null && !req.content().isBlank()) {
            oldVal.put("content", comment.content);
            comment.content = req.content().trim();
            newVal.put("content", comment.content);
        }

        auditService.log("comment", String.valueOf(comment.id), "update", oldVal, newVal);
        if (shouldNotifyReplyRecipient) {
            if (!Boolean.FALSE.equals(req.sendEmailNotification())) {
                commentEmailNotificationService.notifyReplyRecipient(comment);
            }
            userNotificationService.notifyCommentReply(comment);
        }
        invalidateCommentCaches();

        return toItem(comment);
    }

    @DELETE
    @Path("/{id}")
    @Transactional
    @Operation(summary = "删除评论")
    public void delete(@PathParam("id") Long id) {
        Comment comment = entityManager.find(Comment.class, id, LockModeType.PESSIMISTIC_WRITE);
        if (comment != null && comment.deletedAt == null) {
            if (comment.isReviewing) {
                cancelPendingAuditOutbox(comment.id, "评论已删除，取消排队中的 AI 审核任务");
                runtimeTracker.abortAudit(comment.id, "评论已删除，中断 AI 审核");
                comment.isReviewing = false;
            }
            comment.deletedAt = OffsetDateTime.now();
            auditService.log("comment", String.valueOf(id), "delete", null, null);
            invalidateCommentCaches();
        }
    }

    @POST
    @Path("/{id}/audit")
    @Transactional
    @Operation(summary = "AI 审核评论")
    public AdminCommentItem audit(@PathParam("id") final Long id) {
        Comment comment = entityManager.find(Comment.class, id, LockModeType.PESSIMISTIC_WRITE);
        if (comment == null || comment.deletedAt != null) {
            throw new NotFoundException();
        }
        if (comment.isReviewing) {
            return toItem(comment);
        }

        enqueueAuditForComment(comment);
        return toItem(comment);
    }

    @POST
    @Path("/{id}/audit/abort")
    @Transactional
    @Operation(summary = "强制中断 AI 审核")
    public AdminCommentItem abortAudit(@PathParam("id") final Long id) {
        Comment comment = entityManager.find(Comment.class, id, LockModeType.PESSIMISTIC_WRITE);
        if (comment == null || comment.deletedAt != null) {
            throw new NotFoundException();
        }
        abortAuditForComment(comment);
        invalidateCommentCaches();
        return toItem(comment);
    }

    @POST
    @Path("/batch")
    @Transactional
    @Operation(summary = "批量操作评论")
    public CommentBatchResult batch(CommentBatchRequest req) {
        if (req == null || req.ids() == null || req.ids().isEmpty()) {
            throw new BadRequestException("请选择至少一条评论");
        }
        if (req.action() == null || req.action().isBlank()) {
            throw new BadRequestException("批量操作类型不能为空");
        }
        String action = req.action().trim().toUpperCase(Locale.ROOT);
        Set<Long> uniqueIds = new LinkedHashSet<>();
        for (Long id : req.ids()) {
            if (id != null && id > 0) {
                uniqueIds.add(id);
            }
        }
        if (uniqueIds.isEmpty()) {
            throw new BadRequestException("没有有效的评论 ID");
        }
        if (uniqueIds.size() > MAX_BATCH_SIZE) {
            throw new BadRequestException("单次批量操作最多支持 " + MAX_BATCH_SIZE + " 条评论");
        }

        int succeeded = 0;
        int skipped = 0;
        List<Long> failedIds = new ArrayList<>();

        for (Long id : uniqueIds) {
            Comment comment = entityManager.find(Comment.class, id, LockModeType.PESSIMISTIC_WRITE);
            if (comment == null || comment.deletedAt != null) {
                failedIds.add(id);
                continue;
            }
            switch (action) {
                case "APPROVE" -> {
                    boolean notify = comment.status != 1;
                    if (comment.isReviewing) {
                        cancelPendingAuditOutbox(comment.id, "管理员批量通过，取消排队中的 AI 审核任务");
                        runtimeTracker.abortAudit(comment.id, "管理员批量通过，中断 AI 审核");
                        comment.isReviewing = false;
                    }
                    short oldStatus = comment.status;
                    short oldAudit = comment.auditStatus;
                    comment.status = 1;
                    comment.auditStatus = Comment.AUDIT_STATUS_APPROVED;
                    auditService.log("comment", String.valueOf(comment.id), "batch_approve",
                            Map.of("status", oldStatus, "auditStatus", oldAudit),
                            Map.of("status", comment.status, "auditStatus", comment.auditStatus));
                    if (notify) {
                        if (!Boolean.FALSE.equals(req.sendEmailNotification())) {
                            commentEmailNotificationService.notifyReplyRecipient(comment);
                        }
                        userNotificationService.notifyCommentReply(comment);
                    }
                    succeeded++;
                }
                case "REJECT" -> {
                    if (comment.isReviewing) {
                        cancelPendingAuditOutbox(comment.id, "管理员批量驳回，取消排队中的 AI 审核任务");
                        runtimeTracker.abortAudit(comment.id, "管理员批量驳回，中断 AI 审核");
                        comment.isReviewing = false;
                    }
                    short oldStatus = comment.status;
                    short oldAudit = comment.auditStatus;
                    comment.status = 2;
                    comment.auditStatus = Comment.AUDIT_STATUS_REJECTED;
                    auditService.log("comment", String.valueOf(comment.id), "batch_reject",
                            Map.of("status", oldStatus, "auditStatus", oldAudit),
                            Map.of("status", comment.status, "auditStatus", comment.auditStatus));
                    succeeded++;
                }
                case "DELETE" -> {
                    if (comment.isReviewing) {
                        cancelPendingAuditOutbox(comment.id, "管理员批量删除，取消排队中的 AI 审核任务");
                        runtimeTracker.abortAudit(comment.id, "管理员批量删除，中断 AI 审核");
                        comment.isReviewing = false;
                    }
                    comment.deletedAt = OffsetDateTime.now();
                    auditService.log("comment", String.valueOf(comment.id), "batch_delete", null, null);
                    succeeded++;
                }
                case "AUDIT" -> {
                    if (comment.isReviewing) {
                        skipped++;
                    } else {
                        enqueueAuditForComment(comment);
                        succeeded++;
                    }
                }
                case "ABORT_AUDIT" -> {
                    boolean hasActive = runtimeTracker.getActiveAudit(comment.id) != null;
                    if (!comment.isReviewing && !hasActive) {
                        skipped++;
                    } else {
                        abortAuditForComment(comment);
                        succeeded++;
                    }
                }
                default -> throw new BadRequestException("不支持的批量操作: " + action);
            }
        }

        if (succeeded > 0) {
            invalidateCommentCaches();
        }

        String summary = String.format("批量操作完成：成功 %d 条，跳过 %d 条，失败 %d 条",
                succeeded, skipped, failedIds.size());
        return new CommentBatchResult(uniqueIds.size(), succeeded, skipped, failedIds.size(), failedIds, summary);
    }

    private void enqueueAuditForComment(Comment comment) {
        comment.isReviewing = true;
        comment.auditStatus = Comment.AUDIT_STATUS_REVIEWING;
        comment.aiReviewData = null;
        CommentQuote quote = CommentQuote.find(
                "comment.id = ?1 order by id desc", comment.id).firstResult();
        String auditContent = com.biliwind.blog.service.ai.CommentModerationPrompt.buildModerationInput(
                comment.content,
                quote != null ? quote.quoteType : null,
                quote != null ? quote.quoteText : null);
        reliableAiTaskService.enqueueAudit(
                new com.biliwind.blog.service.ai.AiAuditTask(comment.id, auditContent),
                UUID.randomUUID().toString());
    }

    private void abortAuditForComment(Comment comment) {
        int cancelledOutbox = cancelPendingAuditOutbox(comment.id, "已被管理员强制中断 AI 审核");
        boolean interruptedActive = runtimeTracker.abortAudit(comment.id, "已被管理员强制中断 AI 审核");

        Map<String, Object> oldVal = Map.of(
                "isReviewing", comment.isReviewing,
                "auditStatus", comment.auditStatus);

        comment.isReviewing = false;
        comment.auditStatus = Comment.AUDIT_STATUS_FAILED;
        Map<String, Object> abortData = new HashMap<>();
        abortData.put("reviewRequired", true);
        abortData.put("aborted", true);
        abortData.put("failed", false);
        abortData.put("reason", "已被管理员强制中断 AI 审核");
        abortData.put("abortedAt", System.currentTimeMillis());
        abortData.put("interruptedActiveTask", interruptedActive);
        abortData.put("cancelledOutboxCount", cancelledOutbox);
        comment.aiReviewData = abortData;

        auditService.log(
                "comment",
                String.valueOf(comment.id),
                "ai_moderation_aborted",
                oldVal,
                Map.of(
                        "isReviewing", false,
                        "auditStatus", comment.auditStatus,
                        "interruptedActiveTask", interruptedActive,
                        "cancelledOutboxCount", cancelledOutbox));
    }

    private int cancelPendingAuditOutbox(Long commentId, String reason) {
        return entityManager.createQuery(
                        "update OutboxEvent e set e.status = 'FAILED', e.lockedUntil = null, "
                                + "e.lockOwner = null, e.lastError = :reason "
                                + "where e.eventType = 'AI_AUDIT' and e.aggregateType = 'COMMENT' "
                                + "and e.aggregateId = :aggregateId and e.status in ('PENDING', 'IN_FLIGHT')")
                .setParameter("reason", reason)
                .setParameter("aggregateId", String.valueOf(commentId))
                .executeUpdate();
    }

    AdminCommentItem toItem(Comment comment) {
        CommentQuote quote = CommentQuote.find(
                "comment.id = ?1 order by id desc", comment.id).firstResult();
        OutboxEvent outbox = OutboxEvent.find(
                "eventType = 'AI_AUDIT' and aggregateType = 'COMMENT' and aggregateId = ?1 order by id desc",
                String.valueOf(comment.id)).firstResult();
        AiTaskRuntimeTracker.ActiveTaskSnapshot active = runtimeTracker.getActiveAudit(comment.id);
        return toItem(comment, quote, outbox, active);
    }

    private Map<Long, CommentQuote> loadQuotes(List<Comment> comments) {
        if (comments.isEmpty()) {
            return Map.of();
        }
        List<Long> commentIds = new ArrayList<>(comments.size());
        for (Comment comment : comments) {
            if (comment.id != null) {
                commentIds.add(comment.id);
            }
        }
        if (commentIds.isEmpty()) {
            return Map.of();
        }
        List<CommentQuote> quotes = CommentQuote.find(
                "comment.id in ?1 order by id desc", commentIds).list();
        Map<Long, CommentQuote> byCommentId = new HashMap<>();
        for (CommentQuote quote : quotes) {
            if (quote.comment != null) {
                byCommentId.putIfAbsent(quote.comment.id, quote);
            }
        }
        return byCommentId;
    }

    private Map<Long, OutboxEvent> loadLatestAuditOutbox(List<Comment> comments) {
        if (comments.isEmpty()) {
            return Map.of();
        }
        List<String> aggregateIds = new ArrayList<>(comments.size());
        for (Comment comment : comments) {
            if (comment.id != null) {
                aggregateIds.add(String.valueOf(comment.id));
            }
        }
        if (aggregateIds.isEmpty()) {
            return Map.of();
        }
        List<OutboxEvent> events = OutboxEvent.find(
                "eventType = 'AI_AUDIT' and aggregateType = 'COMMENT' and aggregateId in ?1 order by id desc",
                aggregateIds).list();
        Map<Long, OutboxEvent> byCommentId = new HashMap<>();
        for (OutboxEvent event : events) {
            try {
                Long commentId = Long.valueOf(event.aggregateId);
                byCommentId.putIfAbsent(commentId, event);
            } catch (NumberFormatException ignored) {
                // ignore malformed aggregate id
            }
        }
        return byCommentId;
    }

    private AiAuditState buildAuditState(
            Comment comment,
            OutboxEvent outbox,
            AiTaskRuntimeTracker.ActiveTaskSnapshot active) {
        OffsetDateTime now = OffsetDateTime.now();
        int maxMqRetries = retryPolicy.maxAttempts();

        if (active != null) {
            String detail = active.stageDetail();
            boolean stuck = active.elapsedSeconds() > 90;
            if (stuck && !active.aborting()) {
                detail = detail + String.format("（已持续 %d 秒，响应偏慢，可强制中断）", active.elapsedSeconds());
            }
            return new AiAuditState(
                    active.aborting() ? "ABORTING" : "EXECUTING",
                    active.stageLabel(),
                    detail,
                    stuck,
                    true,
                    active.retryCount() + 1,
                    active.maxRetries() + 1,
                    active.elapsedSeconds(),
                    null,
                    active.stageUpdatedAt(),
                    null);
        }

        if (comment.isReviewing) {
            if (!outboxEnabled) {
                return new AiAuditState(
                        "STUCK",
                        "Outbox 调度已关闭",
                        "系统配置 windblog.outbox.enabled=false，审核任务不会被投递，请强制中断或启用 Outbox",
                        true,
                        true,
                        0,
                        OUTBOX_MAX_ATTEMPTS,
                        secondsSince(comment.updatedAt, now),
                        "Outbox disabled",
                        comment.updatedAt,
                        null);
            }

            if (outbox == null) {
                long elapsed = secondsSince(comment.updatedAt, now);
                return new AiAuditState(
                        "STUCK",
                        "状态卡死（无任务记录）",
                        "评论标记为审核中，但未找到 Outbox 审核任务（可能是历史残留或异常中断），请点击强制中断",
                        true,
                        true,
                        0,
                        OUTBOX_MAX_ATTEMPTS,
                        elapsed,
                        null,
                        comment.updatedAt,
                        null);
            }

            String safeLastError = SensitiveMessageSanitizer.sanitize(outbox.lastError);
            if ("IN_FLIGHT".equals(outbox.status)) {
                long elapsed = secondsSince(outbox.createdAt, now);
                boolean stuck = elapsed > 30;
                return new AiAuditState(
                        "OUTBOX_IN_FLIGHT",
                        "正在投递到 RabbitMQ",
                        String.format("Outbox 正在将任务发布到 ai-audit-tasks（第 %d/%d 次尝试）",
                                Math.max(1, outbox.attemptCount), OUTBOX_MAX_ATTEMPTS),
                        stuck,
                        true,
                        outbox.attemptCount,
                        OUTBOX_MAX_ATTEMPTS,
                        elapsed,
                        safeLastError,
                        outbox.createdAt,
                        null);
            }

            if ("PENDING".equals(outbox.status)) {
                long elapsed = secondsSince(outbox.createdAt, now);
                if (outbox.attemptCount > 0) {
                    long waitSeconds = outbox.availableAt != null && outbox.availableAt.isAfter(now)
                            ? Duration.between(now, outbox.availableAt).getSeconds()
                            : 0L;
                    String detail = waitSeconds > 0
                            ? String.format("投递 RabbitMQ 失败（第 %d/%d 次），将在 %d 秒后重试%s",
                            outbox.attemptCount, OUTBOX_MAX_ATTEMPTS, waitSeconds,
                            safeLastError != null && !safeLastError.isBlank() ? "：" + safeLastError : "")
                            : String.format("投递 RabbitMQ 失败（第 %d/%d 次），等待调度器重试%s",
                            outbox.attemptCount, OUTBOX_MAX_ATTEMPTS,
                            safeLastError != null && !safeLastError.isBlank() ? "：" + safeLastError : "");
                    return new AiAuditState(
                            "OUTBOX_RETRYING",
                            "卡在 Outbox 投递重试",
                            detail,
                            true,
                            true,
                            outbox.attemptCount,
                            OUTBOX_MAX_ATTEMPTS,
                            elapsed,
                            safeLastError,
                            outbox.createdAt,
                            outbox.availableAt);
                }

                boolean stuck = elapsed > 20;
                String detail = stuck
                        ? String.format("已写入 Outbox 超过 %d 秒仍未被调度，请检查 Outbox 定时任务或数据库连接", elapsed)
                        : String.format("已写入 Outbox（%d 秒前），等待调度器投递到 RabbitMQ（每 5 秒轮询）", elapsed);
                return new AiAuditState(
                        "OUTBOX_PENDING",
                        stuck ? "卡在 Outbox 待调度" : "等待 Outbox 调度",
                        detail,
                        stuck,
                        true,
                        outbox.attemptCount,
                        OUTBOX_MAX_ATTEMPTS,
                        elapsed,
                        safeLastError,
                        outbox.createdAt,
                        outbox.availableAt);
            }

            if ("PUBLISHED".equals(outbox.status)) {
                OffsetDateTime refTime = outbox.publishedAt != null ? outbox.publishedAt : outbox.createdAt;
                long elapsed = secondsSince(refTime, now);
                boolean suspectedStuck = elapsed > 120;
                if (suspectedStuck) {
                    return new AiAuditState(
                            "STUCK",
                            "疑似卡死（队列未消费/任务丢失）",
                            String.format("已于 %d 秒前投递到 RabbitMQ，但当前无活跃消费者处理此评论。可能是消费者离线、队列积压或应用重启导致任务丢失，可强制中断后重新审核。", elapsed),
                            true,
                            true,
                            outbox.attemptCount,
                            maxMqRetries + 1,
                            elapsed,
                            safeLastError,
                            refTime,
                            null);
                }
                return new AiAuditState(
                        "MQ_QUEUED",
                        elapsed > 45 ? "RabbitMQ 排队较久" : "RabbitMQ 排队中",
                        String.format("已投递到 ai-audit-tasks 队列（%d 秒前），等待 AI 审核消费者领取执行", elapsed),
                        elapsed > 45,
                        true,
                        outbox.attemptCount,
                        maxMqRetries + 1,
                        elapsed,
                        safeLastError,
                        refTime,
                        null);
            }

            return new AiAuditState(
                    "STUCK",
                    "审核任务已失败（状态未释放）",
                    "Outbox 任务状态为 " + outbox.status
                            + (safeLastError != null && !safeLastError.isBlank() ? "：" + safeLastError : "")
                            + "，请强制中断以释放审核中状态",
                    true,
                    true,
                    outbox.attemptCount,
                    OUTBOX_MAX_ATTEMPTS,
                    secondsSince(outbox.createdAt, now),
                    safeLastError,
                    outbox.createdAt,
                    null);
        }

        // Not currently reviewing
        if (comment.aiReviewData instanceof Map<?, ?> map) {
            if (Boolean.TRUE.equals(map.get("aborted"))) {
                String reason = map.get("reason") != null ? String.valueOf(map.get("reason")) : "已被管理员强制中断 AI 审核";
                return new AiAuditState(
                        "ABORTED",
                        "已强制中断",
                        reason,
                        false,
                        false,
                        0,
                        0,
                        null,
                        null,
                        comment.updatedAt,
                        null);
            }
            if (Boolean.TRUE.equals(map.get("failed")) || comment.auditStatus == Comment.AUDIT_STATUS_FAILED) {
                String error = map.get("error") != null ? SensitiveMessageSanitizer.sanitize(String.valueOf(map.get("error"))) : null;
                String reason = map.get("reason") != null ? String.valueOf(map.get("reason")) : "AI 审核任务失败";
                return new AiAuditState(
                        "FAILED",
                        "AI 审核失败",
                        error != null && !error.isBlank() ? reason + "：" + error : reason,
                        false,
                        false,
                        0,
                        0,
                        null,
                        error,
                        comment.updatedAt,
                        null);
            }
            Object isSafe = map.get("isSafe");
            String reason = map.get("reason") != null ? String.valueOf(map.get("reason")) : null;
            if (isSafe instanceof Boolean safe) {
                return new AiAuditState(
                        "COMPLETED",
                        safe ? "AI 建议通过" : "AI 建议驳回",
                        reason,
                        false,
                        false,
                        0,
                        0,
                        null,
                        null,
                        comment.updatedAt,
                        null);
            }
        }

        if (comment.auditStatus == Comment.AUDIT_STATUS_FAILED) {
            return new AiAuditState(
                    "FAILED",
                    "AI 审核失败",
                    "上次 AI 审核失败，可重新发起审核",
                    false,
                    false,
                    0,
                    0,
                    null,
                    null,
                    comment.updatedAt,
                    null);
        }

        return new AiAuditState(
                "IDLE",
                "未审核",
                "尚未发起 AI 审核",
                false,
                false,
                0,
                0,
                null,
                null,
                comment.updatedAt,
                null);
    }

    private long secondsSince(OffsetDateTime from, OffsetDateTime now) {
        if (from == null) {
            return 0L;
        }
        return Math.max(0L, Duration.between(from, now).getSeconds());
    }

    private AdminCommentItem toItem(
            Comment comment,
            CommentQuote quote,
            OutboxEvent latestOutbox,
            AiTaskRuntimeTracker.ActiveTaskSnapshot activeAudit) {
        String displayTitle = "Deleted Post";
        if (comment.post != null) {
            try {
                String postTitle = resolveLocalizedTitle(comment.post.title);
                if (postTitle != null && !postTitle.isBlank()) {
                    displayTitle = postTitle;
                } else {
                    displayTitle = comment.post.slug;
                }
            } catch (jakarta.persistence.EntityNotFoundException e) {
                displayTitle = "Deleted Post (ID: " + comment.post.id + ")";
            }
        }

        String userName = comment.user != null ? comment.user.username
                : (comment.guestName == null || comment.guestName.isBlank() ? "Guest" : comment.guestName);

        Long parentId = comment.parent != null ? comment.parent.id : null;
        AiAuditState auditState = buildAuditState(comment, latestOutbox, activeAudit);

        return new AdminCommentItem(
                comment.id,
                comment.post != null ? comment.post.id : null,
                displayTitle,
                comment.user != null ? comment.user.id : null,
                userName,
                comment.guestEmail,
                comment.content,
                parentId,
                comment.status,
                comment.auditStatus,
                comment.aiReviewData,
                comment.isReviewing,
                auditState,
                comment.createdAt,
                quote != null ? quote.id : null,
                quote != null ? quote.quoteType : null,
                quote != null ? quote.quoteText : null,
                quote != null ? quote.anchorData : null,
                quote != null ? quote.status : null);
    }

    private String resolveLocalizedTitle(Map<String, String> titles) {
        if (titles == null || titles.isEmpty()) {
            return null;
        }
        if (titles.containsKey("zh-CN")) {
            return titles.get("zh-CN");
        }
        if (titles.containsKey("zh-cn")) {
            return titles.get("zh-cn");
        }
        for (String value : titles.values()) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    @PUT
    @Path("/quotes/{quoteId}/status")
    @Transactional
    @Operation(summary = "处理纠错状态")
    public void updateQuoteStatus(@PathParam("quoteId") Long quoteId, @QueryParam("status") short status) {
        CommentQuote quote = CommentQuote.findById(quoteId);
        if (quote == null) {
            throw new NotFoundException("Quote not found");
        }

        Map<String, Object> oldVal = new HashMap<>();
        oldVal.put("status", quote.status);

        quote.status = status;
        quote.updatedAt = OffsetDateTime.now();

        Map<String, Object> newVal = new HashMap<>();
        newVal.put("status", quote.status);

        auditService.log("comment_quote", String.valueOf(quote.id), "update_status", oldVal, newVal);
        invalidateCommentCaches();
    }
}
