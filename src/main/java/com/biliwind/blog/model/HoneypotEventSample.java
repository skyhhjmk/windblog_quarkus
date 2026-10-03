package com.biliwind.blog.model;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.List;
import java.util.Map;

@Entity
@Table(name = "honeypot_event_samples")
public class HoneypotEventSample extends PanacheEntityBase {

    @Id
    @Column(name = "event_id")
    public Long eventId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "request_headers", nullable = false, columnDefinition = "jsonb")
    public Map<String, List<String>> requestHeaders;

    @Column(name = "body_sample", columnDefinition = "bytea")
    public byte[] bodySample;
}
