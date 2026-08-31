package com.emie.designpm.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "material_market_adoptions",
        indexes = {
                @Index(name = "idx_material_adoption_material", columnList = "material_id,created_at"),
                @Index(name = "idx_material_adoption_project", columnList = "project_id")
        })
public class MaterialMarketAdoption {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false) private Long materialId;
    @Column(nullable = false) private Long projectId;
    @Column(nullable = false, length = 20) private String adoptionType;
    @Column(nullable = false, length = 100) private String selectedBy;
    @Column(nullable = false) private LocalDateTime createdAt;
    @Transient private String projectCode;
    @Transient private String selectedByName;

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = LocalDateTime.now();
    }
}
