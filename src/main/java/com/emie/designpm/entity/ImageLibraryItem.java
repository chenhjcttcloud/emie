package com.emie.designpm.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "image_library_items")
public class ImageLibraryItem {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false, length = 160)
    private String name;
    @Column(nullable = false, length = 100)
    private String ipName;
    @Column(columnDefinition = "TEXT")
    private String subOptionsJson;
    @Column(length = 1000)
    private String notes;
    @Column(nullable = false, columnDefinition = "TEXT")
    private String imagesJson;
    @Column(nullable = false, length = 100)
    private String ownerUserId;
    @Column(nullable = false)
    private LocalDateTime createdAt;

    @PrePersist void onCreate() { if (createdAt == null) createdAt = LocalDateTime.now(); }
}
