package com.biliwind.blog.model;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.OffsetDateTime;

@Entity
@Table(name = "content_access_ticket")
public class ContentAccessTicket extends PanacheEntityBase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(name = "token_hash", nullable = false, unique = true, length = 128)
    public String tokenHash;

    @Column(name = "subject_type", nullable = false, length = 32)
    public String subjectType;

    @Column(name = "subject_id")
    public Long subjectId;

    @Column(name = "post_id")
    public Long postId;

    @Column(nullable = false, length = 64)
    public String scope;

    @Column(name = "expires_at", nullable = false)
    public OffsetDateTime expiresAt;

    @Column(name = "revoked_at")
    public OffsetDateTime revokedAt;

    @Column(name = "key_version", nullable = false)
    public Integer keyVersion = 1;

    @Column(name = "device_hash", length = 128)
    public String deviceHash;

    @Column(name = "last_used_at")
    public OffsetDateTime lastUsedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    @CreationTimestamp
    public OffsetDateTime createdAt;
}
