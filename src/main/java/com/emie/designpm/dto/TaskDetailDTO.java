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
    private String plannedDate;
    private String actualDate;
    private String designerId;
    private String designerName;
    private String details;
    private String deliverables;
    private String attachmentsJson;
    private String referenceImagesJson;
    private String reviewComments;
    private List<Map<String, Object>> scoringRecords;
    private String createdAt;
}
