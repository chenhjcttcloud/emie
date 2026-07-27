package com.emie.designpm.dto;

import lombok.Data;
import java.util.List;
import java.util.Map;

/** 子任务详细信息 */
@Data
public class TaskDetailDTO {
    private Long id;
    private String name;
    private String status;
    private String statusLabel;
    private String statusCls;
    private String statusIcon;
    private String workflowStage;
    private String plannedDate;
    private String actualDate;
    private String designerId;
    private String designerName;
    private String assigneeRole;
    private String details;
    private String deliverables;
    private String attachmentsJson;
    private String referenceImagesJson;
    private String reviewComments;
    private Double selfScore;
    private Double selfAesthetics;
    private Double selfInnovation;
    private List<Map<String, Object>> scoringRecords;
    private List<Map<String, Object>> rejectionRecords;
    private List<Map<String, Object>> deliveryVersions;
    private String createdAt;
}
