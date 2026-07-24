package com.emie.designpm.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@Entity
@Table(name = "design_requirement_scores", indexes = {
        @Index(name = "idx_dr_score_requirement", columnList = "requirement_id"),
        @Index(name = "idx_dr_score_reviewer", columnList = "reviewerId,status"),
        @Index(name = "idx_dr_score_role", columnList = "role,status")
})
public class DesignRequirementScore {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "requirement_id", nullable = false)
    private DesignRequirement requirement;

    /** self=设计师自评，review=需求复评。 */
    @Column(nullable = false, length = 20)
    private String stage;

    @Column(nullable = false, length = 40)
    private String role;

    /** 精确到人的评分责任；管理员评分池为空，由任一管理员领取。 */
    private String reviewerId;
    private String reviewerName;

    /** waiting=等待前序，pending=待评分，completed=已评分。 */
    @Column(nullable = false, length = 20)
    private String status = "waiting";

    private Integer score;
    private LocalDateTime scoredAt;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    @Column(nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = createdAt;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
