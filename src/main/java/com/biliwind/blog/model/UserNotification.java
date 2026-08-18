package com.biliwind.blog.model;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.OffsetDateTime;

/** 站内通知，面向用户中心展示。 */
@Entity
@Table(name = "user_notifications")
public class UserNotification extends PanacheEntityBase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    public User user;

    @Column(nullable = false, length = 40)
    public String type;

    @Column(nullable = false, length = 200)
    public String title;

    @Column(nullable = false, columnDefinition = "text")
    public String body;

    @Column(name = "target_url", length = 512)
    public String targetUrl;

    @Column(name = "read_at")
    public OffsetDateTime readAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false)
    public OffsetDateTime createdAt;
}
