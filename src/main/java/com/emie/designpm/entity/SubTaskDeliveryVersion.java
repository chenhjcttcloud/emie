package com.emie.designpm.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@Entity
@Table(name = "sub_task_delivery_versions",
        uniqueConstraints = @UniqueConstraint(name = "uk_sub_task_delivery_version", columnNames = {"sub_task_id", "version_no"}),
        indexes = @Index(name = "idx_delivery_version_task_time", columnList = "sub_task_id,submitted_at"))
public class SubTaskDeliveryVersion {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "sub_task_id", nullable = false)
    private SubTask subTask;

    @Column(name = "version_no", nullable = false)
    private Integer versionNo;

    /** initial / correction / redelivery */
    @Column(name = "submission_type", nullable = false, length = 20)
    private String submissionType;

    @Column(name = "change_summary", length = 500)
    private String changeSummary;

    @Column(columnDefinition = "TEXT")
    private String deliverables;

    @Column(name = "reference_images_json", columnDefinition = "LONGTEXT")
    private String referenceImagesJson;

    @Column(name = "attachments_json", columnDefinition = "LONGTEXT")
    private String attachmentsJson;

    @Column(name = "actual_date", length = 10)
    private String actualDate;

    @Column(name = "self_score")
    private Double selfScore;

    @Column(name = "submitted_by_id")
    private String submittedById;

    @Column(name = "submitted_by_name")
    private String submittedByName;

    @Column(name = "submitted_by_role", length = 30)
    private String submittedByRole;

    @Column(name = "submitted_at", nullable = false)
    private LocalDateTime submittedAt;

    @PrePersist
    void onCreate() {
        if (submittedAt == null) submittedAt = LocalDateTime.now();
    }
}
