package com.emie.designpm.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@Entity
@Table(name = "scoring_records", indexes = {
    @Index(name = "idx_scoring_sub_task", columnList = "sub_task_id"),
    @Index(name = "idx_scoring_role", columnList = "role"),
    @Index(name = "idx_scoring_role_score", columnList = "role,score"),
    @Index(name = "idx_scoring_task_role", columnList = "sub_task_id,role")
})
@EntityListeners(com.emie.designpm.service.ScoringSyncListener.class)
public class ScoringRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 角色: sales / planner / designer / supplychain / admin */
    @Column(nullable = false)
    private String role;

    /** 评分类型: self=自评 planner=企划评 sales=销售评 admin=管理评 */
    @Column(length = 20)
    private String scoreType;

    /** 审核阶段：first=一审，second=二审 */
    @Column(length = 20)
    private String reviewStage;

    /** 审核状态：waiting=等待前序审核，pending=待审核，approved=已通过，rejected=已驳回 */
    @Column(length = 20)
    private String reviewStatus = "pending";

    /** 实际执行本次审核的用户 */
    @Column(length = 100)
    private String reviewerId;

    @Column(length = 100)
    private String reviewerName;

    /** 审核通过或驳回的时间；待审核时为空 */
    private LocalDateTime reviewedAt;

    /** 单一评分 1-100（新系统使用，替代 aesthetics + innovation） */
    private Integer score;

    /** 评分备注 */
    @Column(columnDefinition = "TEXT")
    private String comment;

    /** 审美评分 1-10（兼容旧数据） */
    private Double aesthetics;

    /** 创新评分 1-10（兼容旧数据） */
    private Double innovation;

    /** 该角色评分权重 (0-1) */
    @Column(nullable = false)
    private Double weight;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "sub_task_id", nullable = false)
    private SubTask subTask;
}
