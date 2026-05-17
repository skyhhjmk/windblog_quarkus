package com.biliwind.blog.service.edge;

public class PostSyncedEvent {
    private final Long postId;

    public PostSyncedEvent(Long postId) {
        this.postId = postId;
    }

    public Long getPostId() {
        return postId;
    }
}
