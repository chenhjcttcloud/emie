package com.emie.designpm.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@Entity
@Table(name = "monthly_user_point_targets", uniqueConstraints =
        @UniqueConstraint(name = "uk_monthly_user_point_target", columnNames = {"month_key", "user_id"}))
public class MonthlyUserPointTarget {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name = "month_key", nullable = false, length = 7) private String monthKey;
    @Column(name = "user_id", nullable = false, length = 100) private String userId;
    @Column(name = "user_name", length = 100) private String userName;
    @Column(name = "target_points", nullable = false) private Integer targetPoints;
    @Column(name = "updated_by", length = 100) private String updatedBy;
    @Column(nullable = false) private LocalDateTime createdAt;
    @Column(nullable = false) private LocalDateTime updatedAt;
    @PrePersist void create() { createdAt = updatedAt = LocalDateTime.now(); }
    @PreUpdate void update() { updatedAt = LocalDateTime.now(); }
}
