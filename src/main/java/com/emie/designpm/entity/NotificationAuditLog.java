package com.emie.designpm.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/** 追加式通知审计，保留创建、投递、阅读、点击、催办和重试历史。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "notification_audit_logs", indexes = {
        @Index(name = "idx_notification_audit_notification_time", columnList = "notificationId,createdAt"),
        @Index(name = "idx_notification_audit_event_time", columnList = "eventId,createdAt"),
        @Index(name = "idx_notification_audit_action_time", columnList = "action,createdAt")
})
public class NotificationAuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long eventId;
    private Long notificationId;
    private Long deliveryId;

    @Column(nullable = false, length = 60)
    private String action;

    @Column(length = 100)
    private String operatorUserId;

    @Column(columnDefinition = "LONGTEXT")
    private String detail;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) createdAt = LocalDateTime.now();
    }
}
