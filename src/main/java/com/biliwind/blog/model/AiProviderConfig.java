package com.biliwind.blog.model;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;

import java.time.OffsetDateTime;

@Entity
@Table(name = "ai_provider_configs")
public class AiProviderConfig extends PanacheEntityBase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(nullable = false, unique = true, length = 64)
    public String provider;

    @Column(nullable = false)
    public boolean enabled;

    @Column(length = 512)
    public String endpoint;

    @Column(name = "api_key", length = 512)
    public String apiKey;

    @Column(length = 128)
    public String model;

    @Column(name = "updated_at", nullable = false)
    public OffsetDateTime updatedAt;
}
