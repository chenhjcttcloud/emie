package com.emie.designpm.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/** 项目子任务总流程中审核阶段的不可覆盖轮次记录。 */
@Data
@NoArgsConstructor
@Entity
@Table(name = "project_workflow_attempts",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_project_workflow_attempt_round",
                columnNames = {"project_id", "stageKey", "attemptNo"}),
        indexes = {
                @Index(name = "idx_project_workflow_attempt_project", columnList = "project_id,id"),
                @Index(name = "idx_project_workflow_attempt_status", columnList = "status,submittedAt")
        })
public class ProjectWorkflowAttempt {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "project_id", nullable = false)
    private Project project;

    @Column(nullable = false, length = 30)
    private String stageKey;

    @Column(nullable = false)
    private Integer attemptNo;

    /** pending / approved / rejected */
    @Column(nullable = false, length = 20)
    private String status;

    @Column(nullable = false, length = 100)
    private String submittedBy;

    @Column(nullable = false, length = 100)
    private String submittedByName;

    @Column(nullable = false)
    private LocalDateTime submittedAt;

    @Column(length = 100)
    private String reviewerId;

    @Column(length = 100)
    private String reviewerName;

    private LocalDateTime reviewedAt;

    @Column(columnDefinition = "TEXT")
    private String comment;
}
