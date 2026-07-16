package com.emie.designpm.dto;

import lombok.Data;
import java.util.List;
import java.util.Map;

/** 项目详情，包含子任务、日志、评分等完整信息 */
@Data
public class ProjectDetailDTO {
    private Long id;
    private String type;
    private String status;
    private String statusLabel;
    private String statusCls;
    private String salesName;
    private String salesId;
    private String plannerName;
    private String plannerId;
    private String productName;
    private String deadline;
    private String productRequirements;
    private String description;
    private String productCategory;
    private String productCategoryNote;
    private String targetMarket;
    private String complianceItems;
    private String priceRange;
    private String ipName;
    private String ipSubOptions;
    private String referenceImagesJson;
    private String attachmentsJson;
    private List<Map<String, Object>> logs;
    private List<TaskDetailDTO> tasks;
    private int progressPercent;
    private String createdAt;
    private String updatedAt;
}
