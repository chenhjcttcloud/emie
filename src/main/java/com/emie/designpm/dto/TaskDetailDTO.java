package com.emie.designpm.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import java.util.Map;
import lombok.Data;

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
    private String pointRuleCode;
    private Double basePointSnapshot;
    private boolean supportsChangeTiers;
    private String smallChangeNoteSnapshot;
    private String largeChangeNoteSnapshot;
    private Double difficultyMultiplierSnapshot;
    private boolean conceptReserveExempt;
    private String pendingChangeRuleCode;
    private String pendingChangeBonusRequestId;
    private Double pendingChangeBonusPoints;
    private String pendingChangeBonusReason;
    private Map<String, Object> pointSummary;
    private String pendingChangeReferenceImagesJson;
    private Integer qualityBonusThresholdSnapshot;
    private Double qualityBonusRatioSnapshot;
    private Integer qualityTopThresholdSnapshot;
    private Double qualityTopRatioSnapshot;
    private Double maxTotalMultiplierSnapshot;
    private String collaboratorAllocationsJson;
    private String milestoneMonth;
    private String assignmentReason;
    private Boolean countInPerformanceSnapshot;
    private String requiredSkillTagsJson;
    private String allocationStatus;
    private String marketPublishedAt;
    private String claimedAt;
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

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private List<UploadedFile> uploadedFiles;

    private Long activeRejectionCycleId;
    private String activeRejectionRole;
    private String createdAt;

    public record UploadedFile(String storedName, String name, Long size) {}
}
