package com.biliwind.blog.model;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.OffsetDateTime;

@Entity
@Table(name = "image_processing_config")
public class ImageProcessingConfig extends PanacheEntityBase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(name = "config_key", nullable = false, unique = true, length = 100)
    public String configKey;

    @Column(name = "config_value", nullable = false, columnDefinition = "TEXT")
    public String configValue;

    @Column(length = 500)
    public String description;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    public OffsetDateTime updatedAt;
}
