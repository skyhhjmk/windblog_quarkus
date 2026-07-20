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
@Table(name = "email_templates")
public class EmailTemplate extends PanacheEntityBase {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;
    @Column(name = "template_key", nullable = false)
    public String templateKey;
    @Column(nullable = false)
    public String name;
    @Column(name = "subject_template", nullable = false)
    public String subjectTemplate;
    @Column(nullable = false)
    public String title;
    @Column(nullable = false)
    public String greeting;
    @Column(nullable = false)
    public String content;
    @Column(name = "button_text", nullable = false)
    public String buttonText;
    @Column(name = "button_url", nullable = false)
    public String buttonUrl;
    @Column(nullable = false)
    public boolean published;
    @Column(nullable = false)
    public int version;
    @Column(name = "updated_at")
    public OffsetDateTime updatedAt;
}
