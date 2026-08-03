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
@Table(name = "email_deliveries")
public class EmailDelivery extends PanacheEntityBase {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;
    @Column(nullable = false)
    public String scenario;
    @Column(name = "recipient_address", nullable = false)
    public String recipientAddress;
    @Column(nullable = false)
    public String subject;
    @Column(name = "html_content", nullable = false)
    public String htmlContent;
    @Column(name = "channel_id")
    public Long channelId;
    @Column(name = "channel_group_id")
    public Long channelGroupId;
    @Column(nullable = false)
    public String status;
    @Column(name = "attempt_count", nullable = false)
    public int attemptCount;
    @Column(name = "next_attempt_at", nullable = false)
    public OffsetDateTime nextAttemptAt;
    @Column(name = "last_error")
    public String lastError;
    @Column(name = "created_at")
    public OffsetDateTime createdAt;
    @Column(name = "sent_at")
    public OffsetDateTime sentAt;
    @Column(name = "locked_until")
    public OffsetDateTime lockedUntil;
    @Column(name = "lock_owner", length = 64)
    public String lockOwner;
}
