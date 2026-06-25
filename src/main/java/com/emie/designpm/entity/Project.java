package com.emie.designpm.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Data
@NoArgsConstructor
@Entity
@Table(name = "projects")
public class Project {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** channel_custom / regular */
    @Column(nullable = false)
    private String type;

    /** draft / pending_planner / planner_accepted / in_progress / completed */
    @Column(nullable = false)
    private String status = "draft";

    // 渠道定制单特有
    private String salesId;
    private String salesName;

    // 产品企划
    @Column(nullable = false)
    private String plannerId;
    @Column(nullable = false)
    private String plannerName;

    @Column(nullable = false)
    private String deadline;

    @Column(columnDefinition = "TEXT")
    private String productRequirements;

    @Column(columnDefinition = "TEXT")
    private String description;

    /** reference images stored as JSON array string */
    @Column(columnDefinition = "LONGTEXT")
    private String referenceImagesJson;

    /** attachments stored as JSON array string */
    @Column(columnDefinition = "LONGTEXT")
    private String attachmentsJson;

    /** 终止请求发起方（用于双方确认终止流程） */
    private String terminateRequester;

    /** 暂停前的状态（用于恢复） */
    private String prePauseStatus;

    @OneToMany(mappedBy = "project", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @OrderBy("id ASC")
    private List<SubTask> tasks = new ArrayList<>();

    @OneToMany(mappedBy = "project", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @OrderBy("time ASC")
    private List<ActivityLog> logs = new ArrayList<>();

    @Column(nullable = false)
    private LocalDateTime createdAt;

    @Column(nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
        if (status == null) status = "draft";
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
