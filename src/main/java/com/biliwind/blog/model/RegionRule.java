package com.biliwind.blog.model;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.OffsetDateTime;

/**
 * 区域匹配规则实体
 */
@Entity
@Table(name = "region_rules")
public class RegionRule extends PanacheEntityBase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    /**
     * 规则名称
     */
    @Column(nullable = false)
    public String name;

    /**
     * 规则类型: domain, language
     */
    @Column(name = "rule_type", nullable = false)
    public String ruleType;

    /**
     * 匹配模式: blog.cn, zh-CN
     */
    @Column(nullable = false)
    public String pattern;

    /**
     * 映射到的区域: cn, us, eu, global
     */
    @Column(nullable = false)
    public String region;

    /**
     * 优先级，越大越优先
     */
    @Column(nullable = false)
    public Integer priority = 0;

    /**
     * 是否启用
     */
    @Column(name = "is_enabled", nullable = false)
    public Boolean isEnabled = true;

    /**
     * 创建时间
     */
    @Column(name = "created_at", nullable = false)
    @CreationTimestamp
    public OffsetDateTime createdAt;

    /**
     * 更新时间
     */
    @Column(name = "updated_at", nullable = false)
    @UpdateTimestamp
    public OffsetDateTime updatedAt;
}
