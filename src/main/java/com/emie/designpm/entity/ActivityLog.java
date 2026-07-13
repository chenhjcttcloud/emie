package com.emie.designpm.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@Entity
@Table(name = "activity_logs", indexes = {
    @Index(name = "idx_activity_project", columnList = "project_id"),
    @Index(name = "idx_activity_time", columnList = "time"),
    @Index(name = "idx_activity_username_time", columnList = "username,time"),
    @Index(name = "idx_activity_role_time", columnList = "role,time")
})
public class ActivityLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String action;

    @Column(nullable = false)
    private String username;

    @Column(nullable = false)
    private String role;

    @Column(nullable = false)
    private LocalDateTime time;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "project_id", nullable = true)
    private Project project;

    /** 关联的项目ID（冗余字段，项目删除后仍可查看日志） */
    private Long projectRefId;

    /** 审计元数据：实体类型/ID及变更前后快照（JSON，兼容历史日志为空） */
    @Column(length = 50)
    private String entityType;
    private Long entityId;
    @Column(columnDefinition = "LONGTEXT")
    private String beforeData;
    @Column(columnDefinition = "LONGTEXT")
    private String afterData;
    @Column(columnDefinition = "TEXT")
    private String changedFields;

    public ActivityLog(String action, String username, String role, Project project) {
        this.action = action;
        this.username = username;
        this.role = role;
        this.time = LocalDateTime.now();
        this.project = project;
        this.projectRefId = project != null ? project.getId() : null;
        this.entityType = "project";
        this.entityId = project != null ? project.getId() : null;
    }

    /** 无项目关联的日志（如登录、查询等系统操作） */
    public ActivityLog(String action, String username, String role) {
        this.action = action;
        this.username = username;
        this.role = role;
        this.time = LocalDateTime.now();
    }

    public ActivityLog(String action, String username, String role, Project project,
                       String entityType, Long entityId, String beforeData,
                       String afterData, String changedFields) {
        this(action, username, role, project);
        this.entityType = entityType;
        this.entityId = entityId;
        this.beforeData = beforeData;
        this.afterData = afterData;
        this.changedFields = changedFields;
    }
}
