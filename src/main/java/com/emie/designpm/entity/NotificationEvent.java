package com.emie.designpm.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 通知领域事件 Outbox。业务事务提交时写入，后续由通知编排器异步处理。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "notification_events", uniqueConstraints = {
        @UniqueConstraint(name = "uk_notification_event_idempotency", columnNames = "idempotencyKey")
}, indexes = {
        @Index(name = "idx_notification_event_status_time", columnList = "status,occurredAt"),
        @Index(name = "idx_notification_event_aggregate", columnList = "aggregateType,aggregateId")
})
public class NotificationEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 80)
    private String eventType;

    @Column(nullable = false, length = 50)
    private String aggregateType;

    @Column(nullable = false)
    private Long aggregateId;

    /** 同一业务对象的状态版本；不同版本必须独立通知。 */
    private Integer aggregateVersion;

    @Column(length = 100)
    private String actorUserId;

    @Column(nullable = false, length = 64)
    private String idempotencyKey;

    @Column(nullable = false, length = 20)
    @Builder.Default
    private String status = "pending";

    @Column(columnDefinition = "LONGTEXT")
    private String payload;

    @Column(nullable = false)
    private LocalDateTime occurredAt;

    private LocalDateTime processedAt;

    @Column(columnDefinition = "TEXT")
    private String errorMsg;

    @PrePersist
    protected void onCreate() {
        if (occurredAt == null) occurredAt = LocalDateTime.now();
    }
}
