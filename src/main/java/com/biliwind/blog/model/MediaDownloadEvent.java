package com.biliwind.blog.model;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.OffsetDateTime;

@Entity
@Table(name = "media_download_event")
public class MediaDownloadEvent extends PanacheEntityBase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(name = "media_id", nullable = false)
    public Long mediaId;

    @Column(name = "post_id", nullable = false)
    public Long postId;

    @Column(name = "ticket_id")
    public Long ticketId;

    @Column(name = "subject_hash", length = 128)
    public String subjectHash;

    @Column(name = "ip_hash", length = 128)
    public String ipHash;

    @Column(name = "ua_hash", length = 128)
    public String userAgentHash;

    @Column(name = "referrer_hash", length = 128)
    public String referrerHash;

    @Column(name = "bytes_sent")
    public Long bytesSent;

    @Column(name = "ticket_age_ms")
    public Long ticketAgeMillis;

    @Column(nullable = false, length = 32)
    public String status;

    @Column(name = "deny_reason", length = 128)
    public String denyReason;

    @Column(name = "node_id", nullable = false, length = 100)
    public String nodeId;

    @Column(name = "created_at", nullable = false, updatable = false)
    @CreationTimestamp
    public OffsetDateTime createdAt;
}
