package com.biliwind.blog.model;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.OffsetDateTime;

/**
 * 封禁域名，对应 blocked_domains。
 */
@Entity
@Table(name = "blocked_domains")
public class BlockedDomain extends PanacheEntityBase {

    /**
     * 主键ID
     */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    /**
     * 域名
     */
    @Column(name = "domain_name", nullable = false, length = 255, unique = true)
    public String domainName;

    /**
     * 封禁原因
     */
    @Column(columnDefinition = "text")
    public String reason;

    /**
     * 状态：1生效，2停用
     */
    @Column(nullable = false)
    public short status = 1;

    /**
     * 创建时间
     */
    @Column(name = "created_at", nullable = false, updatable = false)
    @CreationTimestamp
    public OffsetDateTime createdAt;

    /**
     * 更新时间
     */
    @Column(name = "updated_at", nullable = false)
    @UpdateTimestamp
    public OffsetDateTime updatedAt;
}
