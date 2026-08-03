package com.biliwind.blog.service;

import com.biliwind.blog.model.BlogRegion;
import com.biliwind.blog.model.Media;
import com.biliwind.blog.model.Post;
import com.biliwind.blog.model.PostMedia;
import com.biliwind.blog.model.User;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@ApplicationScoped
public class MediaAccessPolicy {

    @Inject
    MediaAccessService mediaAccessService;

    @Inject
    PostAccessPolicy postAccessPolicy;

    @Inject
    PostAccessService postAccessService;

    public Decision authorizeDownload(Media media, Post post, Long userId, BlogRegion region) {
        User user = userId == null ? null : User.find(
                "id = ?1 and status = 1 and deletedAt is null", userId).firstResult();
        if (user == null) {
            return new Decision(false, "USER_NOT_ACTIVE");
        }
        if (media == null || media.deletedAt != null) {
            return new Decision(false, "MEDIA_NOT_FOUND");
        }
        if (post == null || post.deletedAt != null) {
            return new Decision(false, "POST_NOT_FOUND");
        }
        if (!postAccessPolicy.isVisibleInRegion(post, region)) {
            return new Decision(false, "POST_REGION_DENIED");
        }
        if (!mediaAccessService.canAccess(media, region)) {
            return new Decision(false, "REGION_DENIED");
        }
        PostMedia relation = PostMedia.find(
                "select relation from PostMedia relation join fetch relation.post "
                        + "where relation.media.id = ?1 and relation.post.id = ?2",
                media.id, post.id).firstResult();
        if (relation == null || !postAccessService.isProtectedMediaReference(relation)) {
            return new Decision(false, "MEDIA_NOT_ATTACHED");
        }
        if (!postAccessPolicy.isAuthor(post, userId) && !postAccessService.hasPurchasedPost(userId, post.id)) {
            return new Decision(false, "PURCHASE_REQUIRED");
        }
        return new Decision(true, "ALLOWED");
    }

    public record Decision(boolean allowed, String reason) {
    }
}
