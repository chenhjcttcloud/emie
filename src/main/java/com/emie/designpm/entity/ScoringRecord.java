package com.emie.designpm.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

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

    /** 单一评分 1-10（新系统使用，替代 aesthetics + innovation） */
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
