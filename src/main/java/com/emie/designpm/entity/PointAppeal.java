package com.emie.designpm.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.time.LocalDateTime;
import java.util.List;

@Data @NoArgsConstructor @Entity
@Table(name = "point_appeals", indexes = {
        @Index(name = "idx_point_appeal_user", columnList = "applicant_user_id,created_at"),
        @Index(name = "idx_point_appeal_status", columnList = "status")})
public class PointAppeal {
    // 并发兜底说明：
    // - V38 迁移新增生成列 active_ledger_user_key（仅 SUBMITTED/PLANNER_PROCESSED 生成
    //   CONCAT(point_ledger_id,':',applicant_user_id)，其余 NULL）并建唯一索引，保证同
    //   (ledger,user) 最多一笔处理中异议；
    // - V40 迁移新增生成列 approved_ledger_user_key（仅 APPROVED 生成 CONCAT('A:',point_ledger_id,':',applicant_user_id)，
    //   其余 NULL）并建唯一索引，兜底 submit 查重与 INSERT 之间管理员恰终审通过的窄 TOCTOU，
    //   保证同 (ledger,user) 最多一笔 APPROVED，第二次 APPROVE 被索引拒绝，杜绝重复调账。
    // 不使用 MySQL 函数索引：Hibernate 6.6 的 ddl-auto update/validate 读取函数索引元数据时
    // 列名为 NULL 会抛 NPE。JPA 无法表达按状态过滤的部分唯一约束，因此约束只存在于迁移层；
    // 服务层 PointAppealService.submit 同时做了拦截（APPROVED 后拒绝重复申诉）并捕获
    // DataIntegrityViolationException 转为中文业务异常。

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name="point_ledger_id", nullable=false) private Long pointLedgerId;
    @Column(name="applicant_user_id", nullable=false, length=100) private String applicantUserId;
    @Column(name="applicant_name", nullable=false, length=100) private String applicantName;
    @Column(nullable=false, length=40) private String type;
    @Column(nullable=false, length=1000) private String reason;
    @Column(name="corrected_score", precision=12, scale=1) private Double correctedScore;
    @Column(name="attachments_json", columnDefinition="TEXT") private String attachmentsJson;
    @Column(nullable=false, length=32) private String status = "SUBMITTED";
    @Column(name="planner_decision", length=20) private String plannerDecision;
    @Column(name="planner_comment", length=1000) private String plannerComment;
    @Column(name="planner_user_id", length=100) private String plannerUserId;
    @Column(name="planner_name", length=100) private String plannerName;
    @Column(name="planner_processed_at") private LocalDateTime plannerProcessedAt;
    @Column(name="admin_decision", length=20) private String adminDecision;
    @Column(name="admin_comment", length=1000) private String adminComment;
    @Column(name="admin_user_id", length=100) private String adminUserId;
    @Column(name="admin_name", length=100) private String adminName;
    @Column(name="admin_reviewed_at") private LocalDateTime adminReviewedAt;
    @Column(name="created_at", nullable=false) private LocalDateTime createdAt;
    @Column(name="updated_at", nullable=false) private LocalDateTime updatedAt;
    @Column(name="due_at") private LocalDateTime dueAt;
    public boolean isOverdue(){ return !List.of("APPROVED","REJECTED").contains(status) && dueAt != null && LocalDateTime.now().isAfter(dueAt); }
    @PrePersist void create(){ createdAt=updatedAt=LocalDateTime.now(); }
    @PreUpdate void update(){ updatedAt=LocalDateTime.now(); }
}
