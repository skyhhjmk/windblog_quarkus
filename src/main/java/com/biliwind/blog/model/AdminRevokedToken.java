package com.biliwind.blog.model;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;

@Entity
@Table(name = "admin_revoked_tokens", indexes = {
        @Index(name = "idx_admin_revoked_tokens_expires_at", columnList = "expires_at"),
        @Index(name = "idx_admin_revoked_tokens_user_id", columnList = "user_id")
})
public class AdminRevokedToken extends PanacheEntityBase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(name = "token_hash", nullable = false, unique = true, length = 64)
    public String tokenHash;

    @Column(name = "user_id")
    public Long userId;

    @Column(name = "expires_at", nullable = false)
    public OffsetDateTime expiresAt;

    @Column(name = "revoked_at", nullable = false)
    public OffsetDateTime revokedAt;

    @Column(length = 255)
    public String reason;
}
