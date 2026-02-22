package com.biliwind.blog.model;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;

import java.time.OffsetDateTime;

/**
 * 用户实体，对应 users
 */
@Entity
@Table(name = "users")
public class User extends PanacheEntityBase {

    /** 主键ID */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    /** 用户名（唯一） */
    @Column(nullable = false, length = 100, unique = true)
    public String username;

    /** 邮箱（唯一） */
    @Column(nullable = false, length = 255, unique = true)
    public String email;

    /** 加密后密码 */
    @Column(nullable = false, length = 255)
    public String password;

    /** 用户状态：0禁用，1正常 */
    @Column(nullable = false)
    public short status;

    /**
     * 用户角色
     */
    @Column(name = "role_name", length = 64)
    public String roleName;

    /** 创建时间 */
    @Column(name = "created_at", nullable = false)
    public OffsetDateTime createdAt;

    /** 更新时间 */
    @Column(name = "updated_at", nullable = false)
    public OffsetDateTime updatedAt;

    /** 软删除时间 */
    @Column(name = "deleted_at")
    public OffsetDateTime deletedAt;
}
