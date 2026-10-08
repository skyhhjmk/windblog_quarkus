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

    /** 主键ID */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    /** 关联链接 */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "link_id", nullable = false)
    public Link link;

    /** 检查时间 */
    @Column(name = "check_time", nullable = false)
    public OffsetDateTime checkTime;

    /** 检测来源：自动定时检测或管理员手动检测 */
    @Enumerated(EnumType.STRING)
    @Column(name = "check_source", nullable = false, length = 16)
    public LinkMonitorSource checkSource;

    /**
     * 同一轮多节点检测批次ID
     */
    @Column(name = "check_batch_id", length = 64)
    public String checkBatchId;

    /**
     * 执行检测的节点ID
     */
    @Column(name = "node_id", nullable = false, length = 128)
    public String nodeId;

    /**
     * 执行检测的节点名称
     */
    @Column(name = "node_name")
    public String nodeName;

    /** 访问是否成功 */
    @Column
    public Boolean ok;

    /** 加载耗时(ms) */
    @Column(name = "load_time_ms")
    public Integer loadTimeMs;

    /** 是否发现反链 */
    @Column(name = "backlink_found")
    public Boolean backlinkFound;

    /** HTTP 状态码 */
    @Column(name = "status_code")
    public Integer statusCode;

    /**
     * 检测失败原因
     */
    @Column(name = "error_message")
    public String errorMessage;

    /** 原始检测数据(JSONB) */
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
