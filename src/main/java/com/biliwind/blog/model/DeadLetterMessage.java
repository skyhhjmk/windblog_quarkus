package com.biliwind.blog.model;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Map;

/**
 * 死信消息记录实体
 * 用于持久化存储进入死信队列的消息，方便后续分析和重试
 */
@Entity
@Table(name = "dead_letter_messages")
@SequenceGenerator(
    name = "dead_letter_messages_sequence",
    sequenceName = "dead_letter_messages_id_seq",
    allocationSize = 1,
    initialValue = 1
)
public class DeadLetterMessage extends PanacheEntityBase {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "dead_letter_messages_sequence")
    public Long id;

    /**
     * 消息来源队列名称
     */
    @Column(name = "source_queue", nullable = false, length = 255)
    public String sourceQueue;

    /**
     * 交换机名称
     */
    @Column(name = "exchange_name", length = 255)
    public String exchangeName;

    /**
     * 路由键
     */
    @Column(name = "routing_key", length = 255)
    public String routingKey;

    /**
     * 文章 ID（如果是 AI 摘要任务）
     */
    @Column(name = "post_id")
    public Long postId;

    /**
     * 任务优先级
     */
    @Column(name = "priority")
    public Integer priority;

    /**
     * 重试次数
     */
    @Column(name = "retry_count")
    public Integer retryCount;

    /**
     * 错误原因
     */
    @Column(name = "error_reason", length = 1024)
    public String errorReason;

    /**
     * 消息内容（JSON 格式）
     */
    @Column(name = "message_content", columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    public Map<String, Object> messageContent;

    /**
     * 消息进入死信队列的时间
     */
    @Column(name = "dead_lettered_at", nullable = false)
    public Instant deadLetteredAt;

    /**
     * 是否已处理（手动重试或标记为已读）
     */
    @Column(name = "is_processed", nullable = false)
    public boolean isProcessed = false;

    /**
     * 处理时间
     */
    @Column(name = "processed_at")
    public Instant processedAt;

    /**
     * 处理备注
     */
    @Column(name = "process_note", length = 512)
    public String processNote;

    /**
     * 创建时间
     */
    @Column(name = "created_at", nullable = false, updatable = false)
    public Instant createdAt;

    /**
     * 更新时间
     */
    @Column(name = "updated_at")
    public Instant updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = Instant.now();
        deadLetteredAt = Instant.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = Instant.now();
        if (isProcessed && processedAt == null) {
            processedAt = Instant.now();
        }
    }

    /**
     * 查找所有未处理的死信消息
     */
    public static java.util.List<DeadLetterMessage> findUnprocessed() {
        return list("isProcessed", false);
    }

    /**
     * 根据 postId 查找死信消息
     */
    public static java.util.List<DeadLetterMessage> findByPostId(Long postId) {
        return list("postId", postId);
    }

    /**
     * 标记为已处理
     */
    public void markAsProcessed(String note) {
        this.isProcessed = true;
        this.processNote = note;
        this.processedAt = Instant.now();
        this.persist();
    }
}
