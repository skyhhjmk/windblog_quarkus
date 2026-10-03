package com.biliwind.blog.model;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;

@Entity
@Table(name = "honeypot_rules")
public class HoneypotRule extends PanacheEntityBase {

    @Id
    @Column(name = "rule_key", length = 64)
    public String ruleKey;

    @Column(nullable = false)
    public boolean enabled;

    @Column(nullable = false, length = 16)
    public String action;

    @Column(name = "updated_at", nullable = false)
    public OffsetDateTime updatedAt;
}
