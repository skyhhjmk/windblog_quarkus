package com.biliwind.blog.model;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;

@Entity
@Table(name = "seo_redirects")
public class SeoRedirect extends PanacheEntityBase {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) public Long id;
    @Column(nullable = false, unique = true, length = 512) public String source;
    @Column(nullable = false, length = 512) public String target;
    @Column(nullable = false) public int status = 301;
    @Column(nullable = false) public boolean enabled = true;
}
