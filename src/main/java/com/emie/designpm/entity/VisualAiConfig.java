package com.emie.designpm.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "visual_ai_config")
@Getter
@Setter
public class VisualAiConfig {
    @Id
    private Long id;

    @Column(nullable = false, length = 500)
    private String baseUrl;

    @Column(nullable = false, length = 150)
    private String model;

    @Column(nullable = false)
    private boolean enabled;

    @Column(columnDefinition = "TEXT")
    private String encryptedKey;
}
