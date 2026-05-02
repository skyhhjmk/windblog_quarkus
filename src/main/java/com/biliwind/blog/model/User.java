package com.biliwind.blog.model;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;

/**
 * 用户实体，对应 users
 */
@Entity
@Table(name = "users")
public class User extends PanacheEntityBase {

    /**
     * 主键ID
     */
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

    /**
     * 用户昵称
     */
    @Column(length = 100)
    public String nickname;

    /**
     * 用户头像 URL
     */
    @Column(length = 512)
    public String avatar;

    /**
     * 手机号
     */
    @Column(length = 20)
    public String phone;

    /**
     * 扩展信息
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "extra_info", columnDefinition = "jsonb")
    public Object extraInfo;

    /**
     * 关联的钱包 ID
     */
    @Column(name = "wallet_id")
    public Long walletId;

    /**
     * 用户等级
     */
    @Column(nullable = false)
    public Integer level = 1;

    /**
     * 经验值
     */
    @Column(nullable = false)
    public Integer exp = 0;

    /**
     * 背包容量（默认36格：4x9）
     */
    @Column(name = "backpack_capacity", nullable = false)
    public Integer backpackCapacity = 36;

    /** 创建时间 */
    @Column(name = "created_at", nullable = false)
    @CreationTimestamp
    public OffsetDateTime createdAt;

    /** 更新时间 */
    @Column(name = "updated_at", nullable = false)
    @UpdateTimestamp
    public OffsetDateTime updatedAt;

    /** 软删除时间 */
    @Column(name = "deleted_at")
    public OffsetDateTime deletedAt;
}
