package com.biliwind.blog.service;

import com.biliwind.blog.common.helper.RegionVisibility;
import com.biliwind.blog.model.BlogRegion;
import com.biliwind.blog.model.Post;
import com.biliwind.blog.model.User;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@ApplicationScoped
public class PostAccessPolicy {

    @Inject
    PostAccessService postAccessService;

    @Inject
    ContentAccessTicketService ticketService;

    public Decision evaluate(Post post, Long userId, String submittedPassword, String ticketToken) {
        return evaluate(post, userId, submittedPassword, ticketToken, null);
    }

    public Decision evaluate(Post post, Long userId, String submittedPassword,
                             String ticketToken, String deviceId) {
        if (post == null || post.deletedAt != null) {
            return new Decision(false, "POST_NOT_FOUND");
        }
        Long activeUserId = resolveActiveUserId(userId);
        if (isAuthor(post, activeUserId)) {
            return new Decision(true, "AUTHOR");
        }
        if (postAccessService.hasPurchasedPost(activeUserId, post.id)) {
            return new Decision(true, "PURCHASED");
        }
        if (post.visibility == 0) {
            return new Decision(true, "PUBLIC");
        }
        if (post.visibility == 1) {
            return new Decision(false, "PRIVATE");
        }
        if (ticketService.isValid(ticketToken, "POST_PASSWORD", post.id, null, deviceId)) {
            return new Decision(true, "PASSWORD_TICKET");
        }
        if (postAccessService.verifyPassword(post, submittedPassword)) {
            return new Decision(true, "PASSWORD_SUBMISSION");
        }
        return new Decision(false, "PASSWORD_REQUIRED");
    }

    public boolean isAuthor(Post post, Long userId) {
        Long activeUserId = resolveActiveUserId(userId);
        return post != null && post.user != null && activeUserId != null && activeUserId.equals(post.user.id);
    }

    public boolean isVisibleInRegion(Post post, BlogRegion currentRegion) {
        return post != null && RegionVisibility.isVisible(post.visibilityRegions, currentRegion);
    }

    private Long resolveActiveUserId(Long userId) {
        if (userId == null) {
            return null;
        }
        User user = User.find("id = ?1 and status = 1 and deletedAt is null", userId).firstResult();
        return user == null ? null : user.id;
    }

    public record Decision(boolean allowed, String reason) {
    }
}
