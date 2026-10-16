package com.biliwind.blog.model;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;

@Entity
@Table(name = "seo_post_policies")
public class SeoPostPolicy extends PanacheEntityBase {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) public Long id;
    @Column(name = "post_id", nullable = false, unique = true) public Long postId;
    @Column(nullable = false) public boolean noindex;
    @Column(nullable = false) public boolean nofollow;
}
