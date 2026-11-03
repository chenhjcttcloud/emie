package com.emie.designpm.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "assistant_model_profiles")
@Getter
@Setter
public class AssistantModelProfile {
    @Id
    @Column(length = 64)
    private String id;

    @Column(nullable = false, length = 100)
    private String displayName;

    @Column(nullable = false, length = 30)
    private String provider;

    @Column(nullable = false, length = 30)
    private String dialect;

    @Column(nullable = false, length = 15)
    private String thinkingMode;

    @Column(nullable = false, length = 500)
    private String baseUrl;

    @Column(nullable = false, length = 150)
    private String model;

    @Column(nullable = false)
    private boolean visionSupported;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String encryptedKey;

    @Column(nullable = false)
    private LocalDateTime updatedAt;
}
