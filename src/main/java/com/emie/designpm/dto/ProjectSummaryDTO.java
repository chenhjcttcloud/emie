package com.emie.designpm.dto;

import lombok.Data;
import java.util.List;
import java.util.Map;

/** 前端项目列表 + 统计用，简化数据 */
@Data
public class ProjectSummaryDTO {
    private Long id;
    private String type;
    private String status;
    private String statusLabel;
    private String statusCls;
    private String salesName;
    private String plannerName;
    private String deadline;
    private String productRequirements;
    private int taskCount;
    private int approvedTaskCount;
    private int progressPercent;
    private String createdAt;
    private String updatedAt;
}
