package com.emie.designpm.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@Entity
@Table(name = "point_rules")
public class PointRule {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "rule_code", nullable = false, unique = true, length = 80)
    private String ruleCode;

    @Column(nullable = false, columnDefinition = "DECIMAL(12,2)")
    private Double points;

    @Column(length = 50)
    private String category;

    @Column(length = 50)
    private String subcategory;

    @Column(nullable = false)
    private Integer qualityBonusThreshold = 0;

    @Column(nullable = false)
    private Double qualityBonusRatio = 0.0;

    @Column(nullable = false)
    private Integer qualityTopThreshold = 97;

    @Column(nullable = false)
    private Double qualityTopRatio = 0.60;

    @Column(nullable = false)
    private Double maxTotalMultiplier = 3.0;

    @Column(nullable = false)
    private boolean countInPerformance = true;

    @Column(nullable = false)
    private boolean enabled = true;

    @Column(length = 255)
    private String description;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    @Column(nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    void onCreate() {
        createdAt = updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
