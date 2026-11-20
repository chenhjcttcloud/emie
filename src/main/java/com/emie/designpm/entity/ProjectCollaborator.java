package com.emie.designpm.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDateTime;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 项目负责人授权的协作企划，可验收自己发布的子任务，不改变项目归属。 */
@Data
@NoArgsConstructor
@Entity
@Table(
        name = "project_collaborators",
        uniqueConstraints =
                @UniqueConstraint(
                        name = "uk_project_collaborator",
                        columnNames = {"project_id", "user_id"}),
        indexes = @Index(name = "idx_project_collaborator_user", columnList = "user_id"))
public class ProjectCollaborator {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "project_id", nullable = false)
    private Long projectId;

    @Column(name = "user_id", nullable = false)
    private String userId;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "assigned_by", nullable = false)
    private String assignedBy;

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = LocalDateTime.now();
    }
}
