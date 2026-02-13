package com.biliwind.blog.model;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Map;

/**
 * 友情链接审核记录实体，对应 link_audit。
 */
@Entity
@Table(name = "link_audit")
public class LinkAudit extends PanacheEntityBase {

    /** 主键ID */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    /** 关联链接 */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "link_id", nullable = false)
    public Link link;

    /** 审核状态：1=approved,2=rejected,3=spam,4=pending,5=error */
    @Column(nullable = false)
    public short status;

    /** 审核评分 */
    @Column(precision = 5, scale = 2)
    public BigDecimal score;

    /** 置信度 */
    @Column(precision = 4, scale = 3)
    public BigDecimal confidence;

    /** 审核原因 */
    @Column(columnDefinition = "text")
    public String reason;

    /** 分类信息(JSONB) */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    public Map<String, Object> categories;

    /** 是否自动通过 */
    @Column(name = "auto_approved")
    public Boolean autoApproved;

    /** 是否自动隐藏 */
    @Column(name = "auto_hidden")
    public Boolean autoHidden;

    /** 创建时间 */
    @Column(name = "created_at", nullable = false)
    public OffsetDateTime createdAt;
}