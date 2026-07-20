package com.emie.designpm.dto;

import lombok.Data;

/** 前端项目列表 + 统计用，简化数据 */
@Data
public class ProjectSummaryDTO {
    private Long id;
    private String projectCode;
    private String type;
    private String status;
    private String statusLabel;
    private String statusCls;
    private String salesName;
    private String plannerName;
    private String productName;
    private String deadline;
    private String productRequirements;
    private String productCategory;
    private String targetMarket;
    private String complianceItems;
    private String priceRange;
    private String ipName;
    private String ipSubOptions;
    private int taskCount;
    private int approvedTaskCount;
    private int progressPercent;
    private Double score;
    private String createdAt;
    private String updatedAt;
}
