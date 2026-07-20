package com.biliwind.blog.model;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;

@Entity
@Table(name = "email_verification_tokens")
public class EmailVerificationToken extends PanacheEntityBase {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;
    @Column(name = "user_id", nullable = false)
    public Long userId;
    @Column(name = "token_hash", nullable = false)
    public String tokenHash;
    @Column(name = "expires_at", nullable = false)
    public OffsetDateTime expiresAt;
    @Column(name = "consumed_at")
    public OffsetDateTime consumedAt;
}
