package com.emie.designpm.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@Entity
@Table(name = "sub_tasks", indexes = {
    @Index(name = "idx_sub_task_designer", columnList = "designerId"),
    @Index(name = "idx_sub_task_project", columnList = "project_id"),
    @Index(name = "idx_sub_task_status", columnList = "status"),
    @Index(name = "idx_sub_task_status_designer", columnList = "status,designerId"),
    @Index(name = "idx_sub_task_project_status", columnList = "project_id,status"),
    @Index(name = "idx_sub_task_assignee_designer_status", columnList = "assigneeRole,designerId,status")
})
@EntityListeners(com.emie.designpm.service.SubTaskSyncListener.class)
public class SubTask {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    /** pending / accepted / delivered / planner_approved / rejected / scoring_planner / sales_approved / admin_approved / completed */
    @Column(nullable = false)
    private String status = "pending";

    @Column(nullable = false)
    private String plannedDate;

    private String actualDate;
    private String designerId;
    private String designerName;

    /** 创建并派发该子任务的用户。用于“我的子任务”同时覆盖负责人与发布人。 */
    private String publisherId;
    private String publisherName;
    private String publisherRole;

    /** 负责人角色类型：designer / supplychain（用于区分设计师和供应链的子任务） */
    private String assigneeRole;

    @Column(columnDefinition = "TEXT")
    private String details;

    @Column(columnDefinition = "TEXT")
    private String deliverables;

    /** attachments stored as JSON array string */
    @Column(columnDefinition = "LONGTEXT")
    private String attachmentsJson;

    /** subtask reference images stored as JSON array string */
    @Column(columnDefinition = "LONGTEXT")
    private String referenceImagesJson;

    @Column(columnDefinition = "TEXT")
    private String reviewComments;

    /** 设计师自评 - 审美评分 1-10（交付时提交），支持1位小数 */
    private Double selfAesthetics;

    /** 设计师自评 - 创新评分 1-10（交付时提交），支持1位小数 */
    private Double selfInnovation;

    /** 设计师自评综合分数（旧字段，兼容保留） */
    private Double selfScore;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    @Column(nullable = false)
    private LocalDateTime updatedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "project_id", nullable = false)
    private Project project;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = createdAt;
        if (status == null) status = "pending";
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
