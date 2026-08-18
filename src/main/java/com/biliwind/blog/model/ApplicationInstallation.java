package com.biliwind.blog.model;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;

@Entity
@Table(name = "application_installation")
public class ApplicationInstallation extends PanacheEntityBase {

    @Id
    public Long id;

    @Column(nullable = false)
    public boolean installed;

    @Column(name = "installed_at")
    public OffsetDateTime installedAt;

    @Column(name = "installed_by")
    public Long installedBy;

    public static ApplicationInstallation current() {
        return findById(1L);
    }
}
