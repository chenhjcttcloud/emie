package com.emie.designpm.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/** 独立的设计需求项目，不与渠道定制单、公司常规品共用 projects 表。 */
@Data
@NoArgsConstructor
@Entity
@Table(name = "design_requirements", indexes = {
        @Index(name = "idx_design_requirement_status", columnList = "status"),
        @Index(name = "idx_design_requirement_owner", columnList = "ownerId"),
        @Index(name = "idx_design_requirement_planner", columnList = "plannerId"),
        @Index(name = "idx_design_requirement_designer", columnList = "designerId"),
        @Index(name = "idx_design_requirement_created", columnList = "createdAt")
})
public class DesignRequirement {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(length = 30, unique = true)
    private String requirementCode;

    @Column(nullable = false, length = 200)
    private String name;

    @Column(nullable = false, length = 40)
    private String status = "draft";

    private String ownerId;
    private String ownerName;
    private String customerName;
    private String responsibleId;
    private String responsibleName;
    private String responsibleRole;
    private String plannerId;
    private String plannerName;
    private String designerId;
    private String designerName;
    private String deadline;

    @Column(columnDefinition = "TEXT")
    private String requirements;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(columnDefinition = "LONGTEXT")
    private String attachmentsJson;

    @Column(columnDefinition = "LONGTEXT")
    private String referenceImagesJson;

    @Column(columnDefinition = "TEXT")
    private String deliveryContent;

    @Column(columnDefinition = "LONGTEXT")
    private String deliveryAttachmentsJson;

    @Column(columnDefinition = "LONGTEXT")
    private String deliveryReferenceImagesJson;

    private LocalDateTime deliveredAt;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    @Column(nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = createdAt;
        if (status == null) status = "draft";
    }

    @PreUpdate
    protected void onUpdate() { updatedAt = LocalDateTime.now(); }
}
