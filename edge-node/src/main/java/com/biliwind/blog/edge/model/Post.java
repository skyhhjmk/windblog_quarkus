package com.biliwind.blog.edge.model;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "post")
public class Post extends PanacheEntityBase {
    @Id
    public Long id;

    @Column
    public String title;

    @Column
    public String slug;

    @Column(columnDefinition = "TEXT")
    public String content;

    @Column(name = "is_published")
    public Boolean isPublished;
}
