package com.biliwind.blog.controller.api.admin;

import com.biliwind.blog.common.CacheService;
import com.biliwind.blog.controller.api.admin.dto.AdminCommentDtos.AdminCommentItem;
import com.biliwind.blog.controller.api.admin.dto.AdminCommentDtos.CommentBatchRequest;
import com.biliwind.blog.controller.api.admin.dto.AdminCommentDtos.CommentUpdateRequest;
import com.biliwind.blog.model.Comment;
import com.biliwind.blog.model.Post;
import com.biliwind.blog.model.User;
import com.biliwind.blog.service.AuditService;
import com.biliwind.blog.service.CommentEmailNotificationService;
import com.biliwind.blog.service.UserNotificationService;
import com.biliwind.blog.service.ai.AiTaskRuntimeTracker;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AdminCommentEmailNotificationOptOutTest {

    @Test
    void updateApproveSendsEmailByDefaultAndSkipsWhenOptedOut() {
        List<Long> emailedCommentIds = new ArrayList<>();
        List<Long> inAppNotifiedCommentIds = new ArrayList<>();
        Comment commentWithDefault = buildReplyComment(101L);
        Comment commentOptedOut = buildReplyComment(102L);
        AdminCommentController controller = buildController(
                Map.of(101L, commentWithDefault, 102L, commentOptedOut),
                emailedCommentIds,
                inAppNotifiedCommentIds);

        controller.update(101L, new CommentUpdateRequest((short) 1, null, null));
        assertEquals(List.of(101L), emailedCommentIds);
        assertEquals(List.of(101L), inAppNotifiedCommentIds);
        assertEquals((short) 1, commentWithDefault.status);

        controller.update(102L, new CommentUpdateRequest((short) 1, null, false));
        assertEquals(List.of(101L), emailedCommentIds);
        assertEquals(List.of(101L, 102L), inAppNotifiedCommentIds);
        assertEquals((short) 1, commentOptedOut.status);
    }

    @Test
    void batchApproveSkipsEmailWhenOptedOut() {
        List<Long> emailedCommentIds = new ArrayList<>();
        List<Long> inAppNotifiedCommentIds = new ArrayList<>();
        Comment comment1 = buildReplyComment(201L);
        Comment comment2 = buildReplyComment(202L);
        AdminCommentController controller = buildController(
                Map.of(201L, comment1, 202L, comment2),
                emailedCommentIds,
                inAppNotifiedCommentIds);

        controller.batch(new CommentBatchRequest(List.of(201L), "APPROVE", false));
        assertTrue(emailedCommentIds.isEmpty());
        assertEquals(List.of(201L), inAppNotifiedCommentIds);

        controller.batch(new CommentBatchRequest(List.of(202L), "APPROVE", true));
        assertEquals(List.of(202L), emailedCommentIds);
        assertEquals(List.of(201L, 202L), inAppNotifiedCommentIds);
    }

    private AdminCommentController buildController(
            Map<Long, Comment> commentsById,
            List<Long> emailedCommentIds,
            List<Long> inAppNotifiedCommentIds) {
        AdminCommentController controller = new AdminCommentController() {
            @Override
            AdminCommentItem toItem(Comment comment) {
                return null;
            }
        };
        controller.commentEmailNotificationService = new CommentEmailNotificationService() {
            @Override
            public void notifyReplyRecipient(Comment replyComment) {
                emailedCommentIds.add(replyComment.id);
            }
        };
        controller.userNotificationService = new UserNotificationService() {
            @Override
            public void notifyCommentReply(Comment comment) {
                inAppNotifiedCommentIds.add(comment.id);
            }
        };
        controller.auditService = new AuditService() {
            @Override
            public void log(String entityType, Object entityId, String action, Object oldValue, Object newValue) {
            }
        };
        controller.runtimeTracker = new AiTaskRuntimeTracker();
        controller.cacheService = new CacheService(null, null) {
            @Override
            public void delete(String key) {
            }
        };
        controller.entityManager = (EntityManager) Proxy.newProxyInstance(
                EntityManager.class.getClassLoader(),
                new Class<?>[]{EntityManager.class},
                (proxy, method, args) -> {
                    if ("find".equals(method.getName()) && args != null && args.length >= 2
                            && args[0] == Comment.class && args[1] instanceof Long id) {
                        if (args.length == 2 || args[2] == LockModeType.PESSIMISTIC_WRITE) {
                            return commentsById.get(id);
                        }
                    }
                    return null;
                });
        return controller;
    }

    private Comment buildReplyComment(long id) {
        Post post = new Post();
        post.id = 1L;
        post.slug = "test-post";
        post.title = Map.of("zh-CN", "测试文章");

        User parentUser = new User();
        parentUser.id = 10L;
        parentUser.username = "parent";
        parentUser.email = "parent@example.com";

        Comment parent = new Comment();
        parent.id = 99L;
        parent.user = parentUser;
        parent.post = post;

        User replyUser = new User();
        replyUser.id = 20L;
        replyUser.username = "replier";

        Comment comment = new Comment();
        comment.id = id;
        comment.post = post;
        comment.user = replyUser;
        comment.parent = parent;
        comment.content = "回复内容";
        comment.status = 0;
        comment.auditStatus = 0;
        return comment;
    }
}
