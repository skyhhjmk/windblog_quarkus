package com.biliwind.blog.model;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;

import java.time.OffsetDateTime;

@Entity
@Table(name = "media_reference_rebuild_jobs")
public class MediaReferenceRebuildJob extends PanacheEntityBase {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(nullable = false, length = 24)
    public String status;

    @Column(name = "last_post_id")
    public Long lastPostId;

    @Column(name = "posts_scanned", nullable = false)
    public long postsScanned;

    @Column(name = "references_created", nullable = false)
    public long referencesCreated;

    @Column(name = "unreferenced_media", nullable = false)
    public long unreferencedMedia;

    @Column(name = "last_error", columnDefinition = "text")
    public String lastError;

    @Column(name = "created_at", nullable = false)
    public OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    public OffsetDateTime updatedAt;
}
