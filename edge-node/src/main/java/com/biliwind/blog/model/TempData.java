package com.biliwind.blog.model;

import com.fasterxml.jackson.databind.JsonNode;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.List;

@Entity
@Table(name = "temp_data")
public class TempData extends PanacheEntityBase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(nullable = false, length = 64)
    public String type;

    @Column(nullable = false, length = 128)
    public String target;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    public JsonNode payload;

    @Column(nullable = false, length = 32)
    public String status = "pending";

    @Column(name = "retry_count", nullable = false)
    public Integer retryCount = 0;

    @Column(name = "max_retries", nullable = false)
    public Integer maxRetries = 3;

    @Column(name = "error_msg", columnDefinition = "TEXT")
    public String errorMsg;

    @Column(name = "created_at", nullable = false, updatable = false)
    @CreationTimestamp
    public OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    @UpdateTimestamp
    public OffsetDateTime updatedAt;

    public static List<TempData> findByTypeAndStatus(String type, String status) {
        return list("type = ?1 and status = ?2", type, status);
    }

    public static List<TempData> findPendingByType(String type) {
        return list("type = ?1 and status = 'pending'", type);
    }
}
