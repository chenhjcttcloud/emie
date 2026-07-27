package com.emie.designpm.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "permission_audit_logs", indexes = {
        @Index(name = "idx_permission_audit_target_time", columnList = "target_type,target_key,created_at"),
        @Index(name = "idx_permission_audit_actor_time", columnList = "actor_user_id,created_at")
})
public class PermissionAuditLog {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "actor_user_id", nullable = false, length = 255)
    private String actorUserId;

    @Column(name = "actor_name", nullable = false, length = 100)
    private String actorName;

    @Column(nullable = false, length = 50)
    private String action;

    @Column(name = "target_type", nullable = false, length = 30)
    private String targetType;

    @Column(name = "target_key", nullable = false, length = 120)
    private String targetKey;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String reason;

    @Column(name = "before_data", columnDefinition = "LONGTEXT")
    private String beforeData;

    @Column(name = "after_data", columnDefinition = "LONGTEXT")
    private String afterData;

    @Column(name = "source_ip", length = 64)
    private String sourceIp;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
