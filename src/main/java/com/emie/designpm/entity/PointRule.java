package com.emie.designpm.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@Entity
@Table(name = "point_rules")
public class PointRule {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "rule_code", nullable = false, unique = true, length = 80)
    private String ruleCode;

    @Column(nullable = false)
    private Integer points;

    @Column(length = 50) private String category;
    @Column(nullable = false) private Double difficultyMultiplier = 1.0;
    @Column(nullable = false) private Integer qualityBonusThreshold = 0;
    @Column(nullable = false) private Double qualityBonusRatio = 0.0;
    @Column(nullable = false) private Integer qualityTopThreshold = 97;
    @Column(nullable = false) private Double qualityTopRatio = 0.60;
    @Column(nullable = false) private Double maxTotalMultiplier = 3.0;
    @Column(nullable = false) private boolean countInPerformance = true;

    @Column(nullable = false)
    private boolean enabled = true;

    @Column(length = 255)
    private String description;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    @Column(nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist void onCreate() { createdAt = updatedAt = LocalDateTime.now(); }
    @PreUpdate void onUpdate() { updatedAt = LocalDateTime.now(); }
}
