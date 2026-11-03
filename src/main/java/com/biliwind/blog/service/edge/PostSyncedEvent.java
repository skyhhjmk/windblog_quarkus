package com.biliwind.blog.service.edge;

public class PostSyncedEvent {
    private final Long postId;
    private final boolean remoteSync;

    public PostSyncedEvent(Long postId) {
        this(postId, false);
    }

    public PostSyncedEvent(Long postId, boolean remoteSync) {
        this.postId = postId;
        this.remoteSync = remoteSync;
    }

    public Long getPostId() {
        return postId;
    }

    public boolean isRemoteSync() {
        return remoteSync;
    }
}
