package com.biliwind.blog.model;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;

@Entity
@Table(name = "content_access_ticket_key_state")
public class ContentAccessTicketKeyState extends PanacheEntityBase {

    @Id
    public Integer id;

    @Column(name = "current_version", nullable = false)
    public Integer currentVersion;

    @Column(name = "updated_at", nullable = false)
    public OffsetDateTime updatedAt;
}
