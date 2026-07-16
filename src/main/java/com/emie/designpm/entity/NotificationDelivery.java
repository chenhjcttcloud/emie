package com.emie.designpm.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/** 站内、飞书等渠道的独立投递状态。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "notification_deliveries", uniqueConstraints = {
        @UniqueConstraint(name = "uk_notification_delivery_channel", columnNames = {"notificationId", "channel"})
}, indexes = {
        @Index(name = "idx_notification_delivery_status_time", columnList = "status,nextRetryAt"),
        @Index(name = "idx_notification_delivery_notification", columnList = "notificationId")
})
public class NotificationDelivery {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long notificationId;

    @Column(nullable = false, length = 30)
    private String channel;

    @Column(nullable = false, length = 20)
    @Builder.Default
    private String status = "pending";

    @Builder.Default
    private Integer retryCount = 0;

    private LocalDateTime nextRetryAt;
    private LocalDateTime deliveredAt;

    @Column(length = 200)
    private String externalMessageId;

    @Column(columnDefinition = "TEXT")
    private String errorMsg;
}
