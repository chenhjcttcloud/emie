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
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name="point_ledger_id", nullable=false) private Long pointLedgerId;
    @Column(name="applicant_user_id", nullable=false, length=100) private String applicantUserId;
    @Column(name="applicant_name", nullable=false, length=100) private String applicantName;
    @Column(nullable=false, length=40) private String type;
    @Column(nullable=false, length=1000) private String reason;
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
