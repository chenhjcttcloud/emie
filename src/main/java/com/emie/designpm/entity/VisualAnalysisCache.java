package com.emie.designpm.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "visual_analysis_cache")
@Getter
@Setter
public class VisualAnalysisCache {
    @Id
    @Column(length = 255)
    private String storedName;

    @Column(nullable = false, length = 64)
    private String fingerprint;

    @Column(nullable = false, length = 20)
    private String status;

    @Column(columnDefinition = "TEXT")
    private String profileJson;

    @Column(length = 240)
    private String message;

    @Column(nullable = false)
    private int attempts;

    private LocalDateTime retryAfter;

    @Column(nullable = false)
    private LocalDateTime updatedAt;
}
