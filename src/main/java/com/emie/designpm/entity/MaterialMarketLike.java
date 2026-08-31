package com.emie.designpm.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "material_market_likes",
        uniqueConstraints = @UniqueConstraint(name = "uk_material_market_like", columnNames = {"material_id", "user_id"}),
        indexes = @Index(name = "idx_material_market_like_user", columnList = "user_id"))
public class MaterialMarketLike {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long materialId;

    @Column(nullable = false, length = 100)
    private String userId;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = LocalDateTime.now();
    }
}
