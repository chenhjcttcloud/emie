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
@Table(name = "projects", indexes = {
    @Index(name = "idx_sales_id", columnList = "salesId"),
    @Index(name = "idx_planner_id", columnList = "plannerId"),
    @Index(name = "idx_project_status", columnList = "status"),
    @Index(name = "idx_created_at", columnList = "createdAt")
})
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

    /** 产品类目 */
    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "product_category_id")
    private ProductCategory productCategory;

    /** 产品类目为"其他"时的补充说明 */
    @Column(columnDefinition = "TEXT")
    private String productCategoryNote;

    /** 目标市场：JSON数组，如 ["国内"] 或 ["国内","海外"] */
    private String targetMarket;

    /** 合规处罚：JSON数组，如 ["蓝牙","无线发射"] */
    @Column(columnDefinition = "TEXT")
    private String complianceItems;

    /** 参考零售价 */
    private String priceRange;

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
