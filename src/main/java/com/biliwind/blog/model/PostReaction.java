package com.biliwind.blog.model;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.runtime.annotations.RegisterForReflection;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 文章表情互动与点赞汇总实体，对应 post_reactions 表
 */
@Entity
@Table(name = "post_reactions")
@RegisterForReflection
public class PostReaction extends PanacheEntityBase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(name = "post_slug", nullable = false, unique = true, length = 160)
    public String postSlug;

    @Column(name = "like_count", nullable = false)
    public long likeCount;

    @Column(name = "heart_count", nullable = false)
    public long heartCount;

    @Column(name = "rocket_count", nullable = false)
    public long rocketCount;

    @Column(name = "celebrate_count", nullable = false)
    public long celebrateCount;

    @Column(name = "insightful_count", nullable = false)
    public long insightfulCount;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false)
    public OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    public OffsetDateTime updatedAt;

    public long getCount(String type) {
        if (type == null) return 0L;
        return switch (type) {
            case "like" -> likeCount;
            case "heart" -> heartCount;
            case "rocket" -> rocketCount;
            case "celebrate" -> celebrateCount;
            case "insightful" -> insightfulCount;
            default -> 0L;
        };
    }

    public void setCount(String type, long val) {
        if (type == null) return;
        long safeVal = Math.max(0L, val);
        switch (type) {
            case "like" -> likeCount = safeVal;
            case "heart" -> heartCount = safeVal;
            case "rocket" -> rocketCount = safeVal;
            case "celebrate" -> celebrateCount = safeVal;
            case "insightful" -> insightfulCount = safeVal;
        }
    }

    public void applyDelta(String type, long delta) {
        setCount(type, getCount(type) + delta);
    }

    public Map<String, Long> toMap() {
        Map<String, Long> map = new LinkedHashMap<>();
        map.put("like", likeCount);
        map.put("heart", heartCount);
        map.put("rocket", rocketCount);
        map.put("celebrate", celebrateCount);
        map.put("insightful", insightfulCount);
        return map;
    }
}
