package com.emie.designpm.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/** 可跨进程查询的全员通知广播任务。 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "notification_broadcast_jobs")
public class NotificationBroadcastJob {

    @Id
    @Column(length = 36)
    private String id;

    @Column(nullable = false, length = 100)
    private String operatorUserId;

    @Column(nullable = false, length = 20)
    private String status;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    private LocalDateTime completedAt;

    @Column(columnDefinition = "LONGTEXT")
    private String resultJson;

    @Column(length = 500)
    private String error;

    @Column(nullable = false, length = 36)
    private String ownerInstanceId;

    public NotificationBroadcastJob(String id, String operatorUserId, String ownerInstanceId,
                                    LocalDateTime createdAt) {
        this.id = id;
        this.operatorUserId = operatorUserId;
        this.status = "running";
        this.ownerInstanceId = ownerInstanceId;
        this.createdAt = createdAt;
    }
}
