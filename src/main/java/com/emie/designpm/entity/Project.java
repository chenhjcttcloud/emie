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
    @Index(name = "idx_created_at", columnList = "createdAt"),
    @Index(name = "idx_project_status_planner", columnList = "status,plannerId"),
    @Index(name = "idx_project_status_sales", columnList = "status,salesId"),
    @Index(name = "idx_project_type_status", columnList = "type,status"),
    @Index(name = "idx_project_product_name", columnList = "productName"),
    @Index(name = "idx_project_type_created", columnList = "type,createdAt"),
    @Index(name = "idx_project_sales_type_created", columnList = "salesId,type,createdAt"),
    @Index(name = "idx_project_planner_type_created", columnList = "plannerId,type,createdAt")
})
@EntityListeners(com.emie.designpm.service.ProjectSyncListener.class)
public class Project {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 对外展示的项目编号：EMIE + 年月 + 当月四位序号。V39 迁移加宽至 40 并建唯一索引兜底。 */
    @Column(length = 40, unique = true)
    private String projectCode;

    /** channel_custom / regular */
    @Column(nullable = false)
    private String type;

    /** draft / pending_planner / planner_accepted / in_progress / completed */
    @Column(nullable = false)
    private String status = "draft";

    /** 项目级子任务总流程：design / design_review / three_d_review / sample_review / promotion / bulk */
    @Column(length = 30)
    private String workflowStage = "design";

    /** current / under_review / rejected / completed */
    @Column(length = 30)
    private String workflowStatus = "current";

    /** 项目协作群信息；由飞书机器人创建和维护。 */
    @Column(length = 100)
    private String feishuChatId;
    @Column(length = 20)
    private String feishuChatStatus = "not_created";
    @Column(nullable = false)
    private boolean feishuChatEnabled = false;
    @Column(columnDefinition = "TEXT")
    private String feishuChatError;
    private LocalDateTime feishuChatCreatedAt;
    private LocalDateTime feishuChatDissolvedAt;

    // 渠道定制单特有
    private String salesId;
    private String salesName;

    // 产品企划
    @Column(nullable = true)
    private String plannerId;
    @Column(nullable = true)
    private String plannerName;

    /** 产品名称（新建项目必填；历史项目允许为空以兼容存量数据） */
    @Column(length = 200)
    private String productName;

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

    /** 可选产品档案资料，按资料类型保存文件列表、是否齐全和备注。 */
    @Column(columnDefinition = "TEXT")
    private String productArchiveJson;

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

    /** IP名称；保存文本以确保管理员调整配置后历史项目仍可正常展示 */
    @Column(length = 100)
    private String ipName;

    /** 已选二级 IP，JSON 数组；保留历史项目的完整一级/二级 IP 归属。 */
    @Column(columnDefinition = "TEXT")
    private String ipSubOptions;

    @Column(length=100) private String creativeAuthorId;
    @Column(length=200) private String creativeAuthorName;
    @Column(length=50) private String source;

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
        if (workflowStage == null) workflowStage = "design";
        if (workflowStatus == null) workflowStatus = "current";
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
        if (workflowStage == null) workflowStage = "design";
        if (workflowStatus == null) workflowStatus = "current";
    }
}
