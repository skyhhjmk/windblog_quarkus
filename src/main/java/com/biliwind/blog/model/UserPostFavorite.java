package com.biliwind.blog.model;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.OffsetDateTime;

/** 用户收藏的公开文章。 */
@Entity
@Table(name = "user_post_favorites",
        uniqueConstraints = @UniqueConstraint(name = "uk_user_post_favorite", columnNames = {"user_id", "post_id"}))
public class UserPostFavorite extends PanacheEntityBase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    public User user;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "post_id", nullable = false)
    public Post post;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false)
    public OffsetDateTime createdAt;
}
