package com.biliwind.blog.model;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.Map;

/**
 * 友情链接监控日志实体，对应 link_monitor_log。
 */
@Entity
@Table(name = "link_monitor_log")
public class LinkMonitorLog extends PanacheEntityBase {

    /**
     * 主键ID
     */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    /**
     * 关联链接
     */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "link_id", nullable = false)
    public Link link;

    /**
     * 检查时间
     */
    @Column(name = "check_time", nullable = false)
    public OffsetDateTime checkTime;

    /**
     * 访问是否成功
     */
    @Column
    public Boolean ok;

    /**
     * 加载耗时(ms)
     */
    @Column(name = "load_time_ms")
    public Integer loadTimeMs;

    /**
     * 是否发现反链
     */
    @Column(name = "backlink_found")
    public Boolean backlinkFound;

    /**
     * HTTP 状态码
     */
    @Column(name = "status_code")
    public Integer statusCode;

    /**
     * 原始检测数据(JSONB)
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "raw_data", columnDefinition = "jsonb")
    public Map<String, Object> rawData;

    /**
     * 创建时间
     */
    @Column(name = "created_at", nullable = false, updatable = false)
    @CreationTimestamp
    public OffsetDateTime createdAt;
}