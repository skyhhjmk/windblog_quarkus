package com.biliwind.blog.model;

import com.fasterxml.jackson.databind.JsonNode;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * 用户签到记录实体
 */
@Entity
@Table(name = "user_check_ins", uniqueConstraints = {
        @UniqueConstraint(columnNames = {"user_id", "check_in_date"})
})
public class UserCheckIn extends PanacheEntityBase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    /**
     * 用户 ID
     */
    @Column(name = "user_id", nullable = false)
    public Long userId;

    /**
     * 签到日期
     */
    @Column(name = "check_in_date", nullable = false)
    public LocalDate checkInDate;

    /**
     * 奖励详情（JSONB格式，记录当时发放的奖励）
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "reward_info", columnDefinition = "jsonb")
    public JsonNode rewardInfo;

    /**
     * 创建时间
     */
    @Column(name = "created_at", nullable = false, updatable = false)
    @CreationTimestamp
    public OffsetDateTime createdAt;

    /**
     * 根据用户和日期查找签到记录
     */
    public static UserCheckIn findByUserAndDate(Long userId, LocalDate date) {
        return find("userId = ?1 and checkInDate = ?2", userId, date).firstResult();
    }
}
