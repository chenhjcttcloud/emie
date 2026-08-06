package com.emie.designpm.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "sync_queue", indexes = {
    @Index(name = "idx_sync_status", columnList = "status,createdAt"),
    @Index(name = "idx_sync_entity_status", columnList = "entityType,entityId,status")
})
public class SyncQueue {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** project / sub_task / scoring_record / activity_log / user */
    @Column(nullable = false, length = 50)
    private String entityType;

    /** 对应的业务记录 ID */
    @Column(nullable = false)
    private Long entityId;

    /** create / update / delete */
    @Column(nullable = false, length = 20)
    private String action;

    /** pending / processing / done / fail */
    @Builder.Default
    @Column(nullable = false, length = 20)
    private String status = "pending";

    @Builder.Default
    private Integer retryCount = 0;

    /** 下次允许请求飞书的时间，避免外部故障时每轮立即重试。 */
    private LocalDateTime nextRetryAt;

    @Column(columnDefinition = "TEXT")
    private String errorMsg;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) createdAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
