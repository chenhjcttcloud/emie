package com.emie.designpm.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@Entity
@Table(name = "sub_tasks")
public class SubTask {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    /** pending / accepted / delivered / approved / rejected */
    @Column(nullable = false)
    private String status = "pending";

    @Column(nullable = false)
    private String plannedDate;

    private String actualDate;
    private String designerId;
    private String designerName;

    @Column(columnDefinition = "TEXT")
    private String details;

    @Column(columnDefinition = "TEXT")
    private String deliverables;

    /** attachments stored as JSON array string */
    @Column(columnDefinition = "LONGTEXT")
    private String attachmentsJson;

    /** subtask reference images stored as JSON array string */
    @Column(columnDefinition = "LONGTEXT")
    private String referenceImagesJson;

    @Column(columnDefinition = "TEXT")
    private String reviewComments;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "project_id", nullable = false)
    private Project project;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        if (status == null) status = "pending";
    }
}
