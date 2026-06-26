package com.emie.designpm.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@Entity
@Table(name = "activity_logs")
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

    public ActivityLog(String action, String username, String role, Project project) {
        this.action = action;
        this.username = username;
        this.role = role;
        this.time = LocalDateTime.now();
        this.project = project;
        this.projectRefId = project != null ? project.getId() : null;
    }

    /** 无项目关联的日志（如登录、查询等系统操作） */
    public ActivityLog(String action, String username, String role) {
        this.action = action;
        this.username = username;
        this.role = role;
        this.time = LocalDateTime.now();
    }
}
