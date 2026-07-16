package com.emie.designpm.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/** 用户站内通知；即使外部渠道失败，也必须保留。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "notifications", indexes = {
        @Index(name = "idx_notification_recipient_status", columnList = "recipientUserId,status,createdAt"),
        @Index(name = "idx_notification_event", columnList = "eventId"),
        @Index(name = "idx_notification_aggregate", columnList = "aggregateType,aggregateId")
})
public class Notification {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long eventId;

    @Column(nullable = false, length = 100)
    private String recipientUserId;

    @Column(nullable = false, length = 80)
    private String category;

    @Column(nullable = false, length = 20)
    @Builder.Default
    private String priority = "normal";

    @Column(nullable = false)
    @Builder.Default
    private Boolean mandatory = false;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    @Column(length = 500)
    private String deepLink;

    @Column(length = 50)
    private String aggregateType;

    private Long aggregateId;

    @Column(nullable = false, length = 20)
    @Builder.Default
    private String status = "unread";

    @Column(nullable = false)
    private LocalDateTime createdAt;

    private LocalDateTime readAt;
    private LocalDateTime clickedAt;
    private LocalDateTime archivedAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) createdAt = LocalDateTime.now();
    }
}
