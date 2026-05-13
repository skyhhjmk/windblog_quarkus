package com.biliwind.blog.edge.model;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.Map;

@Entity
@Table(name = "media")
public class Media extends PanacheEntityBase {
    @Id
    public Long id;

    @Column(name = "storage_key")
    public String storageKey;

    @Column(name = "mime_type")
    public String mimeType;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "storage_providers", columnDefinition = "jsonb")
    public Map<String, Object> storageProviders;

    @Column
    public Integer version;
}
