package com.biliwind.blog.model;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;

@Entity
@Table(name = "static_cache_stats")
public class StaticCacheStat extends PanacheEntityBase {

    @Id
    @Column(name = "cache_key", length = 512)
    public String cacheKey;

    @Column(name = "path", nullable = false, length = 512)
    public String path;

    @Column(name = "lang", nullable = false, length = 32)
    public String lang = "zh-CN";

    @Column(name = "region", nullable = false, length = 32)
    public String region = "DEFAULT";

    @Column(name = "variant", nullable = false, length = 16)
    public String variant = "full";

    @Column(name = "hits", nullable = false)
    public long hits = 0L;

    @Column(name = "misses", nullable = false)
    public long misses = 0L;

    @Column(name = "last_hit_at")
    public OffsetDateTime lastHitAt;

    @Column(name = "updated_at", nullable = false)
    public OffsetDateTime updatedAt = OffsetDateTime.now();
}
