package com.emie.designpm.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Task inspiration links are separate from delivery evidence and reference uploads. */
@Data
@NoArgsConstructor
@Entity
@Table(
        name = "task_image_library_links",
        uniqueConstraints =
                @UniqueConstraint(
                        name = "uk_task_image_library_link",
                        columnNames = {"task_id", "library_item_id"}))
public class TaskImageLibraryLink {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "task_id", nullable = false)
    private Long taskId;

    @Column(name = "library_item_id", nullable = false)
    private Long libraryItemId;

    @Column(name = "actor_user_id", nullable = false, length = 100)
    private String actorUserId;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;
}
