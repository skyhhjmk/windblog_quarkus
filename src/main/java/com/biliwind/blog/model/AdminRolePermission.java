package com.biliwind.blog.model;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

import java.io.Serializable;
import java.time.OffsetDateTime;

@Entity
@Table(name = "admin_role_permissions")
@IdClass(AdminRolePermission.Key.class)
public class AdminRolePermission extends PanacheEntityBase {

    @Id
    @Column(name = "role_name", length = 64)
    public String roleName;

    @Id
    @Column(length = 128)
    public String permission;

    @Column(nullable = false)
    public boolean enabled;

    @Column(name = "updated_at", nullable = false)
    public OffsetDateTime updatedAt;

    public static class Key implements Serializable {
        public String roleName;
        public String permission;

        public Key() {
        }

        public Key(String roleName, String permission) {
            this.roleName = roleName;
            this.permission = permission;
        }

        @Override
        public boolean equals(Object other) {
            if (!(other instanceof Key key)) {
                return false;
            }
            return java.util.Objects.equals(roleName, key.roleName)
                    && java.util.Objects.equals(permission, key.permission);
        }

        @Override
        public int hashCode() {
            return java.util.Objects.hash(roleName, permission);
        }
    }
}
