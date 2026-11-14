package com.biliwind.blog.model;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.OffsetDateTime;

/**
 * 用户资料变更审核记录实体，对应 user_profile_reviews
 */
@Entity
@Table(name = "user_profile_reviews")
public class UserProfileReview extends PanacheEntityBase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(name = "user_id", nullable = false)
    public Long userId;

    /** PENDING, APPROVED, REJECTED */
    @Column(nullable = false, length = 32)
    public String status = "PENDING";

    @Column(name = "old_nickname", length = 100)
    public String oldNickname;

    @Column(name = "new_nickname", length = 100)
    public String newNickname;

    @Column(name = "old_bio", columnDefinition = "TEXT")
    public String oldBio;

    @Column(name = "new_bio", columnDefinition = "TEXT")
    public String newBio;

    @Column(name = "old_avatar", length = 512)
    public String oldAvatar;

    @Column(name = "pending_avatar_path", length = 512)
    public String pendingAvatarPath;

    @Column(name = "pending_avatar_mime", length = 64)
    public String pendingAvatarMime;

    @Column(name = "pending_avatar_size")
    public Long pendingAvatarSize;

    /** NOT_RUN, PENDING, PASSED, FLAGGED, MANUAL_REQUIRED, FAILED */
    @Column(name = "ai_status", nullable = false, length = 32)
    public String aiStatus = "NOT_RUN";

    @Column(name = "ai_is_safe")
    public Boolean aiIsSafe;

    @Column(name = "ai_score")
    public Integer aiScore;

    @Column(name = "ai_reason", columnDefinition = "TEXT")
    public String aiReason;

    @Column(name = "ai_raw_response", columnDefinition = "TEXT")
    public String aiRawResponse;

    @Column(name = "review_note", columnDefinition = "TEXT")
    public String reviewNote;

    @Column(name = "reviewed_by")
    public Long reviewedBy;

    @Column(name = "reviewed_at")
    public OffsetDateTime reviewedAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    public OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    public OffsetDateTime updatedAt;
}
