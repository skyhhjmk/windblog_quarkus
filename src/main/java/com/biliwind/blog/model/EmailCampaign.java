package com.biliwind.blog.model;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;

@Entity
@Table(name = "email_campaigns")
public class EmailCampaign extends PanacheEntityBase {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;
    @Column(nullable = false)
    public String name;
    @Column(nullable = false)
    public String subject;
    @Column(name = "template_id", nullable = false)
    public Long templateId;
    @Column(name = "channel_group_id")
    public Long channelGroupId;
    @Column(name = "channel_id")
    public Long channelId;
    @Column(name = "recipient_type", nullable = false)
    public String recipientType;
    @Column(name = "created_at")
    public OffsetDateTime createdAt;
    @Column(name = "created_by")
    public Long createdBy;
}
