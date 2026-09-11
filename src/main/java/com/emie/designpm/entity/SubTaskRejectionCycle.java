package com.emie.designpm.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

/** A durable, reversible snapshot for one sub-task rejection cycle. */
@Data
@NoArgsConstructor
@Entity
@Table(
        name = "sub_task_rejection_cycles",
        uniqueConstraints =
                @UniqueConstraint(
                        name = "uk_sub_task_rejection_cycle",
                        columnNames = {"sub_task_id", "sequence_no"}),
        indexes = @Index(name = "idx_rejection_cycle_task_status", columnList = "sub_task_id,status"))
public class SubTaskRejectionCycle {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "sub_task_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private SubTask subTask;

    @Column(name = "sequence_no", nullable = false)
    private Integer sequenceNo;

    @Column(nullable = false, length = 20)
    private String status = "ACTIVE";

    @Column(nullable = false, length = 30)
    private String rejectionRole;

    private String rejectedById;
    private String rejectedByName;

    @Column(nullable = false)
    private LocalDateTime rejectedAt;

    @Column(nullable = false, length = 30)
    private String priorTaskStatus;

    private String priorPlannedDate;

    @Column(columnDefinition = "TEXT")
    private String priorReviewComments;

    @Column(columnDefinition = "LONGTEXT")
    private String priorReviewSnapshotJson;

    @Column(columnDefinition = "TEXT")
    private String reason;

    private String requiredCompletionDate;

    @Column(columnDefinition = "LONGTEXT")
    private String referenceImagesJson;

    @Column(columnDefinition = "LONGTEXT")
    private String attachmentsJson;

    private String cancelledById;
    private String cancelledByName;
    private String cancelledByRole;
    private LocalDateTime cancelledAt;
}
