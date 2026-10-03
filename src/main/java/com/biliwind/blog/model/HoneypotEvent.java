package com.biliwind.blog.model;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.List;

@Entity
@Table(name = "honeypot_events")
public class HoneypotEvent extends PanacheEntityBase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(name = "client_ip", nullable = false, length = 128)
    public String clientIp;

    @Column(name = "remote_ip", length = 128)
    public String remoteIp;

    @Column(nullable = false, length = 16)
    public String method;

    @Column(nullable = false, columnDefinition = "text")
    public String requestUri;

    @Column(name = "user_agent", columnDefinition = "text")
    public String userAgent;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "matched_rules", nullable = false, columnDefinition = "jsonb")
    public List<String> matchedRules;

    @Column(nullable = false, length = 16)
    public String action;

    @Column(name = "body_truncated", nullable = false)
    public boolean bodyTruncated;

    @Column(name = "body_bytes", nullable = false)
    public int bodyBytes;

    @Column(name = "created_at", nullable = false, updatable = false)
    public OffsetDateTime createdAt;
}
