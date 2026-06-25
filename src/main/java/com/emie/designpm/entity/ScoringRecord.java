package com.emie.designpm.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@Entity
@Table(name = "scoring_records", uniqueConstraints = {
    @UniqueConstraint(columnNames = {"sub_task_id", "role"})
})
public class ScoringRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 角色: sales / planner / designer / superior */
    @Column(nullable = false)
    private String role;

    /** 审美评分 1-10 */
    private Double aesthetics;

    /** 创新评分 1-10 */
    private Double innovation;

    /** 该角色评分权重 (0-1) */
    @Column(nullable = false)
    private Double weight;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "sub_task_id", nullable = false)
    private SubTask subTask;
}
