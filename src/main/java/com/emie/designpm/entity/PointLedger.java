package com.emie.designpm.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@Entity
@Table(name = "point_ledgers", uniqueConstraints = @UniqueConstraint(
        name = "uk_point_ledger_task_rule_user", columnNames = {"user_id", "sub_task_id", "rule_code"}))
public class PointLedger {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false, length = 100)
    private String userId;

    @Column(name = "sub_task_id", nullable = false)
    private Long subTaskId;

    @Column(name = "rule_code", nullable = false, length = 80)
    private String ruleCode;

    @Column(nullable = false)
    private Double points;

    /** 入账时锁定，供绩效汇总排除仅展示、不参与绩效的积分。 */
    @Column(nullable = false)
    private boolean countInPerformance = true;

    @Column(length = 7)
    private String accountingMonth;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    @PrePersist void onCreate() { if (createdAt == null) createdAt = LocalDateTime.now(); }
}
