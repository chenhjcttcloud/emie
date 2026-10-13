package com.emie.designpm.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Data;

@Data
@Entity
@Table(
        name = "monthly_designer_evaluations",
        uniqueConstraints = @UniqueConstraint(columnNames = {"user_id", "month_key"}))
public class MonthlyDesignerEvaluation {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String userId;

    @Column(nullable = false, length = 7)
    private String monthKey;

    @Version
    private Long version;

    @Column(nullable = false, precision = 7, scale = 2)
    private BigDecimal completionScore;

    @Column(nullable = false, precision = 7, scale = 2)
    private BigDecimal adjustmentScore;

    @Column(nullable = false, precision = 7, scale = 2)
    private BigDecimal aestheticScore;

    @Column(nullable = false, precision = 7, scale = 2)
    private BigDecimal collaborationScore;

    @Column(nullable = false, precision = 7, scale = 2)
    private BigDecimal initiativeScore;

    @Column(nullable = false, precision = 7, scale = 2)
    private BigDecimal attendanceScore;

    @Column(nullable = false, precision = 7, scale = 2)
    private BigDecimal weeklyReportScore;

    @Column(length = 2000)
    private String comment;

    @Column(nullable = false, precision = 7, scale = 2)
    private BigDecimal weeklyReportDeduction = BigDecimal.ZERO;

    @Column(nullable = false)
    private String updatedBy;

    @Column(nullable = false)
    private LocalDateTime updatedAt;

    @Column(nullable = false, precision = 7, scale = 2)
    private BigDecimal totalScore;

    @Column(length = 2000)
    private String completionReason;

    @Column(length = 2000)
    private String collaborationReason;

    @Column(length = 2000)
    private String attendanceReason;

    @Column(length = 2000)
    private String weeklyReportReason;

    @Column(length = 2000)
    private String weeklyReportDeductionReason;
}
