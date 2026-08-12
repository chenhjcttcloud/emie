package com.emie.designpm.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.time.LocalDateTime;

@Data @NoArgsConstructor @Entity @Table(name="standard_point_configs")
public class StandardPointConfig {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
    @Column(name="config_code", nullable=false, unique=true, length=80) private String configCode;
    @Column(nullable=false) private Integer points = 0;
    @Column(nullable=false) private Double performanceBase = 0d;
    @Column(nullable=false, length=20) private String departmentType = "SUPPORT";
    @Column(nullable=false) private boolean enabled = true;
    @Column(length=255) private String description;
    @Column(nullable=false) private LocalDateTime createdAt;
    @Column(nullable=false) private LocalDateTime updatedAt;
    @PrePersist void create(){createdAt=updatedAt=LocalDateTime.now();}
    @PreUpdate void update(){updatedAt=LocalDateTime.now();}
}
